package edu.uestc.nightsign.data.update

import android.content.Context
import java.util.concurrent.TimeUnit

/** 自动检查间隔，以及用户点「稍后」后不再重复提示同一版本。 */
class UpdateReminderStorage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("app_update_reminder", Context.MODE_PRIVATE)

    fun shouldCheckNow(): Boolean =
        System.currentTimeMillis() - prefs.getLong("last_check_ms", 0L) >= TimeUnit.HOURS.toMillis(12)

    fun markCheckedNow() {
        prefs.edit().putLong("last_check_ms", System.currentTimeMillis()).apply()
    }

    fun isDismissed(releaseTag: String): Boolean = prefs.getString("dismissed_release_tag", null) == releaseTag

    fun dismiss(releaseTag: String) {
        prefs.edit().putString("dismissed_release_tag", releaseTag).apply()
    }
}
