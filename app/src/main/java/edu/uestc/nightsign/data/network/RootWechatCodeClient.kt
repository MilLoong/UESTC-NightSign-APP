package edu.uestc.nightsign.data.network

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RootCommandResult(val exitCode: Int, val output: String) {
    override fun toString() = "RootCommandResult(exitCode=$exitCode)"
}

fun interface RootCommandRunner {
    fun run(command: String): RootCommandResult
}

/** Runs only the fixed root-owned adapter, never commands or paths supplied by UI input. */
class SuCommandRunner : RootCommandRunner {
    override fun run(command: String): RootCommandResult {
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (_: IOException) {
            throw IOException("需要已 Root 的手机，并在 Root 管理器中授权本应用")
        }
        val reader = Executors.newSingleThreadExecutor { task ->
            Thread(task, "root-wechat-response").apply { isDaemon = true }
        }
        val output = reader.submit<String> {
            process.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(512)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (bytes.size() + count > 4096) throw IOException("Root 取码组件响应过大")
                    bytes.write(buffer, 0, count)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        }
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw IOException("Root 取码超时，请检查授权与微信运行状态")
            return RootCommandResult(process.exitValue(), output.get(2, TimeUnit.SECONDS))
        } catch (error: IOException) {
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Root 取码已中断")
        } catch (_: Exception) {
            throw IOException("Root 取码组件响应失败")
        } finally {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            output.cancel(true)
            reader.shutdownNow()
        }
    }
}

class RootWechatCodeClient(
    private val runner: RootCommandRunner = SuCommandRunner(),
    private val nonceFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    fun fetchCode(): String {
        val nonce = nonceFactory()
        require(Regex("[a-f0-9]{32}").matches(nonce)) { "取码请求标识无效" }
        val command = "[ \"\$(id -u)\" = 0 ] || exit 73; " +
            "[ -x $ADAPTER ] || exit 74; " +
            "exec $ADAPTER login --app-id ${ApiConstants.WECHAT_APP_ID} --nonce $nonce"
        val result = runner.run(command)
        when (result.exitCode) {
            0 -> Unit
            74 -> throw IOException("已获得 Root，但尚未安装兼容的 Android 微信取码适配组件")
            73 -> throw IOException("Root 授权失败，请在 Root 管理器中允许本应用")
            else -> throw IOException("Root 取码失败，请检查授权、适配组件和微信版本")
        }
        if (result.output.toByteArray(Charsets.UTF_8).size > 4096) throw IOException("Root 取码组件响应过大")
        val json = runCatching { JsonParser.parseString(result.output).asJsonObject }.getOrNull()
            ?: throw IOException("Root 取码组件响应无效")
        fun text(key: String) = runCatching { json.get(key)?.asString }.getOrNull()
        val ok = runCatching { json.get("ok")?.asBoolean == true }.getOrDefault(false)
        if (!ok) throw IOException(when (text("error")) {
            "TARGET_NOT_OPEN" -> "请在本机微信中打开成电智慧学工后重试"
            "UNSUPPORTED_VERSION" -> "当前 Android 微信版本尚未适配"
            "BRIDGE_UNAVAILABLE" -> "本机微信桥不可用，请检查模块启用状态和系统隔离策略"
            "WX_LOGIN_TIMEOUT" -> "微信登录回调超时，请重新打开成电智慧学工后重试"
            "RESTART_REQUIRED" -> "微信桥需要重新初始化，请重新启动微信"
            "BUSY" -> "微信取码组件繁忙，请稍后重试"
            else -> "本机微信取码失败，请检查适配组件"
        })
        val code = text("code")
        if (text("appId") != ApiConstants.WECHAT_APP_ID || text("nonce") != nonce ||
            code == null || !Regex("[A-Za-z0-9_-]{24,128}").matches(code)) {
            throw IOException("Root 取码响应身份或请求标识不匹配")
        }
        return code
    }

    companion object {
        const val ADAPTER = "/data/adb/uestc-nightsign/bin/wx-login"
    }
}
