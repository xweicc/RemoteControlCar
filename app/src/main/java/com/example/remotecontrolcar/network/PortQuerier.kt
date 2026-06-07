package com.example.remotecontrolcar.network

import java.net.Socket

data class DevicePorts(
    val video0: Int,
    val video1: Int,
    val audio: Int,
    val control: Int
)

object PortQuerier {

    /**
     * 查询设备端口
     * @param host 服务器地址
     * @param queryPort 查询端口
     * @param sn 设备 SN
     * @return DevicePorts 成功时返回端口列表，失败返回 null
     */
    fun query(host: String, queryPort: Int, sn: String): DevicePorts? {
        return try {
            Socket(host, queryPort).use { socket ->
                socket.soTimeout = 5000

                val writer = socket.getOutputStream().bufferedWriter()
                writer.write("QUERY $sn\n")
                writer.flush()

                val response = socket.getInputStream().bufferedReader().readLine()
                    ?: return null

                when {
                    response.startsWith("OK ") -> {
                        val parts = response.substring(3).split(",")
                        if (parts.size == 4) {
                            DevicePorts(
                                video0  = parts[0].trim().toInt(),
                                video1  = parts[1].trim().toInt(),
                                audio   = parts[2].trim().toInt(),
                                control = parts[3].trim().toInt()
                            )
                        } else null
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
