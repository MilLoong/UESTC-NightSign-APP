package edu.uestc.nightsign.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.uestc.nightsign.BuildConfig
import edu.uestc.nightsign.NightSignApp
import edu.uestc.nightsign.data.location.DeviceLocator
import edu.uestc.nightsign.data.location.MapAddressResolver
import edu.uestc.nightsign.data.network.SessionExpiredException
import edu.uestc.nightsign.data.network.WechatCodeSettings
import edu.uestc.nightsign.data.wechat.ServerPairingClient
import edu.uestc.nightsign.data.wechat.ServerPairingOffer
import edu.uestc.nightsign.data.update.AppUpdateChecker
import edu.uestc.nightsign.data.update.UpdateReminderStorage
import edu.uestc.nightsign.domain.*
import edu.uestc.nightsign.worker.CheckinWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class MainUiState(
    val overview: CheckinOverview? = null,
    val settings: SignSettings = SignSettings(),
    val busy: Boolean = false,
    val message: String? = null,
    val showLogin: Boolean = false,
    val smsPrompt: String? = null,
    val resendSeconds: Int = 0,
    val events: List<String> = emptyList(),
    val theme: String = "blue",
    val signedIn: Boolean = false,
    val updatePrompt: UpdatePrompt? = null,
    val wechatCode: WechatCodeSettings = WechatCodeSettings(),
    val serverAuthorization: String = "",
)

data class UpdatePrompt(
    val releaseTag: String,
    val versionLabel: String,
    val releaseNotes: String,
    val downloadUrl: String,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NightSignApp
    private val repository = app.repository
    private val mutable = MutableStateFlow(MainUiState(settings = app.store.settings(), events = app.store.events(),
        theme = app.store.secret("theme") ?: "blue",
        signedIn = repository.jar.snapshot().any { it.domain == "jzsz.uestc.edu.cn" && it.name == "SESSION" },
        wechatCode = repository.wechatCodeSettings()))
    val ui = mutable.asStateFlow()
    private val updateChecker = AppUpdateChecker()
    private val updateStorage = UpdateReminderStorage(application)
    private var operation: Job? = null
    private var smsAnswer: CompletableDeferred<String?>? = null
    private var countdown: Job? = null
    private var authorizationSync: Job? = null

    fun refreshWechatAuthorization() {
        if (authorizationSync?.isActive == true || mutable.value.busy || mutable.value.wechatCode.accessToken.isBlank()) return
        authorizationSync = viewModelScope.launch {
            try {
                val status = repository.syncWechatAuthorization()
                mutable.update { it.copy(wechatCode = repository.wechatCodeSettings(),
                    serverAuthorization = if (status.authorized) "微信已授权" else "请在服务器管理页完成微信授权") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(serverAuthorization = error.message ?: "同步授权状态失败") } }
        }
    }

    init {
        if (repository.jar.snapshot().any { it.domain == "jzsz.uestc.edu.cn" }) refresh()
        checkAppUpdate(force = false)
    }

    private fun launchOperation(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "操作失败，请检查网络") } }
            finally {
                mutable.update { it.copy(busy = false, events = app.store.events(), smsPrompt = null) }
                smsAnswer?.cancel()
                smsAnswer = null
            }
        }
    }

    fun refresh() = launchOperation { loadOverview() }

    fun pairServer(offer: ServerPairingOffer) = launchOperation {
        authorizationSync?.cancelAndJoin()
        val previous = repository.wechatCodeSettings()
        val settings = withContext(Dispatchers.IO) { ServerPairingClient().pair(offer) }
        repository.saveWechatCodeSettings(settings)
        if (previous.endpoint == settings.endpoint && previous.accessToken.isNotBlank()) {
            withContext(Dispatchers.IO) {
                runCatching { ServerPairingClient().revoke(previous) }
                    .onFailure { app.store.record("旧设备绑定未撤销，可在管理页面清理") }
            }
        }
        mutable.update { it.copy(wechatCode = settings, serverAuthorization = "请在服务器管理页完成微信授权",
            message = "服务器已连接，请在管理页完成微信授权") }
    }

    fun disconnectServer() = launchOperation {
        authorizationSync?.cancelAndJoin()
        val settings = repository.wechatCodeSettings()
        val revoked = withContext(Dispatchers.IO) { runCatching { ServerPairingClient().revoke(settings) }.isSuccess }
        repository.clearWechatCodeSettings()
        val signSettings = app.store.settings().copy(autoEnabled = false)
        app.store.saveSettings(signSettings)
        CheckinWorker.schedule(app, false)
        mutable.update { it.copy(wechatCode = WechatCodeSettings(), serverAuthorization = "", settings = signSettings,
            message = if (revoked) "已断开服务器并关闭后台签到" else "已清除本机绑定，请在服务器管理页面撤销设备") }
    }

    fun testWechatAuthorization() = launchOperation {
        repository.testWechatAuthorization()
        mutable.update { it.copy(wechatCode = repository.wechatCodeSettings(), serverAuthorization = "微信已授权",
            message = "微信取码及学校授权成功，未提交签到") }
    }

    fun showLogin() { mutable.update { it.copy(showLogin = true, message = null) } }
    fun closeLogin() {
        operation?.cancel()
        countdown?.cancel()
        smsAnswer?.cancel()
        mutable.update { it.copy(showLogin = false, smsPrompt = null, busy = false, resendSeconds = 0) }
    }

    fun login(username: String, password: String, remember: Boolean) = launchOperation {
        repository.login(username, password, remember) { prompt ->
            val answer = CompletableDeferred<String?>()
            smsAnswer = answer
            mutable.update { it.copy(smsPrompt = prompt, busy = false) }
            startCountdown(60)
            answer.await().also { mutable.update { it.copy(smsPrompt = null, busy = true) } }
        }
        loadOverview()
        mutable.update { it.copy(showLogin = false, message = "登录成功") }
    }

    fun submitSms(value: String) {
        if (value.isBlank()) return
        smsAnswer?.complete(value.trim())
        mutable.update { it.copy(busy = true) }
    }

    private fun startCountdown(seconds: Int) {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (second in seconds downTo 0) {
                mutable.update { it.copy(resendSeconds = second) }
                if (second > 0) delay(1_000)
            }
        }
    }

    fun resendSms() {
        if (mutable.value.resendSeconds > 0 || mutable.value.busy || smsAnswer == null) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                val outcome = repository.auth.resendReauthDynamicCode().getOrThrow()
                mutable.update { it.copy(smsPrompt = edu.uestc.nightsign.data.auth.CasLoginRepository.buildSmsUserPrompt(outcome)) }
                startCountdown(outcome.resendCooldownSec ?: 60)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(message = error.message) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun saveSettings(settings: SignSettings) {
        runCatching {
            settings.coordinates()
            require(settings.address.isNotBlank()) { "请先填写地址" }
            require(settings.targetMinute in 0..1439) { "签到时间无效" }
            val schedule = mutable.value.overview?.schedule
            if (settings.autoEnabled) {
                require(schedule != null) { "请先登录并刷新平台时段" }
                require(schedule.contains(settings.targetMinute)) { "目标时间不在平台签到时段" }
            }
            app.store.saveSettings(settings)
            CheckinWorker.schedule(app, settings.autoEnabled)
            mutable.update { it.copy(settings = settings, message = "设置已保存") }
        }.onFailure { error -> mutable.update { it.copy(message = error.message) } }
    }

    fun loadCurrentLocation(onFilled: (String, String, String) -> Unit) = launchOperation {
        val (lat, lng) = DeviceLocator(getApplication()).currentGcj02()
        val latText = formatCoordinate(lat)
        val lngText = formatCoordinate(lng)
        val address = try {
            withContext(Dispatchers.IO) {
                MapAddressResolver(getApplication(), BuildConfig.TENCENT_MAP_KEY).resolve(lat, lng)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFilled(latText, lngText, "")
            throw error
        }
        onFilled(latText, lngText, address)
        mutable.update { it.copy(message = "已填入当前位置和地址，请保存设置") }
    }

    fun showMessage(text: String) {
        mutable.update { it.copy(message = text) }
    }

    fun checkin() = launchOperation {
        repository.checkin()
        loadOverview()
        mutable.update { it.copy(message = "签到成功，已复查确认") }
    }

    fun setTheme(key: String) {
        app.store.putSecret("theme", key)
        mutable.update { it.copy(theme = key) }
    }

    fun logout() = launchOperation {
        authorizationSync?.cancelAndJoin()
        val previous = repository.wechatCodeSettings()
        if (previous.accessToken.isNotBlank()) withContext(Dispatchers.IO) {
            runCatching { ServerPairingClient().revoke(previous) }
                .onFailure { app.store.record("服务器未确认撤销绑定，可在管理页面撤销设备") }
        }
        repository.logout()
        CheckinWorker.schedule(app, false)
        mutable.update { it.copy(overview = null, signedIn = false, settings = app.store.settings(),
            wechatCode = repository.wechatCodeSettings(), serverAuthorization = "", message = "已退出登录") }
    }
    fun clearMessage() { mutable.update { it.copy(message = null) } }

    private suspend fun loadOverview() {
        try {
            val overview = repository.refresh()
            app.store.windowEnd = overview.schedule.endMinute
            mutable.update { it.copy(overview = overview, signedIn = true) }
        } catch (expired: SessionExpiredException) {
            mutable.update { it.copy(overview = null, signedIn = false) }
            throw expired
        }
    }

    fun checkAppUpdate(force: Boolean = false) {
        viewModelScope.launch {
            if (!force && !updateStorage.shouldCheckNow()) return@launch
            try {
                val info = withContext(Dispatchers.IO) { updateChecker.fetchLatestIfNewer(BuildConfig.VERSION_NAME) }
                if (!force) updateStorage.markCheckedNow()
                if (info == null) {
                    if (force) mutable.update { it.copy(message = "当前已是最新版本（${BuildConfig.VERSION_NAME}）") }
                    return@launch
                }
                if (!force && updateStorage.isDismissed(info.releaseTag)) return@launch
                mutable.update {
                    it.copy(updatePrompt = UpdatePrompt(info.releaseTag, info.versionName, info.releaseNotes, info.downloadUrl))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (force) mutable.update { it.copy(message = error.message ?: "检查更新失败") }
            }
        }
    }

    fun dismissUpdatePrompt() {
        val tag = mutable.value.updatePrompt?.releaseTag ?: return
        updateStorage.dismiss(tag)
        mutable.update { it.copy(updatePrompt = null) }
    }

    fun clearUpdatePrompt() { mutable.update { it.copy(updatePrompt = null) } }

    private fun formatCoordinate(value: Double) = "%.6f".format(Locale.US, value)
}
