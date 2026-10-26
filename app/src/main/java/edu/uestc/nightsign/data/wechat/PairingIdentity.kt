package edu.uestc.nightsign.data.wechat

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PairingIdentity {
    private fun mac(key: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    fun installationKey(seed: String, offer: ServerPairingOffer): String {
        val bytes = Base64.getUrlDecoder().decode(seed)
        require(bytes.size == 32)
        val scope = offer.serverId?.let { "server:$it" } ?: "origin:${offer.endpoint}"
        return mac(bytes, "uestc-nightsign-installation\n$scope")
    }

    fun previousProof(token: String, code: String, installationKey: String): String = mac(
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        "uestc-nightsign-rebind\n$code\n$installationKey",
    )
}
