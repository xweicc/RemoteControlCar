package com.example.remotecontrolcar

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.WindowManager
import java.io.File
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.remotecontrolcar.databinding.ActivityControlBinding
import com.example.remotecontrolcar.network.AudioStream
import com.example.remotecontrolcar.network.ControlClient
import com.example.remotecontrolcar.network.ExposureClient
import com.example.remotecontrolcar.network.VideoStreamManager
import com.example.remotecontrolcar.network.RearCamStream
import com.example.remotecontrolcar.view.JoystickOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControlBinding
    private lateinit var videoManager: VideoStreamManager
    private lateinit var controlClient: ControlClient

    private var serverHost = ""
    private var isHd = true
    private var isConnected = false
    private var video0Port = 0
    private var video1Port = 0
    private var audioPort = 0
    private var controlPort = 0
    private var videoCtrlPort = 0
    private var sshPort = 0
    private var rearCamPort = 0
    private var motorMode = 0
    private var lightMode = 0
    private var useUdp = false
    private var audioStream: AudioStream? = null
    private var hasMicPermission = false
    private var lightLevel = 0
    private var throttle = 512
    private var steering = 512
    private var steeringTrim = 0  // 转向微调 -100~+100
    private var speakerVolume = 10  // 设备扬声器音量档位 1~10
    private var volumeHideTask: java.util.TimerTask? = null
    private var controlJob: Job? = null
    private var recordStartTime = 0L
    private val recordTimer = java.util.Timer()
    private var recordTimerTask: java.util.TimerTask? = null

    // 后视摄像头
    private var rearCamStream: RearCamStream? = null
    private var isRearCamOn = false

    private var controlConnected = false
    private var latencyMs = -1
    private var noResponse = false

    // 曝光控制
    private var exposureClient: ExposureClient? = null
    private var isManualExposure = false
    private val exposureValues = arrayOf("1/50", "1/100", "1/150", "1/200", "1/250", "1/500", "1/750", "1/1000", "1/2000", "1/4000")
    private var exposureIndex = 3 // 默认 1/200
    private var lastTouchY = 0f

    // 手柄摇杆状态
    private var gamepadThrottle = 0f  // 左摇杆 Y，-1~1
    private var gamepadSteering = 0f   // 右摇杆 X，-1~1
    private var gamepadActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverHost = intent.getStringExtra("host") ?: ""
        isHd = intent.getBooleanExtra("hd", true)
        video0Port = intent.getIntExtra("video0Port", 0)
        video1Port = intent.getIntExtra("video1Port", 0)
        audioPort = intent.getIntExtra("audioPort", 0)
        controlPort = intent.getIntExtra("controlPort", 0)
        videoCtrlPort = intent.getIntExtra("videoCtrlPort", 0)
        sshPort = intent.getIntExtra("sshPort", 0)
        rearCamPort = intent.getIntExtra("rearCamPort", 0)
        motorMode = intent.getIntExtra("motorMode", 0)
        lightMode = intent.getIntExtra("lightMode", 0)
        useUdp = intent.getBooleanExtra("useUdp", false)
        binding.btnQuality.text = if (isHd) getString(R.string.quality_hd) else getString(R.string.quality_smooth)

        // Video stream manager
        videoManager = VideoStreamManager(
            onVideoSize = { w, h -> runOnUiThread { updateResolution(w, h) } },
            onStats = { bitrate, total, fps -> runOnUiThread { updateVideoStats(bitrate, total, fps) } },
            onConnectionChanged = { connected -> runOnUiThread { updateVideoConnection(connected) } }
        )

        // Control client
        controlClient = ControlClient(
            onTelemetry = { signal, voltage ->
                runOnUiThread { updateTelemetry(signal, voltage) }
            },
            onGps = { lat, lng, speed ->
                MapPopupActivity.latestLat = lat
                MapPopupActivity.latestLng = lng
                MapPopupActivity.latestSpeed = speed
                MapPopupActivity.hasGpsData = true
                runOnUiThread {
                    binding.tvGpsSpeed.visibility = View.VISIBLE
                    val text = "$speed km/h"
                    val span = SpannableString(text)
                    val unitStart = text.indexOf("km/h")
                    span.setSpan(RelativeSizeSpan(0.5f), unitStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    binding.tvGpsSpeed.text = span
                }
            },
            onLatency = { ms ->
                runOnUiThread { updateLatency(ms) }
            },
            onNoResponse = {
                runOnUiThread { updateNoResponse() }
            },
            onConnectionChanged = { connected ->
                runOnUiThread { updateControlConnection(connected) }
            }
        )

        // 曝光控制客户端
        exposureClient = ExposureClient(
            onDisconnected = {
                runOnUiThread {
                    isManualExposure = false
                    binding.btnExposure.isEnabled = true
                    binding.btnExposure.visibility = View.VISIBLE
                    binding.tvExposureValue.visibility = View.GONE
                }
            },
            onConnected = { success ->
                runOnUiThread {
                    binding.btnExposure.isEnabled = true
                    if (success) {
                        // 连接成功，显示手动模式
                        isManualExposure = true
                        binding.btnExposure.visibility = View.GONE
                        binding.tvExposureValue.visibility = View.VISIBLE
                        binding.tvExposureValue.text = exposureValues[exposureIndex]
                        exposureClient?.startSending(lifecycleScope)
                        exposureClient?.setExposure(exposureValues[exposureIndex])
                    } else {
                        // 连接失败，恢复按钮状态
                        android.widget.Toast.makeText(this, "曝光控制连接失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        // Joysticks
        binding.joystickLeft.orientation = JoystickOrientation.VERTICAL
        binding.joystickLeft.onPositionChanged = { _, y ->
            throttle = (512 + y * 512).toInt().coerceIn(0, 1024)
        }
        binding.joystickRight.orientation = JoystickOrientation.HORIZONTAL
        binding.joystickRight.onPositionChanged = { x, _ ->
            steering = (512 + x * 512).toInt().coerceIn(0, 1024)
        }

        // Buttons
        binding.btnQuality.setOnClickListener {
            isHd = !isHd
            binding.btnQuality.text = if (isHd) getString(R.string.quality_hd) else getString(R.string.quality_smooth)
            stopRecording()
            videoManager.switchQuality(isHd)
        }
        binding.btnConnect.setOnClickListener {
            if (isConnected) disconnectAll() else connectAll()
        }
        binding.btnLight.setOnClickListener {
            lightLevel = (lightLevel + 1) % 4
            binding.btnLight.text = when (lightLevel) {
                1 -> getString(R.string.light_low)
                2 -> getString(R.string.light_mid)
                3 -> getString(R.string.light_high)
                else -> getString(R.string.light_off)
            }
        }

        binding.btnRecord.setOnClickListener {
            if (videoManager.isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        binding.btnAudio.setOnClickListener {
            val stream = audioStream
            if (stream != null && stream.isRunning) {
                stream.disconnect()
                audioStream = null
                binding.btnAudio.setImageResource(R.drawable.ic_speaker_off)
                binding.btnAudio.imageTintList = null
                binding.btnAudio.alpha = 1.0f
                binding.btnAudio.contentDescription = getString(R.string.cd_audio_off)
                resetMicUI()
            } else {
                val s = AudioStream()
                s.sendVolume = speakerVolume
                audioStream = s
                s.onDisconnected = {
                    runOnUiThread {
                        audioStream = null
                        binding.btnAudio.setImageResource(R.drawable.ic_speaker_off)
                        binding.btnAudio.imageTintList = null
                        binding.btnAudio.alpha = 1.0f
                        binding.btnAudio.contentDescription = getString(R.string.cd_audio_off)
                        resetMicUI()
                    }
                }
                s.connect(serverHost, audioPort)
                binding.btnAudio.setImageResource(R.drawable.ic_speaker_on)
                binding.btnAudio.imageTintList = ColorStateList.valueOf(0xFF4CAF50.toInt())
                binding.btnAudio.alpha = 1.0f
                binding.btnAudio.contentDescription = getString(R.string.cd_audio_on)
            }
        }

        binding.btnMap.setOnClickListener {
            startActivity(Intent(this, MapPopupActivity::class.java))
        }

        // 曝光控制按钮
        binding.btnExposure.setOnClickListener {
            toggleExposureMode()
        }

        // 曝光值点击切换回自动模式
        binding.tvExposureValue.setOnClickListener {
            toggleExposureMode()
        }

        // 后视摄像头按钮
        binding.btnRearCam.setOnClickListener { toggleRearCam() }

        binding.btnMic.setOnClickListener {
            val stream = audioStream
            if (stream == null || !stream.isRunning) return@setOnClickListener
            if (!hasMicPermission) {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
                    return@setOnClickListener
                }
                hasMicPermission = true
            }
            if (stream.isMicActive) {
                stream.disableMic()
                resetMicUI()
            } else {
                stream.enableMic()
                binding.btnMic.imageTintList = ColorStateList.valueOf(0xFF4CAF50.toInt())
                binding.btnMic.alpha = 1.0f
                binding.btnMic.contentDescription = getString(R.string.cd_mic_on)
            }
        }

        // Surface callback
        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                applySurface16by9()
                connectAll()
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
                applySurface16by9()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                videoManager.stop()
                controlClient.stop()
                audioStream?.disconnect()
                audioStream = null
                stopRearCam()
                resetMicUI()
                stopControlLoop()
                isConnected = false
            }
        })

        // Initial status
        if (rearCamPort == 0) binding.btnRearCam.isEnabled = false
        binding.tvStatus.text = "连接: --"
        binding.tvLatency.text = "延迟: --"
        binding.tvResolution.text = "分辨率: --"
        binding.tvFps.text = "帧率: --"
        binding.tvBitrate.text = "码率: --"
        binding.tvTraffic.text = "流量: --"
        binding.tvSignal.text = "信号: --"
        binding.tvVoltage.text = "电压: --"
        binding.tvSsh.text = "SSH: $sshPort"

        // 转向微调
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        steeringTrim = prefs.getInt("steeringTrim", 0)
        updateTrimDisplay()
        binding.btnTrimLeft.setOnClickListener {
            steeringTrim = (steeringTrim - 5).coerceAtLeast(-100)
            prefs.edit().putInt("steeringTrim", steeringTrim).apply()
            updateTrimDisplay()
        }
        binding.btnTrimRight.setOnClickListener {
            steeringTrim = (steeringTrim + 5).coerceAtMost(100)
            prefs.edit().putInt("steeringTrim", steeringTrim).apply()
            updateTrimDisplay()
        }

        // 扬声器音量 1~10（默认 10 最大），持久化
        speakerVolume = prefs.getInt("speakerVolume", 10).coerceIn(1, 10)
        binding.tvVolumeValue.text = speakerVolume.toString()
        binding.seekVolume.progress = speakerVolume - 1  // SeekBar 0~9 映射 1~10
        binding.btnVolume.setOnClickListener {
            binding.volumePanel.visibility =
                if (binding.volumePanel.visibility == View.VISIBLE) View.GONE
                else { scheduleVolumeHide(); View.VISIBLE }
        }
        binding.seekVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                speakerVolume = (progress + 1).coerceIn(1, 10)
                binding.tvVolumeValue.text = speakerVolume.toString()
                audioStream?.sendVolume = speakerVolume
                scheduleVolumeHide()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) { volumeHideTask?.cancel() }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                prefs.edit().putInt("speakerVolume", speakerVolume).apply()
                scheduleVolumeHide()
            }
        })
    }

    /** 3 秒无操作自动收起音量面板 */
    private fun scheduleVolumeHide() {
        volumeHideTask?.cancel()
        volumeHideTask = object : java.util.TimerTask() {
            override fun run() {
                runOnUiThread { binding.volumePanel.visibility = View.GONE }
            }
        }
        recordTimer.schedule(volumeHideTask, 3000)
    }

    private fun updateTrimDisplay() {
        binding.tvTrimValue.text = if (steeringTrim > 0) "+$steeringTrim" else "$steeringTrim"
    }

    private fun connectAll() {
        val holder = binding.surfaceView.holder
        videoManager.connect(serverHost, isHd, holder.surface, video0Port, video1Port)
        controlClient.connect(serverHost, controlPort, motorMode, lightMode, useUdp)
        isConnected = true
        updateConnectButton()
        startControlLoop()
    }

    private fun disconnectAll() {
        stopRecording()
        stopRearCam()
        audioStream?.disconnect()
        audioStream = null
        resetMicUI()
        binding.btnAudio.setImageResource(R.drawable.ic_speaker_off)
        binding.btnAudio.imageTintList = null
        binding.btnAudio.alpha = 1.0f
        binding.btnAudio.contentDescription = getString(R.string.cd_audio_off)
        videoManager.disconnect()
        controlClient.disconnect()
        stopControlLoop()
        isConnected = false
        updateConnectButton()
    }

    private fun startControlLoop() {
        controlJob?.cancel()
        controlJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val adjustedSteering = (steering + steeringTrim).coerceIn(0, 1024)
                controlClient.sendControl(throttle, adjustedSteering, lightLevel)
                delay(20)
            }
        }
    }

    private fun stopControlLoop() {
        controlJob?.cancel()
        controlJob = null
    }

    /**
     * 将 SurfaceView 调整为 16:9 center-crop，保证视频不变形
     */
    private fun applySurface16by9() {
        val parent = binding.surfaceView.parent as View
        val pw = parent.width
        val ph = parent.height
        if (pw == 0 || ph == 0) return
        val scale = maxOf(pw.toFloat() / 1920, ph.toFloat() / 1080)
        val vw = (1920 * scale).toInt()
        val vh = (1080 * scale).toInt()
        binding.surfaceView.layoutParams = FrameLayout.LayoutParams(vw, vh).apply {
            gravity = android.view.Gravity.CENTER
        }
    }

    private fun updateResolution(w: Int, h: Int) {
        binding.tvResolution.text = "分辨率: ${w}x${h}"
    }

    private fun startRecording() {
        // 检查存储权限
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (!android.os.Environment.isExternalStorageManager()) {
                android.widget.Toast.makeText(this, "请授予文件管理权限", android.widget.Toast.LENGTH_SHORT).show()
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return
            }
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
                return
            }
        }
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM), "RemoteControlCar")
        if (!dir.exists()) dir.mkdirs()
        val error = videoManager.startRecording(dir)
        if (error == null) {
            recordStartTime = System.currentTimeMillis()
            binding.btnRecord.text = getString(R.string.btn_record_stop)
            binding.tvRecordTime.visibility = View.VISIBLE
            binding.tvRecordTime.text = "00:00"
            recordTimerTask?.cancel()
            recordTimerTask = object : java.util.TimerTask() {
                override fun run() {
                    val elapsed = (System.currentTimeMillis() - recordStartTime) / 1000
                    val min = (elapsed / 60).toInt()
                    val sec = (elapsed % 60).toInt()
                    runOnUiThread { binding.tvRecordTime.text = String.format("%02d:%02d", min, sec) }
                }
            }
            recordTimer.scheduleAtFixedRate(recordTimerTask, 1000, 1000)
        } else {
            android.widget.Toast.makeText(this, "录像失败: ${error.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        if (!videoManager.isRecording) return
        recordTimerTask?.cancel()
        recordTimerTask = null
        videoManager.stopRecording()
        binding.btnRecord.text = getString(R.string.btn_record_start)
        binding.tvRecordTime.visibility = View.GONE
    }

    private fun updateConnectButton() {
        binding.btnConnect.text = if (isConnected) getString(R.string.btn_disconnect) else getString(R.string.btn_connect)
    }

    private fun updateVideoStats(bitrateKbps: Int, totalBytes: Long, fps: Int) {
        binding.tvFps.text = "帧率: $fps fps"
        // 后视摄像头开启时，合并后视流量统计
        val rear = rearCamStream
        val rearBytesSec = if (isRearCamOn && rear != null) rear.bytesInSecond else 0L
        val rearTotalBytes = if (isRearCamOn && rear != null) rear.totalBytes else 0L
        val combinedBitrateKbps = bitrateKbps + (rearBytesSec * 8 / 1000).toInt()
        val combinedTotal = totalBytes + rearTotalBytes
        binding.tvBitrate.text = if (combinedBitrateKbps >= 1000) {
            "码率: ${"%.1f".format(combinedBitrateKbps / 1000.0)} Mbps"
        } else {
            "码率: $combinedBitrateKbps kbps"
        }
        binding.tvTraffic.text = "流量: ${formatBytes(combinedTotal)}"
        // 读取后重置后视每秒字节数
        if (isRearCamOn && rear != null) rear.bytesInSecond = 0
    }

    private fun updateVideoConnection(connected: Boolean) {
        // 状态面板显示控制通道状态，视频连接状态不在此显示
    }

    private fun updateControlConnection(connected: Boolean) {
        controlConnected = connected
        noResponse = false
        binding.tvStatus.text = when {
            connected -> "连接: 已连接"
            isConnected -> "连接: 重连中..."
            else -> "连接: 未连接"
        }
    }

    private fun updateTelemetry(signal: Int, voltageMv: Int) {
        binding.tvSignal.text = "信号: $signal dBm"
        binding.tvVoltage.text = "电压: ${"%.2f".format(voltageMv / 1000.0)}V"
    }

    private fun updateLatency(ms: Int) {
        latencyMs = ms
        binding.tvLatency.text = "延迟: ${ms}ms"
        // 收到延迟说明有响应，重置无响应状态
        if (noResponse) {
            noResponse = false
            binding.tvStatus.text = "连接: 已连接"
        }
    }

    private fun updateNoResponse() {
        noResponse = true
        binding.tvStatus.text = "连接: 无响应"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        stopRearCam()
        recordTimer.cancel()
        videoManager.stop()
        controlClient.stop()
        audioStream?.disconnect()
        exposureClient?.disconnect()
        controlJob?.cancel()
    }

    private fun resetMicUI() {
        binding.btnMic.setImageResource(R.drawable.ic_mic_off)
        binding.btnMic.imageTintList = null
        binding.btnMic.alpha = 1.0f
        binding.btnMic.contentDescription = getString(R.string.cd_mic_off)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            hasMicPermission = true
        }
    }

    /**
     * 切换曝光模式（自动/手动）
     */
    private fun toggleExposureMode() {
        if (isManualExposure) {
            // 切换为自动
            isManualExposure = false
            exposureClient?.disconnect()
            exposureClient = null
            exposureClient = ExposureClient(
                onDisconnected = {
                    runOnUiThread {
                        isManualExposure = false
                        binding.btnExposure.isEnabled = true
                        binding.btnExposure.visibility = View.VISIBLE
                        binding.tvExposureValue.visibility = View.GONE
                    }
                },
                onConnected = { success ->
                    runOnUiThread {
                        binding.btnExposure.isEnabled = true
                        if (success) {
                            isManualExposure = true
                            binding.btnExposure.visibility = View.GONE
                            binding.tvExposureValue.visibility = View.VISIBLE
                            binding.tvExposureValue.text = exposureValues[exposureIndex]
                            exposureClient?.startSending(lifecycleScope)
                            exposureClient?.setExposure(exposureValues[exposureIndex])
                        } else {
                            android.widget.Toast.makeText(this, "曝光控制连接失败", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            binding.btnExposure.visibility = View.VISIBLE
            binding.tvExposureValue.visibility = View.GONE
        } else {
            // 切换为手动，先禁用按钮
            binding.btnExposure.isEnabled = false
            exposureIndex = 3 // 重置为 1/200
            
            // 在 IO 线程中连接
            lifecycleScope.launch(Dispatchers.IO) {
                exposureClient?.connect(serverHost, videoCtrlPort)
            }
        }
    }

    /**
     * 切换后视摄像头开关
     */
    private fun toggleRearCam() {
        if (rearCamPort == 0) {
            android.widget.Toast.makeText(this, "后视摄像头不可用", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (isRearCamOn) {
            stopRearCam()
        } else {
            binding.rearCamContainer.visibility = View.VISIBLE
            binding.surfaceViewRearCam.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val stream = RearCamStream(
                        onConnectionChanged = { /* 可扩展：更新后视连接状态 */ }
                    )
                    rearCamStream = stream
                    stream.connect(serverHost, rearCamPort, holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    rearCamStream?.stop()
                    rearCamStream = null
                }
            })
            // 如果 surface 已创建，手动触发
            if (binding.surfaceViewRearCam.holder.surface.isValid) {
                val stream = RearCamStream(
                    onConnectionChanged = { /* 可扩展 */ }
                )
                rearCamStream = stream
                stream.connect(serverHost, rearCamPort, binding.surfaceViewRearCam.holder.surface)
            }
            binding.btnRearCam.setTextColor(0xFF4CAF50.toInt())
            isRearCamOn = true
        }
    }

    private fun stopRearCam() {
        if (!isRearCamOn) return
        rearCamStream?.stop()
        rearCamStream = null
        binding.rearCamContainer.visibility = View.GONE
        binding.btnRearCam.setTextColor(getColor(R.color.status_text))
        isRearCamOn = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 只在手动曝光模式下处理滑动
        if (!isManualExposure) return super.onTouchEvent(event)

        val x = event.x
        val y = event.y
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()

        // 排除摇杆和按钮区域
        val leftJoystickArea = x < 200 && y > height - 200
        val rightJoystickArea = x > width - 200 && y > height - 200
        val rightButtonArea = x > width - 250 && y < 200
        val bottomButtonArea = y > height - 100 && x > width * 0.3 && x < width * 0.7

        if (leftJoystickArea || rightJoystickArea || rightButtonArea || bottomButtonArea) {
            return super.onTouchEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = lastTouchY - y
                if (Math.abs(deltaY) > 30) { // 灵敏度阈值
                    if (deltaY > 0) {
                        // 向上滑动，减小分母（增加曝光）
                        exposureIndex = (exposureIndex - 1).coerceIn(0, exposureValues.size - 1)
                    } else {
                        // 向下滑动，增大分母（减少曝光）
                        exposureIndex = (exposureIndex + 1).coerceIn(0, exposureValues.size - 1)
                    }
                    lastTouchY = y
                    exposureClient?.setExposure(exposureValues[exposureIndex])
                    binding.tvExposureValue.text = exposureValues[exposureIndex]
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val src = ev.source
        // 处理手柄/游戏手柄事件
        if ((src and InputDevice.SOURCE_JOYSTICK != 0 || src and InputDevice.SOURCE_GAMEPAD != 0)
            && ev.actionMasked == MotionEvent.ACTION_MOVE) {
            val ly = ev.getAxisValue(MotionEvent.AXIS_Y)
            val rx = ev.getAxisValue(MotionEvent.AXIS_Z)

            val deadZone = 0.1f
            // AXIS_Y 推上为负值，需取负以匹配虚拟摇杆方向（推上=前进）
            val throttleInput = if (Math.abs(ly) < deadZone) 0f else -ly
            val steeringInput = if (Math.abs(rx) < deadZone) 0f else rx

            val wasActive = gamepadActive
            gamepadActive = throttleInput != 0f || steeringInput != 0f

            if (gamepadActive) {
                gamepadThrottle = throttleInput
                gamepadSteering = steeringInput
                throttle = (512 + gamepadThrottle * 512).toInt().coerceIn(0, 1024)
                steering = (512 + gamepadSteering * 512).toInt().coerceIn(0, 1024)
                binding.joystickLeft.setPosition(0f, gamepadThrottle)
                binding.joystickRight.setPosition(gamepadSteering, 0f)
            } else if (wasActive && !gamepadActive) {
                throttle = 512
                steering = 512
                binding.joystickLeft.setPosition(0f, 0f)
                binding.joystickRight.setPosition(0f, 0f)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}
