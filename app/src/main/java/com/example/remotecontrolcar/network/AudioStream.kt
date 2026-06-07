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
 * - 接收：服务端 → raw PCM 字节流 → AudioTrack 播放
 * - 发送：AudioRecord 录音 → raw PCM 字节流 → 服务端
 *
 * 采样率 16kHz / 单声道 / 16-bit PCM little-endian
 */
class AudioStream {

    companion object {
        private const val SAMPLE_RATE = 16000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: Socket? = null
    private var audioTrack: AudioTrack? = null
    private var recorder: AudioRecord? = null

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
            val pcmBuf = ByteArray(8192)
            while (isRunning && !sock.isClosed) {
                val len = input.read(pcmBuf)
                if (len < 0) break
                val aligned = len and 0x7FFFFFFE
                if (aligned > 0) {
                    val chunk = ByteArray(aligned)
                    System.arraycopy(pcmBuf, 0, chunk, 0, aligned)
                    pcmChannel.send(chunk) // 队列满时挂起，不阻塞 socket 读取
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

            // 发送循环：AudioRecord → raw PCM 16-bit LE
            val pcmBuf = ByteArray(bufSize * 2)
            while (isMicActive && isRunning) {
                val read = rec.read(pcmBuf, 0, pcmBuf.size)
                if (read <= 0) break
                os.write(pcmBuf, 0, read)
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
