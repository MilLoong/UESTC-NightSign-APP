package edu.uestc.nightsign.data.wechat

internal fun fetchCodeForMode(mode: String, local: () -> String, server: () -> String): String = when (mode) {
    "local" -> local()
    "server" -> server()
    else -> error("微信授权模式无效")
}
