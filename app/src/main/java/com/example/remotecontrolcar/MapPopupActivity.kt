package com.example.remotecontrolcar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.CoordinateConverter

class MapPopupActivity : AppCompatActivity() {

    companion object {
        @Volatile var latestLat: Double = 0.0
        @Volatile var latestLng: Double = 0.0
        @Volatile var latestSpeed: Int = 0
        @Volatile var hasGpsData: Boolean = false

        private const val PREF_NAME = "rc_car_prefs"
        private const val KEY_AMAP_API_KEY = "amap_api_key"
        private const val LOCATION_PERMISSION_REQUEST = 1001

        fun getApiKey(context: Context): String {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_AMAP_API_KEY, "") ?: ""
        }

        fun setApiKey(context: Context, key: String) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_AMAP_API_KEY, key).apply()
        }
    }

    private var mapView: MapView? = null
    private var aMap: AMap? = null
    private var carMarker: Marker? = null
    private var firstLocation = true
    private val handler = Handler(Looper.getMainLooper())
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateMapPosition()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apiKey = getApiKey(this)
        if (apiKey.isBlank()) {
            showApiKeyDialog {
                initMapAndShow()
            }
        } else {
            initMapAndShow()
        }
    }

    private fun applyApiKey(key: String) {
        try {
            val ai = packageManager.getApplicationInfo(packageName, 0x00000080 /* GET_META_DATA */)
            if (ai.metaData == null) ai.metaData = Bundle()
            ai.metaData.putString("com.amap.api.v2.apikey", key)
        } catch (_: Exception) {}
    }

    private fun showApiKeyDialog(onSaved: () -> Unit) {
        val editText = EditText(this).apply {
            hint = "请输入高德地图 API Key"
            setPadding(48, 32, 48, 32)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle("配置高德地图 API Key")
            .setMessage("请输入在高德开放平台申请的 Android SDK API Key")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val key = editText.text.toString().trim()
                if (key.isNotBlank()) {
                    setApiKey(this, key)
                    applyApiKey(key)
                    onSaved()
                } else {
                    finish()
                }
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun initMapAndShow() {
        val apiKey = getApiKey(this)
        // Set API key before inflating layout (MapView reads key during construction)
        applyApiKey(apiKey)

        // Privacy compliance required by AMap SDK
        try {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
        } catch (_: Exception) {}

        setContentView(R.layout.activity_map_popup)

        // Resize popup: 50% width, 70% height
        val card = findViewById<View>(R.id.cardRoot)
        val dm = resources.displayMetrics
        val lp = card.layoutParams as ViewGroup.MarginLayoutParams
        lp.width = (dm.widthPixels * 0.5f).toInt()
        lp.height = (dm.heightPixels * 0.7f).toInt()
        card.layoutParams = lp

        // Close button
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        // Click outside card to close
        findViewById<View>(R.id.outsideArea).setOnClickListener { finish() }
        // Prevent card clicks from propagating to outside
        (findViewById<View>(R.id.mapView).parent as? View)?.setOnClickListener { /* consume */ }

        // MapView from XML
        mapView = findViewById<MapView>(R.id.mapView).also { mv ->
            mv.onCreate(null)
            aMap = mv.map
        }

        enableMyLocation()
        handler.postDelayed(updateRunnable, 500)
    }

    private fun enableMyLocation() {
        val map = aMap ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupMyLocationLayer(map)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    private fun setupMyLocationLayer(map: AMap) {
        map.isMyLocationEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true
        val style = MyLocationStyle().apply {
            myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
            showMyLocation(true)
        }
        map.myLocationStyle = style
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            aMap?.let { setupMyLocationLayer(it) }
        }
    }

    private fun updateMapPosition() {
        val map = aMap ?: return
        val lat = latestLat
        val lng = latestLng
        if (lat == 0.0 && lng == 0.0) {
            // No GPS data yet
            val tvGpsInfo = findViewById<TextView>(R.id.tvGpsInfo)
            tvGpsInfo?.text = "等待GPS数据..."
            return
        }

        // Convert WGS-84 (GPS) to GCJ-02 (AMap)
        val wgs84LatLng = LatLng(lat, lng)
        val converter = CoordinateConverter(this)
            .from(CoordinateConverter.CoordType.GPS)
            .coord(wgs84LatLng)
        val gcj02LatLng = converter.convert()

        if (lat != lastLat || lng != lastLng) {
            lastLat = lat
            lastLng = lng

            if (carMarker == null) {
                carMarker = map.addMarker(
                    MarkerOptions()
                        .position(gcj02LatLng)
                        .title("遥控车")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        .anchor(0.5f, 1.0f)
                )
            } else {
                carMarker?.position = gcj02LatLng
            }

            if (firstLocation) {
                firstLocation = false
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(gcj02LatLng, 16f))
            }
        }

        // Update info text
        val tvGpsInfo = findViewById<TextView>(R.id.tvGpsInfo)
        tvGpsInfo?.text = "%.4f, %.4f".format(lat, lng)

        // Update speed
        val tvSpeed = findViewById<TextView>(R.id.tvSpeed)
        tvSpeed?.text = "$latestSpeed km/h"
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        mapView?.onDestroy()
        super.onDestroy()
    }
}
