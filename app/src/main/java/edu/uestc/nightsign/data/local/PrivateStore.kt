package edu.uestc.nightsign.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import edu.uestc.nightsign.domain.SignSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cookie 与可选账号密码仅在本机保存，用 Android Keystore 加密。 */
class PrivateStore(context: Context) {
    private val prefs = context.getSharedPreferences("night_sign_private", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val alias = "uestc_nightsign_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    @Synchronized fun putSecret(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }

    @Synchronized fun secret(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun removeSecret(name: String) { prefs.edit().remove(name).apply() }
    fun settings(): SignSettings = runCatching {
        gson.fromJson(secret("settings"), SignSettings::class.java) ?: SignSettings()
    }.getOrDefault(SignSettings())
    fun saveSettings(settings: SignSettings) = putSecret("settings", gson.toJson(settings))
    var lastSuccess: String
        get() = prefs.getString("last_success", "").orEmpty()
        set(value) { prefs.edit().putString("last_success", value).apply() }
    var lastLoginAttempt: Long
        get() = prefs.getLong("last_login_attempt", 0L)
        set(value) { prefs.edit().putLong("last_login_attempt", value).apply() }
    var needsLogin: Boolean
        get() = prefs.getBoolean("needs_login", false)
        set(value) { prefs.edit().putBoolean("needs_login", value).apply() }
    var windowEnd: Int
        get() = prefs.getInt("window_end", 23 * 60 + 30)
        set(value) { prefs.edit().putInt("window_end", value).apply() }
    fun events(): List<String> = prefs.getString("events", "").orEmpty().lines().filter(String::isNotBlank)
    @Synchronized fun record(message: String) {
        val time = edu.uestc.nightsign.domain.CheckinPolicy.now().toLocalDateTime().toString().take(19)
        val events = (listOf("$time  ${message.replace('\n', ' ').take(180)}") + events()).take(40)
        prefs.edit().putString("events", events.joinToString("\n")).apply()
    }
}
