package edu.uestc.nightsign.data.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 统一认证与晚安成电的接口入口。 */
object ApiConstants {
    const val CAS_ORIGIN = "https://idas.uestc.edu.cn"
    const val CAS_BASE_URL = "$CAS_ORIGIN/authserver"
    const val PLATFORM_ORIGIN = "https://jzsz.uestc.edu.cn"
    const val CAS_SERVICE_RAW = "$PLATFORM_ORIGIN/"
    const val H5_URL = "$PLATFORM_ORIGIN/daily-checkin-h5/index.html?appId=294"
    const val APP_ENTRY = "/daily-checkin/checkLogin?appId=294"
    const val WARMUP = "/api/isLoginFailure"
    const val TODAY = "/daily-checkin/api/student-checkin-record/today"
    const val SCHEDULE = "/daily-checkin/api/checkin-schedule/effective"
    const val LOCATIONS = "/daily-checkin/api/checkin-location/available-locations"
    const val REVERSE = "/daily-checkin/api/map/reverse"
    const val CHECKIN = "/daily-checkin/api/student-checkin-record/checkin"
    // 保留参考项目网关判定器所需的页面标记，不访问教务站点。
    const val ONLINE_STRUTS_ONCE_PARAM = "4oY1vBSn"
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

    fun casLoginUrlWithService(): String =
        "$CAS_BASE_URL/login?service=${URLEncoder.encode(CAS_SERVICE_RAW, "UTF-8")}"

    fun casLoginRefererPlain(): String = casLoginUrlWithService()

    fun buildOkHttp(jar: InMemoryCookieJar): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(jar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                chain.proceed(builder.build())
            }.build()
}
