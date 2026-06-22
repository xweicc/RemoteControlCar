package com.example.remotecontrolcar

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.remotecontrolcar.databinding.ActivityMainBinding
import com.example.remotecontrolcar.network.PortQuerier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var querying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        binding.etServerAddress.setText(prefs.getString("server", ""))
        binding.etSn.setText(prefs.getString("sn", ""))
        if (prefs.getBoolean("hd", true)) {
            binding.rgQuality.check(R.id.rbHd)
        } else {
            binding.rgQuality.check(R.id.rbSmooth)
        }
        if (prefs.getInt("motorMode", 0) == 0) {
            binding.rgMotorMode.check(R.id.rbMotorBuiltin)
        } else {
            binding.rgMotorMode.check(R.id.rbMotorExternal)
        }
        if (prefs.getInt("lightMode", 0) == 0) {
            binding.rgLightMode.check(R.id.rbLightBuiltin)
        } else {
            binding.rgLightMode.check(R.id.rbLightExternal)
        }

        binding.btnEnterControl.setOnClickListener {
            if (querying) return@setOnClickListener

            val addressInput = binding.etServerAddress.text.toString().trim()
            if (addressInput.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 解析 host:port
            val colonIdx = addressInput.lastIndexOf(':')
            if (colonIdx <= 0 || colonIdx == addressInput.length - 1) {
                Toast.makeText(this, "地址格式错误，请使用 host:port 格式", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val host = addressInput.substring(0, colonIdx)
            val queryPort = addressInput.substring(colonIdx + 1).toIntOrNull()
            if (queryPort == null || queryPort !in 1..65535) {
                Toast.makeText(this, "端口号无效", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val sn = binding.etSn.text.toString().trim()
            if (sn.isEmpty()) {
                Toast.makeText(this, "请输入设备序列号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val hd = binding.rbHd.isChecked
            val motorMode = if (binding.rbMotorExternal.isChecked) 1 else 0
            val lightMode = if (binding.rbLightExternal.isChecked) 1 else 0
            prefs.edit()
                .putString("server", addressInput)
                .putString("sn", sn)
                .putBoolean("hd", hd)
                .putInt("motorMode", motorMode)
                .putInt("lightMode", lightMode)
                .apply()

            // 在首页查询端口
            querying = true
            binding.btnEnterControl.isEnabled = false
            binding.btnEnterControl.text = "查询中..."

            lifecycleScope.launch {
                val ports = withContext(Dispatchers.IO) {
                    PortQuerier.query(host, queryPort, sn)
                }
                querying = false
                binding.btnEnterControl.isEnabled = true
                binding.btnEnterControl.text = getString(R.string.btn_enter)

                if (ports == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "设备不在线或查询失败，请检查服务器地址和序列号",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                startActivity(
                    Intent(this@MainActivity, ControlActivity::class.java)
                        .putExtra("host", host)
                        .putExtra("hd", hd)
                        .putExtra("video0Port", ports.video0)
                        .putExtra("video1Port", ports.video1)
                        .putExtra("audioPort", ports.audio)
                        .putExtra("controlPort", ports.control)
                        .putExtra("videoCtrlPort", ports.videoCtrl)
                        .putExtra("sshPort", ports.ssh)
                        .putExtra("rearCamPort", ports.rearCam)
                        .putExtra("motorMode", motorMode)
                        .putExtra("lightMode", lightMode)
                )
            }
        }
    }
}
