package edu.uestc.nightsign

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Keeps the short, user-initiated QR exchange foreground while the user opens WeChat. */
class WechatAuthorizationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("wechat_authorization", "微信扫码授权", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(2, NotificationCompat.Builder(this, "wechat_authorization")
            .setSmallIcon(R.drawable.ic_moon).setContentTitle("正在进行微信授权")
            .setContentText("在微信确认后返回 APP").setContentIntent(open).setOngoing(true).build())
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, 360_000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WechatAuthorizationService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, WechatAuthorizationService::class.java))
        }
    }
}
