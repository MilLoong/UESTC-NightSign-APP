package edu.uestc.nightsign.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import edu.uestc.nightsign.MainActivity
import edu.uestc.nightsign.NightSignApp
import edu.uestc.nightsign.R
import edu.uestc.nightsign.data.network.SessionExpiredException
import edu.uestc.nightsign.domain.CheckinPolicy
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** 本机持久化调度；系统省电模式下不保证整点运行。 */
class CheckinWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NightSignApp
        val settings = app.store.settings()
        val now = CheckinPolicy.now()
        // 在目标时间前和次日不登录、不发送业务请求。
        if (!settings.autoEnabled || now.hour * 60 + now.minute < settings.targetMinute ||
            now.hour * 60 + now.minute > app.store.windowEnd ||
            app.store.lastSuccess == CheckinPolicy.today()) return Result.success()
        return try {
            app.repository.checkin(background = true)?.let {
                notify("签到成功", "${it.businessDate} 已完成签到并复查确认")
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SessionExpiredException) {
            if (!app.store.secret("login_notice_date").equals(CheckinPolicy.today())) {
                notify("需要重新登录", "打开晚安成电完成登录，后台不会代填短信验证码")
                app.store.record("后台暂停：需要重新登录")
                app.store.putSecret("login_notice_date", CheckinPolicy.today())
            }
            app.store.needsLogin = true
            Result.success()
        } catch (error: Exception) {
            app.store.record("后台检查失败：${error.message ?: "网络或平台错误"}")
            Result.success()
        }
    }

    private fun notify(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("checkin", "签到结果", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, NotificationCompat.Builder(applicationContext, "checkin").setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(title).setContentText(message).setContentIntent(intent).setAutoCancel(true).build())
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) { manager.cancelUniqueWork("night-sign"); return }
            val request = PeriodicWorkRequestBuilder<CheckinWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            manager.enqueueUniquePeriodicWork("night-sign", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
