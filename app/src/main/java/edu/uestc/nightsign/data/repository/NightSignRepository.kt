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
class NightSignRepository(val store: PrivateStore) {
    val jar = InMemoryCookieJar()
    val client = ApiConstants.buildOkHttp(jar)
    val auth = CasLoginRepository(client, jar)
    private val api = NightSignApi(client)
    private val mutex = Mutex()
    private val gson = Gson()

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
