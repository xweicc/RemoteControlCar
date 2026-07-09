package com.example.remotecontrolcar.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 音频流管理器：单个 TCP 连接实现双向音频对讲
 *
 * - 接收：服务端 → G.711a 字节流 → 解码为 PCM → AudioTrack 播放
 * - 发送：AudioRecord 录音 → PCM → 编码为 G.711a → 服务端
 *
 * 采样率 16kHz / 单声道 / 16-bit PCM，传输使用 G.711a 压缩（2:1）
 */
class AudioStream {

    companion object {
        private const val SAMPLE_RATE = 16000

        /** G.711a 解码（与服务端 alaw_to_pcm 一致） */
        private fun decodeAlawSample(aVal: Int): Short {
            val sign = if ((aVal and 0x80) != 0) 0x80 else 0  // 先提取原始符号位
            var v = aVal xor 0x55                              // 再 XOR 还原
            v = v and 0x7F
            val seg = (v shr 4) and 0x07
            val mantissa = v and 0x0F
            val pcm = if (seg == 0) {
                (mantissa shl 4) + 8
            } else {
                ((mantissa shl 4) + 0x108) shl (seg - 1)
            }
            return (if (sign != 0) -pcm else pcm).toShort()
        }

        /** G.711a 解码：alaw 字节 → PCM short 数组 */
        private fun decodeAlaw(alaw: ByteArray, pcm: ShortArray, count: Int) {
            for (i in 0 until count) {
                pcm[i] = decodeAlawSample(alaw[i].toInt() and 0xFF)
            }
        }

        /** G.711a 编码：单个 16-bit linear PCM sample → 8-bit A-law */
        private fun encodeAlawSample(sample: Int): Byte {
            var pcm = sample
            val sign = (pcm and 0x8000) shr 8
            if (sign != 0) pcm = -pcm
            if (pcm > 32635) pcm = 32635
            var exponent = 7
            var mask = 0x4000
            while (exponent > 0 && (pcm and mask) == 0) {
                exponent--
                mask = mask shr 1
            }
            val alaw = if (exponent == 0) {
                (pcm shr 4) and 0x0F
            } else {
                ((pcm shr (exponent + 3)) and 0x0F) or (exponent shl 4)
            }
            return ((alaw xor (sign or 0x55))).toByte()
        }

        /** G.711a 编码：PCM short 数组 → alaw 字节数组 */
        private fun encodeAlaw(pcm: ShortArray, alaw: ByteArray, count: Int) {
            for (i in 0 until count) {
                alaw[i] = encodeAlawSample(pcm[i].toInt())
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: Socket? = null
    private var audioTrack: AudioTrack? = null
    private var recorder: AudioRecord? = null

    /** G.711a 编码开关，false 时使用 raw PCM */
    var useG711a = true

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var isMicActive = false
        private set

    /** 连接异常断开时的回调（非主动 disconnect） */
    var onDisconnected: (() -> Unit)? = null

    /**
     * 建立 TCP 连接并开始接收音频（播放）
     */
    fun connect(host: String, port: Int) {
        if (isRunning) return
        isRunning = true
        scope.launch { connectionLoop(host, port) }
    }

    /**
     * 断开连接，停止播放和录音
     */
    fun disconnect() {
        isRunning = false
        isMicActive = false
        scope.coroutineContext.cancelChildren()
        releaseRecorder()
        releaseAudioTrack()
        closeSocket()
    }

    /**
     * 在已建立的连接上启用麦克风录音发送
     */
    fun enableMic() {
        if (!isRunning || isMicActive) return
        isMicActive = true
        scope.launch { sendLoop() }
    }

    /**
     * 停止麦克风录音发送
     */
    fun disableMic() {
        isMicActive = false
        releaseRecorder()
    }

    private suspend fun connectionLoop(host: String, port: Int) {
        val pcmChannel = Channel<ByteArray>(4) // 内存队列：接收 → 播放（~1s 缓冲）
        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.receiveBufferSize = 8192 // 1 chunk，减少 socket 层缓冲
            sock.connect(InetSocketAddress(host, port), 5000)
            socket = sock

            // 初始化 AudioTrack
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val targetBuf = maxOf(minBuf * 2, SAMPLE_RATE) // 约 0.5s，减少 AudioTrack 内部缓冲
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(targetBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack = track

            // 启动播放协程：从 Channel 取数据写入 AudioTrack
            val playbackJob = scope.launch { playbackLoop(track, pcmChannel) }

            // 接收协程（当前）：从 socket 读数据放入 Channel
            val input = sock.getInputStream()
            val recvBuf = ByteArray(8192)
            while (isRunning && !sock.isClosed) {
                val len = input.read(recvBuf)
                if (len < 0) break
                if (len > 0) {
                    if (useG711a) {
                        // G.711a 解码为 PCM
                        val pcm = ShortArray(len)
                        decodeAlaw(recvBuf, pcm, len)
                        val pcmBytes = ByteArray(len * 2)
                        for (i in 0 until len) {
                            val s = pcm[i].toInt()
                            pcmBytes[i * 2] = s.toByte()
                            pcmBytes[i * 2 + 1] = (s shr 8).toByte()
                        }
                        pcmChannel.send(pcmBytes)
                    } else {
                        // raw PCM 直接转发
                        val aligned = len and 0x7FFFFFFE
                        if (aligned > 0) {
                            val chunk = ByteArray(aligned)
                            System.arraycopy(recvBuf, 0, chunk, 0, aligned)
                            pcmChannel.send(chunk)
                        }
                    }
                }
            }

            pcmChannel.close()
            playbackJob.join()
        } catch (_: Exception) {
        } finally {
            isRunning = false
            isMicActive = false
            pcmChannel.close()
            releaseRecorder()
            releaseAudioTrack()
            closeSocket()
            onDisconnected?.invoke()
        }
    }

    /**
     * 播放协程：从 Channel 取 PCM 数据，阻塞写入 AudioTrack
     *
     * write() 阻塞时挂起当前协程，不影响接收协程继续读 socket
     */
    private suspend fun playbackLoop(track: AudioTrack, channel: Channel<ByteArray>) {
        try {
            var started = false
            for (chunk in channel) {
                if (!started) {
                    track.write(chunk, 0, chunk.size) // 先填充一个缓冲区
                    track.play()
                    started = true
                } else {
                    track.write(chunk, 0, chunk.size) // 阻塞直到空间可用
                }
            }
        } catch (_: Exception) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
        }
    }

    private suspend fun sendLoop() {
        try {
            val os = socket?.getOutputStream() ?: return
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, 640)

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            recorder = rec
            // 启用声学回声消除和噪声抑制
            val sessionId = rec.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.enabled = true
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.enabled = true
            }
            rec.startRecording()

            // 发送循环
            val pcmBuf = ByteArray(bufSize * 2)
            val pcmShort = ShortArray(pcmBuf.size / 2)
            val alawOut = ByteArray(pcmBuf.size / 2)
            while (isMicActive && isRunning) {
                val read = rec.read(pcmBuf, 0, pcmBuf.size)
                if (read <= 0) break
                if (useG711a) {
                    // PCM → G.711a 编码 → 发送
                    val sampleCount = read / 2
                    for (i in 0 until sampleCount) {
                        pcmShort[i] = ((pcmBuf[i * 2].toInt() and 0xFF) or (pcmBuf[i * 2 + 1].toInt() shl 8)).toShort()
                    }
                    encodeAlaw(pcmShort, alawOut, sampleCount)
                    os.write(alawOut, 0, sampleCount)
                } else {
                    // raw PCM 直接发送
                    os.write(pcmBuf, 0, read)
                }
                os.flush()
            }
        } catch (_: Exception) {
        } finally {
            releaseRecorder()
            isMicActive = false
        }
    }

    private fun releaseRecorder() {
        try {
            recorder?.stop()
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
