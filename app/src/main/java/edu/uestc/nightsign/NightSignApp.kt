package edu.uestc.nightsign

import android.app.Application
import edu.uestc.nightsign.data.local.PrivateStore
import edu.uestc.nightsign.data.repository.NightSignRepository
import edu.uestc.nightsign.worker.CheckinWorker

class NightSignApp : Application() {
    val store by lazy { PrivateStore(this) }
    val wechat by lazy { edu.uestc.nightsign.data.wechat.WechatQrClient() }
    val repository by lazy { NightSignRepository(store) }
    override fun onCreate() {
        super.onCreate()
        CheckinWorker.schedule(this, store.settings().autoEnabled)
    }
}
