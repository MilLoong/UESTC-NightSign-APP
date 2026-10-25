package edu.uestc.nightsign.data.wechat

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Retries the same QR operation; callers must bound the whole authorization by its expiry. */
internal suspend fun <T> retryWechatQr(
    maxAttempts: Int = Int.MAX_VALUE,
    pause: suspend (Long) -> Unit = { delay(it) },
    onRetry: (String) -> Unit = {},
    operation: suspend () -> T,
): T {
    require(maxAttempts > 0)
    var failures = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        try { return operation() }
        catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            failures++
            if (failures >= maxAttempts || error is WechatQrRequestException && !error.retryable) throw error
            onRetry(if (error is WechatQrRequestException) error.message.orEmpty() else "微信授权连接暂时中断")
            pause((2_000L * failures).coerceAtMost(10_000L))
        }
    }
}
