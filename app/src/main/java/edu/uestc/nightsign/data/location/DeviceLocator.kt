package edu.uestc.nightsign.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import edu.uestc.nightsign.domain.Gcj02
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume

/** 读取一次当前位置，并转成签到使用的 GCJ-02。 */
class DeviceLocator(private val context: Context) {
    suspend fun currentGcj02(): Pair<Double, Double> {
        val location = try {
            withTimeout(15_000) { request() }
        } catch (timeout: TimeoutCancellationException) {
            throw IOException("定位超时，请打开系统定位后重试")
        }
        return Gcj02.fromWgs84(location.latitude, location.longitude)
    }

    @SuppressLint("MissingPermission")
    private suspend fun request(): Location {
        val fine = granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) throw IOException("需要定位权限")
        val manager = context.getSystemService(LocationManager::class.java)
            ?: throw IOException("当前设备不支持定位")
        val providers = buildList {
            if (fine && enabled(manager, LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (enabled(manager, LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) throw IOException("请先打开系统定位")
        providers.firstNotNullOfOrNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                ?.takeIf { System.currentTimeMillis() - it.time < 120_000 }
        }?.let { return it }
        for (provider in providers) {
            val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                current(manager, provider)
            } else {
                single(manager, provider)
            }
            if (location != null) return location
        }
        throw IOException("未能获取当前位置")
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, { command -> command.run() }) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun single(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun enabled(manager: LocationManager, provider: String) =
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
}
