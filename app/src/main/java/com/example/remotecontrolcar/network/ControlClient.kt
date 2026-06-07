package com.example.remotecontrolcar.network

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ControlClient(
    private val onTelemetry: (signal: Int, voltageMv: Int) -> Unit,
    private val onGps: (lat: Double, lng: Double, speed: Float) -> Unit = { _, _, _ -> },
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile var isRunning = false
        private set
    private var host = ""
    private var port = 0
    private val recvBuffer = ByteArrayOutputStream()

    companion object {
        private const val MAGIC_0 = 0x5A
        private const val MAGIC_1 = 0xA5
        private const val TYPE_CONTROL = 0x01
        private const val TYPE_TELEMETRY = 0x02
        private const val TYPE_GPS = 0x03
        private const val CONTROL_PACKET_SIZE = 10
        private const val TELEMETRY_PACKET_SIZE = 11
        private const val GPS_PACKET_SIZE = 25
    }

    fun start(host: String) {
        this.host = host
        this.isRunning = true
        scope.launch { connectAndRead() }
    }

    fun start(host: String, port: Int) {
        this.host = host
        this.port = port
        this.isRunning = true
        scope.launch { connectAndRead() }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        close()
    }

    fun reconnect() { close() }

    fun disconnect() {
        isRunning = false
        close()
    }

    fun connect(host: String) {
        this.host = host
        this.isRunning = true
        scope.launch { connectAndRead() }
    }

    fun connect(host: String, port: Int) {
        this.host = host
        this.port = port
        this.isRunning = true
        scope.launch { connectAndRead() }
    }

    fun sendControl(throttle: Int, steering: Int, light: Int) {
        val os = outputStream
        if (os == null) return
        try {
            os.write(encodeControl(throttle, steering, light))
            os.flush()
        } catch (_: Exception) {}
    }

    private suspend fun connectAndRead() {
        while (scope.isActive && isRunning) {
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(host, port), 5000)
                socket = sock
                outputStream = sock.getOutputStream()
                withContext(Dispatchers.Main) { onConnectionChanged(true) }
                val input = sock.getInputStream()
                val buf = ByteArray(256)
                recvBuffer.reset()
                while (isRunning && !sock.isClosed) {
                    val len = input.read(buf)
                    if (len < 0) break
                    processReceivedData(buf, len)
                }
            } catch (_: Exception) {
            } finally {
                close()
                withContext(Dispatchers.Main) { onConnectionChanged(false) }
            }
            if (isRunning) delay(2000)
        }
    }

    private fun processReceivedData(data: ByteArray, len: Int) {
        recvBuffer.write(data, 0, len)
        val buf = recvBuffer.toByteArray()
        var i = 0
        while (i < buf.size) {
            // Need at least 4 bytes to read magic + type + length
            if (i + 4 > buf.size) break
            if (buf[i] != MAGIC_0.toByte() || buf[i + 1] != MAGIC_1.toByte()) { i++; continue }
            val type = buf[i + 2].toInt() and 0xFF
            val packetLen = buf[i + 3].toInt() and 0xFF
            if (packetLen < 4 || i + packetLen > buf.size) break
            // Verify checksum
            var checksum: Byte = 0
            for (j in 0 until packetLen - 1) checksum = (checksum.toInt() xor buf[i + j].toInt()).toByte()
            if (checksum != buf[i + packetLen - 1]) { i++; continue }
            when (type) {
                TYPE_TELEMETRY -> {
                    if (packetLen >= TELEMETRY_PACKET_SIZE) {
                        val signal = ((buf[i + 4].toInt() and 0xFF) or (buf[i + 5].toInt() shl 8)).toShort()
                        val voltageMv = (buf[i + 6].toInt() and 0xFF) or
                                ((buf[i + 7].toInt() and 0xFF) shl 8) or
                                ((buf[i + 8].toInt() and 0xFF) shl 16) or
                                ((buf[i + 9].toInt() and 0xFF) shl 24)
                        onTelemetry(signal.toInt(), voltageMv)
                    }
                }
                TYPE_GPS -> {
                    if (packetLen >= GPS_PACKET_SIZE) {
                        val bb = ByteBuffer.wrap(buf, i + 4, 20).order(ByteOrder.LITTLE_ENDIAN)
                        val lat = bb.double  // 8 bytes
                        val lng = bb.double  // 8 bytes
                        val speed = bb.float // 4 bytes
                        onGps(lat, lng, speed)
                    }
                }
            }
            i += packetLen
        }
        recvBuffer.reset()
        if (i < buf.size) recvBuffer.write(buf, i, buf.size - i)
    }

    /**
     * Control packet (C-struct compatible, packed):
     *   uint8_t  magic[2]   = {0x5A, 0xA5}
     *   uint8_t  type       = 0x01
     *   uint8_t  length     = 10
     *   uint16_t throttle   (LE, 0-1024, 512=stop)
     *   uint16_t steering   (LE, 0-1024, 512=center)
     *   uint8_t  light      (0=off,1=low,2=mid,3=high)
     *   uint8_t  checksum   (XOR of all preceding bytes)
     */
    private fun encodeControl(throttle: Int, steering: Int, light: Int): ByteArray {
        val p = ByteArray(CONTROL_PACKET_SIZE)
        p[0] = MAGIC_0.toByte(); p[1] = MAGIC_1.toByte()
        p[2] = TYPE_CONTROL.toByte()
        p[3] = CONTROL_PACKET_SIZE.toByte()
        p[4] = (throttle and 0xFF).toByte(); p[5] = ((throttle shr 8) and 0xFF).toByte()
        p[6] = (steering and 0xFF).toByte(); p[7] = ((steering shr 8) and 0xFF).toByte()
        p[8] = light.toByte()
        var cs: Byte = 0
        for (j in 0..8) cs = (cs.toInt() xor p[j].toInt()).toByte()
        p[9] = cs
        return p
    }

    private fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; outputStream = null
    }
}
