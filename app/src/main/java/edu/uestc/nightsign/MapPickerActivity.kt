package edu.uestc.nightsign

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import edu.uestc.nightsign.data.location.DeviceLocator
import edu.uestc.nightsign.data.location.MapAddressResolver
import edu.uestc.nightsign.ui.theme.AppTheme
import edu.uestc.nightsign.ui.theme.UestcHelperTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 打开地图选点。确认后同时带回 GCJ-02 坐标和地址。 */
class MapPickerActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("拖动地图，把签到位置对准中心圆点")
    private var startLat = DEFAULT_LAT
    private var startLng = DEFAULT_LNG
    private var themeKey = AppTheme.BLUE.key
    private val tencentKey = BuildConfig.TENCENT_MAP_KEY
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) moveToCurrentLocation()
        else message = "没有定位权限，可以手动拖动地图"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA_LAT)?.toDoubleOrNull()?.let { startLat = it }
        intent.getStringExtra(EXTRA_LNG)?.toDoubleOrNull()?.let { startLng = it }
        intent.getStringExtra(EXTRA_THEME)?.takeIf { it.isNotBlank() }?.let { themeKey = it }
        setContent {
            UestcHelperTheme(themeKey = themeKey) {
                val pageColor = MaterialTheme.colorScheme.background
                val pinColor = MaterialTheme.colorScheme.primary
                val onPin = MaterialTheme.colorScheme.onPrimary
                val actionColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                Scaffold(
                    containerColor = pageColor,
                    topBar = {
                        TopAppBar(
                            title = { Text("选择位置") },
                            navigationIcon = {
                                TextButton(onClick = { finish() }, enabled = !busy, colors = actionColors) { Text("返回") }
                            },
                            actions = {
                                TextButton(onClick = ::requestOrLocate, enabled = !busy, colors = actionColors) { Text("当前位置") }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = pageColor,
                                titleContentColor = MaterialTheme.colorScheme.onBackground,
                                navigationIconContentColor = pinColor,
                                actionIconContentColor = pinColor,
                            ),
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Text(
                            message,
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = pinColor)
                        Box(Modifier.weight(1f).fillMaxWidth().background(pageColor)) {
                            AndroidView(
                                factory = { createBrowser(pageColor, pinColor) },
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                Modifier.align(Alignment.Center).offset(y = (-14).dp)
                                    .size(18.dp)
                                    .background(pinColor, CircleShape)
                                    .border(2.dp, onPin, CircleShape),
                            )
                            Column(
                                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { browser?.evaluateJavascript("zoomBy(1)", null) },
                                    enabled = !busy,
                                    modifier = Modifier.size(48.dp),
                                    contentPadding = PaddingValues(0.dp),
                                ) { Text("+") }
                                Button(
                                    onClick = { browser?.evaluateJavascript("zoomBy(-1)", null) },
                                    enabled = !busy,
                                    modifier = Modifier.size(48.dp),
                                    contentPadding = PaddingValues(0.dp),
                                ) { Text("−") }
                            }
                        }
                        Button(
                            onClick = ::confirmMapCenter,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) { Text("使用此位置") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        browser?.destroy()
        browser = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowser(pageColor: Color, pinColor: Color): WebView = WebView(this).also { view ->
        browser = view
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        view.setBackgroundColor(pageColor.toArgb())
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.loadsImagesAutomatically = true
        view.settings.blockNetworkImage = false
        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (title == "tiles-failed") message = "地图没有加载出来，请检查网络后再试"
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                moveMap(startLat, startLng)
            }
        }
        val html = assets.open("map_picker.html").bufferedReader().use { it.readText() }
            .replace("__BG__", hex(pageColor))
            .replace("__PIN__", hex(pinColor))
        view.loadDataWithBaseURL("https://www.amap.com/", html, "text/html", "utf-8", null)
    }

    private fun moveMap(latitude: Double, longitude: Double) {
        val script = "setCenter(%.6f,%.6f)".format(Locale.US, latitude, longitude)
        browser?.evaluateJavascript(script, null)
    }

    private fun requestOrLocate() {
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) moveToCurrentLocation()
        else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun moveToCurrentLocation() {
        if (busy) return
        lifecycleScope.launch {
            busy = true
            message = "正在定位"
            try {
                val (latitude, longitude) = DeviceLocator(this@MapPickerActivity).currentGcj02()
                startLat = latitude
                startLng = longitude
                moveMap(latitude, longitude)
                message = "已移到当前位置，点使用此位置后会自动解析地址"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.message ?: "定位失败"
            } finally {
                busy = false
            }
        }
    }

    private fun confirmMapCenter() {
        val view = browser ?: return
        if (busy) return
        view.evaluateJavascript("currentCenter()") { raw ->
            val center = runCatching { parseCenter(raw) }.getOrNull()
            if (center == null) {
                message = "还没有拿到地图中心，请等地图加载完再试"
                return@evaluateJavascript
            }
            resolveAndFinish(center.first, center.second)
        }
    }

    private fun resolveAndFinish(latitude: Double, longitude: Double) {
        lifecycleScope.launch {
            busy = true
            message = "正在解析地址"
            try {
                val address = withContext(Dispatchers.IO) {
                    MapAddressResolver(this@MapPickerActivity, tencentKey).resolve(latitude, longitude)
                }
                finishPicked(latitude, longitude, address)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.message ?: "解析地址失败"
            } finally {
                busy = false
            }
        }
    }

    private fun finishPicked(latitude: Double, longitude: Double, address: String) {
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_LAT, "%.6f".format(Locale.US, latitude))
            .putExtra(EXTRA_LNG, "%.6f".format(Locale.US, longitude))
            .putExtra(EXTRA_ADDR, address))
        finish()
    }

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ADDR = "addr"
        private const val EXTRA_THEME = "theme"
        private const val DEFAULT_LAT = 30.753
        private const val DEFAULT_LNG = 103.929

        fun createIntent(context: Context, latitude: String, longitude: String, themeKey: String): Intent =
            Intent(context, MapPickerActivity::class.java)
                .putExtra(EXTRA_LAT, latitude)
                .putExtra(EXTRA_LNG, longitude)
                .putExtra(EXTRA_THEME, themeKey)

        internal fun parseCenter(raw: String?): Pair<Double, Double> {
            val text = JsonParser.parseString(raw).asString
            val obj = JsonParser.parseString(text).asJsonObject
            return obj.get("lat").asDouble to obj.get("lng").asDouble
        }

        private fun hex(color: Color) = "#%06X".format(0xFFFFFF and color.toArgb())
    }
}
