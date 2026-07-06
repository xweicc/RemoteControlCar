package com.example.remotecontrolcar.network

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

class ControlClient(
    private val onTelemetry: (signal: Int, voltageMv: Int) -> Unit,
    private val onGps: (lat: Double, lng: Double, speed: Int) -> Unit = { _, _, _ -> },
    private val onLatency: (Int) -> Unit = { _ -> },
    private val onNoResponse: () -> Unit = {},
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile var isRunning = false
        private set
    private var host = ""
    private var port = 0
    private var motorMode = 0
    private var lightMode = 0
    private var useUdp = false
    private val recvBuffer = ByteArrayOutputStream()
    @Volatile private var sentTm: Long = 0
    @Volatile private var lastTmSendTime: Long = 0
    private var baseTimeMs: Long = 0
    @Volatile private var lastResponseTime: Long = 0
    @Volatile private var noResponseTriggered: Boolean = false

    companion object {
        private const val MAGIC_0 = 0x5A
        private const val MAGIC_1 = 0xA5
        private const val TYPE_CONTROL = 0x01
        private const val TYPE_TELEMETRY = 0x02
        private const val TYPE_GPS = 0x03
        private const val TYPE_CONFIG = 0x04
        private const val CONTROL_PACKET_SIZE = 14
        private const val TELEMETRY_PACKET_SIZE = 15
        private const val GPS_PACKET_SIZE = 30
        private const val CONFIG_PACKET_SIZE = 6
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

    fun connect(host: String, port: Int, motorMode: Int = 0, lightMode: Int = 0, useUdp: Boolean = false) {
        this.host = host
        this.port = port
        this.motorMode = motorMode
        this.lightMode = lightMode
        this.useUdp = useUdp
        this.baseTimeMs = System.currentTimeMillis()
        this.lastResponseTime = this.baseTimeMs
        this.noResponseTriggered = false
        this.isRunning = true
        if (useUdp) {
            scope.launch { udpLoop() }
        } else {
            scope.launch { connectAndRead() }
        }
    }

    fun sendControl(throttle: Int, steering: Int, light: Int) {
        try {
            // 每秒发送一次 tm（相对时间，32位）
            val now = System.currentTimeMillis()
            val tm = if (now - lastTmSendTime >= 1000) {
                lastTmSendTime = now
                val relativeTm = (now - baseTimeMs) and 0xFFFFFFFFL
                sentTm = relativeTm
                // UDP 模式：检查是否超过3秒未收到响应
                if (useUdp && now - lastResponseTime >= 3000 && !noResponseTriggered) {
                    noResponseTriggered = true
                    onNoResponse()
                }
                relativeTm
            } else 0L
            val packet = encodeControl(throttle, steering, light, tm)
            if (useUdp) {
                val ds = udpSocket ?: return
                val dp = DatagramPacket(packet, packet.size, java.net.InetAddress.getByName(host), port)
                ds.send(dp)
            } else {
                val os = outputStream ?: return
                os.write(packet)
                os.flush()
            }
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
                // 连接成功后先发送配置包
                sock.getOutputStream().write(encodeConfig(motorMode, lightMode))
                sock.getOutputStream().flush()
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
            // 收到有效响应，重置无响应计时器
            lastResponseTime = System.currentTimeMillis()
            noResponseTriggered = false
            when (type) {
                TYPE_TELEMETRY -> {
                    if (packetLen >= TELEMETRY_PACKET_SIZE) {
                        val signal = ((buf[i + 4].toInt() and 0xFF) or (buf[i + 5].toInt() shl 8)).toShort()
                        val voltageMv = (buf[i + 6].toInt() and 0xFF) or
                                ((buf[i + 7].toInt() and 0xFF) shl 8) or
                                ((buf[i + 8].toInt() and 0xFF) shl 16) or
                                ((buf[i + 9].toInt() and 0xFF) shl 24)
                        val tm = (buf[i + 10].toLong() and 0xFF) or
                                ((buf[i + 11].toLong() and 0xFF) shl 8) or
                                ((buf[i + 12].toLong() and 0xFF) shl 16) or
                                ((buf[i + 13].toLong() and 0xFF) shl 24)
                        onTelemetry(signal.toInt(), voltageMv)
                        // 计算延迟
                        if (tm != 0L && sentTm != 0L && tm == sentTm) {
                            val sentAbsolute = baseTimeMs + tm
                            val rtt = System.currentTimeMillis() - sentAbsolute
                            onLatency((rtt / 2).toInt())
                        }
                    }
                }
                TYPE_GPS -> {
                    if (packetLen >= GPS_PACKET_SIZE) {
                        val latStr = String(buf, i + 4, 12, Charsets.US_ASCII).trimEnd('\u0000')
                        val lngStr = String(buf, i + 16, 12, Charsets.US_ASCII).trimEnd('\u0000')
                        val speed = buf[i + 28].toInt() and 0xFF
                        val lat = latStr.toDoubleOrNull() ?: 0.0
                        val lng = lngStr.toDoubleOrNull() ?: 0.0
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
     *   uint8_t  length     = 14
     *   uint16_t throttle   (LE, 0-1024, 512=stop)
     *   uint16_t steering   (LE, 0-1024, 512=center)
     *   uint8_t  light      (0=off,1=low,2=mid,3=high)
     *   uint32_t tm         (LE, ms timestamp, 0=no latency probe)
     *   uint8_t  checksum   (XOR of all preceding bytes)
     */
    private fun encodeControl(throttle: Int, steering: Int, light: Int, tm: Long): ByteArray {
        val p = ByteArray(CONTROL_PACKET_SIZE)
        p[0] = MAGIC_0.toByte(); p[1] = MAGIC_1.toByte()
        p[2] = TYPE_CONTROL.toByte()
        p[3] = CONTROL_PACKET_SIZE.toByte()
        p[4] = (throttle and 0xFF).toByte(); p[5] = ((throttle shr 8) and 0xFF).toByte()
        p[6] = (steering and 0xFF).toByte(); p[7] = ((steering shr 8) and 0xFF).toByte()
        p[8] = light.toByte()
        p[9] = (tm and 0xFF).toByte()
        p[10] = ((tm shr 8) and 0xFF).toByte()
        p[11] = ((tm shr 16) and 0xFF).toByte()
        p[12] = ((tm shr 24) and 0xFF).toByte()
        var cs: Byte = 0
        for (j in 0..12) cs = (cs.toInt() xor p[j].toInt()).toByte()
        p[13] = cs
        return p
    }

    /**
     * Config packet (C-struct compatible, packed):
     *   uint8_t  magic[2]      = {0x5A, 0xA5}
     *   uint8_t  type          = 0x04
     *   uint8_t  length        = 6
     *   uint8_t  motor_mode    (0=内置电调, 1=外部电调)
     *   uint8_t  light_mode    (0=内置控制, 1=外部控制)
     *   uint8_t  checksum      (XOR of all preceding bytes)
     */
    private fun encodeConfig(motorMode: Int, lightMode: Int): ByteArray {
        val p = ByteArray(CONFIG_PACKET_SIZE)
        p[0] = MAGIC_0.toByte(); p[1] = MAGIC_1.toByte()
        p[2] = TYPE_CONFIG.toByte()
        p[3] = CONFIG_PACKET_SIZE.toByte()
        p[4] = motorMode.toByte()
        p[5] = lightMode.toByte()
        var cs: Byte = 0
        for (j in 0 until CONFIG_PACKET_SIZE - 1) cs = (cs.toInt() xor p[j].toInt()).toByte()
        p[CONFIG_PACKET_SIZE - 1] = cs
        return p
    }

    private fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; outputStream = null
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
    }

    /**
     * UDP 模式：发送控制包 + 接收遥测/GPS 响应
     */
    private suspend fun udpLoop() {
        while (scope.isActive && isRunning) {
            try {
                val ds = DatagramSocket()
                ds.soTimeout = 3000
                udpSocket = ds
                // 发送配置包
                val configPacket = encodeConfig(motorMode, lightMode)
                val configDp = DatagramPacket(configPacket, configPacket.size,
                    java.net.InetAddress.getByName(host), port)
                ds.send(configDp)
                withContext(Dispatchers.Main) { onConnectionChanged(true) }
                val buf = ByteArray(256)
                while (isRunning && !ds.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        ds.receive(packet)
                        recvBuffer.reset()
                        processReceivedData(packet.data, packet.length)
                    } catch (_: java.net.SocketTimeoutException) {
                        // 超时继续，保持循环
                    }
                }
            } catch (_: Exception) {
            } finally {
                close()
                withContext(Dispatchers.Main) { onConnectionChanged(false) }
            }
            if (isRunning) delay(2000)
        }
    }
}
