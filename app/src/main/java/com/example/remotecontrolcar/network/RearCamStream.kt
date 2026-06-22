package com.example.remotecontrolcar.network

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * 后视摄像头 H265 视频流客户端
 * 单端口连接，解码到指定 Surface，支持断开自动重连
 */
class RearCamStream(
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var socket: Socket? = null
    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    @Volatile var isRunning = false
        private set
    @Volatile private var decoderStarted = false

    private var host = ""
    private var port = 0

    private val nalBuffer = ByteArray(1024 * 1024)
    private var nalBufferPos = 0
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var outputThread: Thread? = null

    fun connect(host: String, port: Int, surface: Surface) {
        this.host = host
        this.port = port
        this.surface = surface
        this.isRunning = true
        scope.launch { readLoop() }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        releaseDecoder()
        closeSocket()
    }

    private suspend fun readLoop() {
        while (scope.isActive && isRunning) {
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.receiveBufferSize = 256 * 1024
                sock.connect(InetSocketAddress(host, port), 5000)
                socket = sock
                handler.post { onConnectionChanged(true) }
                val input = sock.getInputStream()
                val buf = ByteArray(32768)
                while (isRunning && !sock.isClosed) {
                    val len = input.read(buf)
                    if (len < 0) break
                    appendAndParseNals(buf, len)
                }
            } catch (_: Exception) {
            } finally {
                closeSocket()
                handler.post { onConnectionChanged(false) }
            }
            if (isRunning) delay(2000)
        }
    }

    private fun appendAndParseNals(data: ByteArray, len: Int) {
        if (nalBufferPos + len > nalBuffer.size) {
            nalBufferPos = 0; vps = null; sps = null; pps = null; return
        }
        System.arraycopy(data, 0, nalBuffer, nalBufferPos, len)
        nalBufferPos += len
        val starts = findStartCodes(nalBuffer, nalBufferPos)
        if (starts.size < 2) return
        for (i in 0 until starts.size - 1) {
            val nal = nalBuffer.copyOfRange(starts[i], starts[i + 1])
            processNalUnit(nal)
        }
        val last = starts.last()
        val remaining = nalBufferPos - last
        System.arraycopy(nalBuffer, last, nalBuffer, 0, remaining)
        nalBufferPos = remaining
    }

    private fun findStartCodes(data: ByteArray, limit: Int): List<Int> {
        val result = mutableListOf<Int>()
        var i = 0
        while (i < limit - 3) {
            if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte()) {
                if (data[i + 2] == 0x01.toByte()) {
                    result.add(i); i += 3
                } else if (i < limit - 3 && data[i + 2] == 0x00.toByte() && data[i + 3] == 0x01.toByte()) {
                    result.add(i); i += 4
                } else i++
            } else i++
        }
        return result
    }

    private fun processNalUnit(nal: ByteArray) {
        if (nal.size < 5) return
        val offset = if (nal[2] == 0x01.toByte()) 3 else 4
        if (nal.size <= offset + 1) return
        val nalType = (nal[offset].toInt() shr 1) and 0x3F
        when (nalType) {
            32 -> vps = nal
            33 -> sps = nal
            34 -> pps = nal
        }
        if (!decoderStarted && vps != null && sps != null && pps != null) {
            initDecoder()
        }
        if (decoderStarted) feedInput(nal)
    }

    private fun initDecoder() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 640, 480)
            val csd = ByteBuffer.allocate(vps!!.size + sps!!.size + pps!!.size)
            csd.put(vps!!); csd.put(sps!!); csd.put(pps!!); csd.flip()
            format.setByteBuffer("csd-0", csd)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            dec.configure(format, surface, null, 0)
            dec.start()
            decoder = dec
            decoderStarted = true
            startOutputThread()
        } catch (_: Exception) {}
    }

    private fun feedInput(nal: ByteArray) {
        val dec = decoder ?: return
        try {
            val idx = dec.dequeueInputBuffer(10000)
            if (idx >= 0) {
                val buf = dec.getInputBuffer(idx) ?: return
                buf.clear(); buf.put(nal)
                dec.queueInputBuffer(idx, 0, nal.size, System.nanoTime() / 1000, 0)
            }
        } catch (_: Exception) {}
    }

    private fun startOutputThread() {
        outputThread = Thread {
            val dec = decoder ?: return@Thread
            val info = MediaCodec.BufferInfo()
            while (decoderStarted && isRunning) {
                try {
                    val idx = dec.dequeueOutputBuffer(info, 10000)
                    when {
                        idx >= 0 -> {
                            val render = info.size > 0 &&
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            dec.releaseOutputBuffer(idx, render)
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {} // ignore
                    }
                } catch (_: Exception) { break }
            }
        }.also { it.start() }
    }

    private fun releaseDecoder() {
        decoderStarted = false
        try { outputThread?.join(1000) } catch (_: Exception) {}
        outputThread = null
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null; vps = null; sps = null; pps = null
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
