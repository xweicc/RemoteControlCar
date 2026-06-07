package com.example.remotecontrolcar.network

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoStreamManager(
    private val onVideoSize: (Int, Int) -> Unit,
    private val onStats: (Int, Long, Int) -> Unit,
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
    @Volatile private var quickReconnect = false

    // 动态端口：video0=高清，video1=流畅
    private var video0Port = 0
    private var video1Port = 0
    private var currentPort = 0
    private var isHd = true
    private var host = ""

    private val nalBuffer = ByteArray(2 * 1024 * 1024)
    private var nalBufferPos = 0
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    private var bytesInSecond = 0L
    private var totalBytes = 0L
    @Volatile private var framesInSecond = 0
    private var outputThread: Thread? = null

    // 录像相关 - 直接从原始H265码流录制
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    @Volatile var isRecording = false
        private set
    private var recordFile: File? = null
    private var recordStartTimeUs = 0L
    private var lastFrameTimeUs = 0L

    fun start(host: String, hd: Boolean, surface: Surface, video0Port: Int, video1Port: Int) {
        this.host = host
        this.surface = surface
        this.video0Port = video0Port
        this.video1Port = video1Port
        this.isHd = hd
        this.currentPort = if (hd) video0Port else video1Port
        this.isRunning = true
        scope.launch { readLoop() }
        scope.launch { statsLoop() }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        releaseDecoder()
        closeSocket()
    }

    fun switchQuality(hd: Boolean) {
        val newPort = if (hd) video0Port else video1Port
        if (newPort != currentPort) {
            isHd = hd
            currentPort = newPort
            quickReconnect = true
            closeSocket()  // 立即关闭socket，触发读循环断开
            nalBufferPos = 0
            vps = null; sps = null; pps = null
            scope.launch { releaseDecoder() }  // 异步释放解码器，不阻塞主线程
        }
    }

    fun reconnect() { closeSocket() }

    fun startRecording(outputDir: File): Exception? {
        if (isRecording) return IllegalStateException("already recording")
        if (vps == null || sps == null || pps == null) return IllegalStateException("no stream")
        return try {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val fileName = "VID_${dateFormat.format(Date())}.mp4"
            recordFile = File(outputDir, fileName)
            muxer = MediaMuxer(recordFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // 创建干净的视频格式
            val (width, height) = if (isHd) 1920 to 1080 else 1280 to 720
            val muxerFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                width,
                height
            )
            // csd-0 = VPS+SPS+PPS
            val csd = ByteBuffer.allocate(vps!!.size + sps!!.size + pps!!.size)
            csd.put(vps!!); csd.put(sps!!); csd.put(pps!!); csd.flip()
            muxerFormat.setByteBuffer("csd-0", csd)
            videoTrackIndex = muxer!!.addTrack(muxerFormat)
            muxer!!.start()
            recordStartTimeUs = System.nanoTime() / 1000
            lastFrameTimeUs = recordStartTimeUs
            isRecording = true
            null
        } catch (e: Exception) {
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            recordFile = null
            e
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            muxer?.stop()
        } catch (_: Exception) {}
        try {
            muxer?.release()
        } catch (_: Exception) {}
        muxer = null
        videoTrackIndex = -1
    }

    fun getRecordFile(): File? = if (isRecording) null else recordFile

    fun disconnect() {
        isRunning = false
        closeSocket()
        releaseDecoder()
    }

    fun connect(host: String, hd: Boolean, surface: Surface, video0Port: Int, video1Port: Int) {
        this.host = host
        this.surface = surface
        this.video0Port = video0Port
        this.video1Port = video1Port
        this.isHd = hd
        this.currentPort = if (hd) video0Port else video1Port
        this.isRunning = true
        scope.launch { readLoop() }
        scope.launch { statsLoop() }
    }

    private suspend fun readLoop() {
        while (scope.isActive && isRunning) {
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.receiveBufferSize = 512 * 1024
                sock.connect(InetSocketAddress(host, currentPort), 5000)
                socket = sock
                handler.post { onConnectionChanged(true) }
                val input = sock.getInputStream()
                val buf = ByteArray(65536)
                while (isRunning && !sock.isClosed) {
                    val len = input.read(buf)
                    if (len < 0) break
                    totalBytes += len
                    bytesInSecond += len
                    appendAndParseNals(buf, len)
                }
            } catch (_: Exception) {
            } finally {
                closeSocket()
                handler.post { onConnectionChanged(false) }
            }
            if (isRunning) delay(if (quickReconnect) { quickReconnect = false; 500 } else 2000)
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
        // 录像：直接从原始H265码流写入muxer，跳过VPS/SPS/PPS（已在csd-0中）
        if (isRecording && muxer != null && videoTrackIndex >= 0) {
            // NAL type 32=VPS, 33=SPS, 34=PPS 跳过（已在track format的csd-0中）
            if (nalType != 32 && nalType != 33 && nalType != 34) {
                try {
                    val buf = ByteBuffer.allocate(nal.size)
                    buf.put(nal)
                    buf.flip()
                    val isKeyFrame = nalType == 19 || nalType == 20 // IDR_W_RADL or IDR_N_LP
                    val nowUs = System.nanoTime() / 1000
                    // 确保时间戳单调递增
                    val pts = if (nowUs > lastFrameTimeUs) nowUs else lastFrameTimeUs + 1
                    lastFrameTimeUs = pts
                    val info = MediaCodec.BufferInfo()
                    info.set(0, nal.size, pts - recordStartTimeUs, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer!!.writeSampleData(videoTrackIndex, buf, info)
                } catch (_: Exception) {}
            }
        }
    }

    private fun initDecoder() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080)
            val csd = ByteBuffer.allocate(vps!!.size + sps!!.size + pps!!.size)
            csd.put(vps!!); csd.put(sps!!); csd.put(pps!!); csd.flip()
            format.setByteBuffer("csd-0", csd)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
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
                            if (render) framesInSecond++
                            dec.releaseOutputBuffer(idx, render)
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            try {
                                val w = dec.outputFormat.getInteger(MediaFormat.KEY_WIDTH)
                                val h = dec.outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                                handler.post { onVideoSize(w, h) }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) { break }
            }
        }.also { it.start() }
    }

    private suspend fun statsLoop() {
        while (scope.isActive) {
            delay(1000)
            val bitrate = (bytesInSecond * 8 / 1000).toInt()
            val fps = framesInSecond
            handler.post { onStats(bitrate, totalBytes, fps) }
            bytesInSecond = 0
            framesInSecond = 0
        }
    }

    private fun releaseDecoder() {
        stopRecording()
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
