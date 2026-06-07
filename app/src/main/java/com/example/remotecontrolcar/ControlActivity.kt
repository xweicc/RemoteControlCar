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
import com.example.remotecontrolcar.network.VideoStreamManager
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
    private var audioStream: AudioStream? = null
    private var hasMicPermission = false
    private var lightLevel = 0
    private var throttle = 512
    private var steering = 512
    private var controlJob: Job? = null
    private var recordStartTime = 0L
    private val recordTimer = java.util.Timer()
    private var recordTimerTask: java.util.TimerTask? = null

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
            },
            onConnectionChanged = { connected ->
                runOnUiThread { updateControlConnection(connected) }
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
                binding.btnAudio.alpha = 0.6f
                binding.btnAudio.contentDescription = getString(R.string.cd_audio_off)
                resetMicUI()
            } else {
                val s = AudioStream()
                audioStream = s
                s.onDisconnected = {
                    runOnUiThread {
                        audioStream = null
                        binding.btnAudio.setImageResource(R.drawable.ic_speaker_off)
                        binding.btnAudio.imageTintList = null
                        binding.btnAudio.alpha = 0.6f
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

        binding.btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val stream = audioStream
                    if (stream == null || !stream.isRunning) return@setOnTouchListener true
                    if (!hasMicPermission) {
                        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(
                                arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
                            return@setOnTouchListener true
                        }
                        hasMicPermission = true
                    }
                    stream.enableMic()
                    binding.btnMic.imageTintList = ColorStateList.valueOf(0xFFF44336.toInt())
                    binding.btnMic.alpha = 1.0f
                    binding.btnMic.contentDescription = getString(R.string.cd_mic_on)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    audioStream?.disableMic()
                    resetMicUI()
                    true
                }
                else -> false
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
                resetMicUI()
                stopControlLoop()
                isConnected = false
            }
        })

        // Initial status
        binding.tvStatus.text = "连接: --"
        binding.tvResolution.text = "分辨率: --"
        binding.tvFps.text = "帧率: --"
        binding.tvBitrate.text = "码率: --"
        binding.tvTraffic.text = "流量: --"
        binding.tvSignal.text = "信号: --"
        binding.tvVoltage.text = "电压: --"
    }

    private fun connectAll() {
        val holder = binding.surfaceView.holder
        videoManager.connect(serverHost, isHd, holder.surface, video0Port, video1Port)
        controlClient.connect(serverHost, controlPort)
        isConnected = true
        updateConnectButton()
        startControlLoop()
    }

    private fun disconnectAll() {
        stopRecording()
        audioStream?.disconnect()
        audioStream = null
        resetMicUI()
        binding.btnAudio.setImageResource(R.drawable.ic_speaker_off)
        binding.btnAudio.imageTintList = null
        binding.btnAudio.alpha = 0.6f
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
                controlClient.sendControl(throttle, steering, lightLevel)
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
        binding.tvBitrate.text = if (bitrateKbps >= 1000) {
            "码率: ${"%.1f".format(bitrateKbps / 1000.0)} Mbps"
        } else {
            "码率: $bitrateKbps kbps"
        }
        binding.tvTraffic.text = "流量: ${formatBytes(totalBytes)}"
    }

    private fun updateVideoConnection(connected: Boolean) {
        binding.tvStatus.text = if (connected) "连接: 已连接" else "连接: 未连接"
    }

    private fun updateControlConnection(connected: Boolean) {
        // Control connection status reflected in main status
    }

    private fun updateTelemetry(signal: Int, voltageMv: Int) {
        binding.tvSignal.text = "信号: $signal dBm"
        binding.tvVoltage.text = "电压: ${"%.2f".format(voltageMv / 1000.0)}V"
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
        recordTimer.cancel()
        videoManager.stop()
        controlClient.stop()
        audioStream?.disconnect()
        controlJob?.cancel()
    }

    private fun resetMicUI() {
        binding.btnMic.setImageResource(R.drawable.ic_mic_off)
        binding.btnMic.imageTintList = null
        binding.btnMic.alpha = 0.6f
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
