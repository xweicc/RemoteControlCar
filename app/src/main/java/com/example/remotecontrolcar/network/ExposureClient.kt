package com.example.remotecontrolcar.network

import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import java.net.Socket

class ExposureClient(
    private val onDisconnected: (() -> Unit)? = null,
    private val onConnected: ((Boolean) -> Unit)? = null
) {
    @Volatile private var isRunning = false
    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null
    private var sendJob: Job? = null

    @Volatile private var currentExposure = "1/200"
    @Volatile private var needsUpdate = false

    /**
     * 连接视频控制端口
     */
    fun connect(host: String, port: Int) {
        if (isRunning) return
        
        try {
            socket = Socket(host, port)
            socket?.soTimeout = 0
            writer = OutputStreamWriter(socket?.getOutputStream())
            isRunning = true
            needsUpdate = true
            onConnected?.invoke(true)
        } catch (e: Exception) {
            e.printStackTrace()
            isRunning = false
            onConnected?.invoke(false)
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        isRunning = false
        sendJob?.cancel()
        sendJob = null
        try {
            writer?.close()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        writer = null
        socket = null
        onDisconnected?.invoke()
    }

    /**
     * 设置曝光值
     */
    fun setExposure(exposure: String) {
        if (currentExposure == exposure) return
        currentExposure = exposure
        needsUpdate = true
    }

    /**
     * 开始发送曝光值
     */
    fun startSending(scope: CoroutineScope) {
        sendJob?.cancel()
        sendJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                if (needsUpdate && writer != null) {
                    try {
                        writer?.write("manualExposure:$currentExposure\n")
                        writer?.flush()
                        needsUpdate = false
                    } catch (e: Exception) {
                        e.printStackTrace()
                        isRunning = false
                    }
                }
                delay(50) // 20Hz 发送频率
            }
        }
    }

    val isConnected: Boolean
        get() = isRunning
}
