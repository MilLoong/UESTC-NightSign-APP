package edu.uestc.nightsign.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import edu.uestc.nightsign.data.auth.CasLoginRepository
import edu.uestc.nightsign.data.local.PrivateStore
import edu.uestc.nightsign.data.network.*
import edu.uestc.nightsign.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

data class SavedCookie(val origin: String, val header: String)
data class SavedAccount(val username: String, val password: String)

/** 前台操作与后台检查共享同一 CookieJar 和互斥锁。 */
class NightSignRepository(val store: PrivateStore,
    private val localWechat: edu.uestc.nightsign.data.wechat.LocalWechatClient) {
    val jar = InMemoryCookieJar()
    val client = ApiConstants.buildOkHttp(jar)
    val auth = CasLoginRepository(client, jar)
    private val codeClient = WechatCodeClient()
    private val api = NightSignApi(client, onDiagnostic = { store.record(it.summary()) },
        checkinTicketProvider = { acquireWechatTicket() })
    private val mutex = Mutex()
    private val gson = Gson()

    fun wechatMode(): String = if (store.secret("wechat-mode") == "server") "server" else "local"

    suspend fun setWechatMode(mode: String) = withContext(Dispatchers.IO) {
        require(mode in setOf("local", "server"))
        mutex.withLock { store.putSecret("wechat-mode", mode) }
    }

    @Synchronized fun wechatCodeSettings(): WechatCodeSettings = runCatching {
        gson.fromJson(store.secret("wechat-code"), WechatCodeSettings::class.java) ?: WechatCodeSettings()
    }.getOrDefault(WechatCodeSettings()).let { if (it.mode == "yyb") it else WechatCodeSettings() }

    @Synchronized fun saveWechatCodeSettings(settings: WechatCodeSettings) {
        settings.validatedUrl()
        require(settings.mode == "yyb") { "取码模式无效" }
        if (!settings.accountRef.isNullOrBlank()) settings.validate()
        if (settings.accessToken.isNotBlank()) store.putSecret("wechat-code", gson.toJson(settings))
        else store.removeSecret("wechat-code")
    }

    @Synchronized fun clearWechatCodeSettings() { store.removeSecret("wechat-code") }

    private fun synchronizeWechatAuthorization(): edu.uestc.nightsign.data.wechat.ServerAuthorizationStatus {
        val settings = wechatCodeSettings()
        require(settings.accessToken.isNotBlank()) { "请先扫码连接服务器" }
        val status = edu.uestc.nightsign.data.wechat.ServerPairingClient().status(settings)
        synchronized(this) {
            check(wechatCodeSettings() == settings) { "服务器配置已更改，请重试" }
            saveWechatCodeSettings(settings.copy(accountRef = status.ref.orEmpty()))
        }
        return status
    }

    suspend fun syncWechatAuthorization() = withContext(Dispatchers.IO) {
        mutex.withLock { synchronizeWechatAuthorization() }
    }

    private fun acquireWechatTicket(): String {
        val code = edu.uestc.nightsign.data.wechat.fetchCodeForMode(wechatMode(),
            local = { localWechat.fetchCode(schoolSession()) }, server = {
            require(synchronizeWechatAuthorization().authorized) { "请先在服务器管理页完成微信授权" }
            codeClient.fetchCode(wechatCodeSettings())
        })
        return api.requestCheckinTicket(code)
    }

    private fun schoolSession(): String = jar.loadForRequest(ApiConstants.PLATFORM_ORIGIN.toHttpUrl())
        .firstOrNull { it.name == "SESSION" }?.value ?: throw IOException("请先登录学校账号")

    suspend fun authorizeLocalWechat(cancelled: java.util.concurrent.atomic.AtomicBoolean,
        showQr: (String) -> Unit, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareSession(false)
            val code = localWechat.fetchCode(schoolSession(), true, cancelled, showQr, progress)
            currentCoroutineContext().ensureActive()
            api.requestCheckinTicket(code)
            store.record("手机本地微信授权成功（未提交签到）")
        }
    }

    suspend fun testWechatAuthorization() = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareSession(false)
            acquireWechatTicket()
            store.record("微信取码及学校授权成功（未提交签到）")
        }
    }

    init {
        runCatching {
            val type = object : TypeToken<List<SavedCookie>>() {}.type
            val saved: List<SavedCookie> = gson.fromJson(store.secret("cookies"), type) ?: emptyList()
            saved.forEach { row ->
                val url = row.origin.toHttpUrl()
                Cookie.parse(url, row.header)?.let { jar.saveFromResponse(url, listOf(it)) }
            }
        }
    }

    private fun persistSession() {
        val rows = jar.snapshot().map { SavedCookie("https://${it.domain.removePrefix(".")}${it.path}", it.toString()) }
        store.putSecret("cookies", gson.toJson(rows))
    }

    private fun resetLoginSession() {
        val retained = jar.snapshot().filterNot { it.name in setOf("SESSION", "JSESSIONID", "CASTGC", "happyVoyage") }
        jar.clearAll()
        retained.forEach { cookie ->
            val url = "https://${cookie.domain.removePrefix(".")}${cookie.path}".toHttpUrl()
            jar.saveFromResponse(url, listOf(cookie))
        }
    }

    suspend fun login(user: String, password: String, remember: Boolean, sms: suspend (String) -> String?) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.lastLoginAttempt = System.currentTimeMillis()
                store.removeSecret("account")
                resetLoginSession()
                try {
                    auth.login(user, password, sms).getOrThrow()
                    api.warmUp()
                    api.enterApp()
                    val today = api.today()
                    store.lastSuccess = if (today.checkedIn && !today.abnormal) today.businessDate else ""
                    persistSession()
                    if (remember) store.putSecret("account", gson.toJson(SavedAccount(user, password)))
                    else store.removeSecret("account")
                    store.needsLogin = false
                    store.record("登录成功，会话已保存")
                } finally {
                    // 指纹必须跨进程保留，失败时也不重新生成。
                    persistSession()
                }
            }
        }

    private suspend fun prepareSession(allowAutoLogin: Boolean) {
        try {
            api.warmUp()
            api.enterApp()
        } catch (expired: SessionExpiredException) {
            if (!allowAutoLogin) throw expired
            if (store.needsLogin) throw SessionExpiredException()
            if (jar.snapshot().any { it.name == "CASTGC" }) {
                try {
                    client.newCall(okhttp3.Request.Builder().url(ApiConstants.casLoginUrlWithService()).build())
                        .execute().close()
                    api.warmUp()
                    api.enterApp()
                    persistSession()
                    return
                } catch (_: SessionExpiredException) {
                    // SSO 不能换取业务会话时，才尝试用户授权保存的密码。
                }
            }
            val account = gson.fromJson(store.secret("account"), SavedAccount::class.java) ?: throw expired
            if (System.currentTimeMillis() - store.lastLoginAttempt < 30 * 60_000L) {
                throw IOException("自动登录冷却中，稍后重试或手动登录")
            }
            store.lastLoginAttempt = System.currentTimeMillis()
            resetLoginSession()
            // 复用密码登录；遇到二次验证时，在发码前退出并交给用户。
            val result = auth.login(account.username, account.password, { null }, allowInteractive = false)
            currentCoroutineContext().ensureActive()
            if (result.isFailure) {
                persistSession()
                store.needsLogin = true
                throw SessionExpiredException()
            }
            api.warmUp()
            api.enterApp()
        }
        persistSession()
    }

    suspend fun refresh(): CheckinOverview = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareSession(false)
            val overview = CheckinOverview(api.today(), api.schedule(), api.locations())
            if (overview.status.businessDate == CheckinPolicy.today() && overview.status.checkedIn && !overview.status.abnormal) {
                store.lastSuccess = overview.status.businessDate
            }
            overview
        }
    }

    suspend fun reverse(settings: SignSettings): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareSession(false)
            val (lat, lng) = settings.coordinates()
            api.reverse(lat, lng)
        }
    }

    suspend fun checkin(background: Boolean = false): CheckinStatus? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settings = store.settings()
            if (background && (!settings.autoEnabled || store.lastSuccess == CheckinPolicy.today())) return@withLock null
            prepareSession(background)
            val after = CheckinFlow(api).run(settings, background) { store.windowEnd = it.endMinute }
                ?: return@withLock null
            store.lastSuccess = after.businessDate
            store.record("签到成功，已复查确认")
            after
        }
    }

    /** WebView Cookie 必须在主线程读取；只导入两个学校站点。 */
    suspend fun importWebCookies(headers: Map<String, String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            headers.forEach { (origin, header) ->
                val url = origin.toHttpUrl()
                require(url.host in setOf("idas.uestc.edu.cn", "jzsz.uestc.edu.cn"))
                header.split(';').forEach { pair ->
                    val name = pair.substringBefore('=').trim()
                    val value = pair.substringAfter('=', "").trim()
                    if (name.isNotBlank() && value.isNotBlank()) {
                        val path = if (name in setOf("JSESSIONID", "CASTGC", "route") && url.host == "idas.uestc.edu.cn") "/authserver" else "/"
                        jar.saveFromResponse(url, listOf(Cookie.Builder().name(name).value(value)
                            .hostOnlyDomain(url.host).path(path).secure().build()))
                    }
                }
            }
            api.warmUp()
            api.enterApp()
            val today = api.today()
            store.lastSuccess = if (today.checkedIn && !today.abnormal) today.businessDate else ""
            persistSession()
            store.needsLogin = false
            store.record("已导入 Web 登录会话")
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        mutex.withLock {
            jar.clearAll()
            store.removeSecret("cookies")
            store.removeSecret("account")
            store.lastSuccess = ""
            store.removeSecret("wechat-code")
            store.needsLogin = false
            store.saveSettings(store.settings().copy(autoEnabled = false))
            store.record("已退出登录并关闭后台签到")
        }
        withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                android.webkit.CookieManager.getInstance().removeAllCookies {
                    android.webkit.CookieManager.getInstance().flush()
                    if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                }
            }
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
    }
}
