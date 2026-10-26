package edu.uestc.nightsign.data.wechat

import android.content.Context
import edu.uestc.nightsign.data.local.PrivateStore
import edu.uestc.nightsign.data.wechat.nativeprotocol.NativeYybAuth
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** The live signup code path is Kotlin-only; Python sources remain as a reference. */
class LocalWechatClient(@Suppress("UNUSED_PARAMETER") context: Context, private val store: PrivateStore) {
    val authorized: Boolean get() = !store.secret("local-wechat-credentials").isNullOrBlank()

    @Synchronized
    fun fetchCode(session: String, allowQr: Boolean = false, cancelled: AtomicBoolean = AtomicBoolean(false),
                  showQr: (String) -> Unit = {}, progress: (String) -> Unit = {}): String {
        try {
            return NativeYybAuth(cancelled, { message -> if (!cancelled.get()) progress(message) },
                { image -> if (!cancelled.get()) showQr(image) },
                { json -> check(!cancelled.get()) { "AUTH_CANCELLED" }; store.putSecret("local-wechat-credentials", json) })
                .getCode(session, store.secret("local-wechat-credentials"), allowQr)
        } catch (error: Exception) {
            val stage = error.message.orEmpty().takeIf { Regex("[A-Z][A-Z0-9_]{0,79}").matches(it) } ?: "NATIVE_NETWORK_OR_PROTOCOL_FAILED"
            store.record("Kotlin 微信取码失败：$stage")
            val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            throw IOException(when {
                cancelled.get() || stage == "AUTH_CANCELLED" -> "微信授权已取消"
                causes.any { it is UnknownHostException } -> "微信域名解析失败，请检查手机网络"
                causes.any { it is SocketTimeoutException } -> "微信网络连接超时，请重试"
                stage == "AUTHORIZATION_REQUIRED" -> "请先在设置中扫码授权微信"
                stage.startsWith("QR_EXPIRED") -> "二维码已过期或已取消，请重新授权"
                stage == "WECHAT_CREDENTIAL_REJECTED" -> "微信授权失效，请清除本地授权后重新扫码"
                stage.startsWith("SCHOOL_") -> "学校会话已失效，请重新登录学校账号"
                else -> "Kotlin 微信取码失败（$stage），未切换其他取码方式"
            })
        }
    }

    fun clear() { store.removeSecret("local-wechat-credentials") }
}
