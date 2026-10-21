package edu.uestc.nightsign.data.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import edu.uestc.nightsign.AppLinks
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 从 GitHub Releases 检查是否有新版本。不把 Latest 标记当成唯一依据。 */
class AppUpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
) {
    data class UpdateInfo(
        val releaseTag: String,
        val versionName: String,
        val releasePageUrl: String,
        val downloadUrl: String,
        val releaseNotes: String,
    )

    fun fetchLatestIfNewer(localVersionName: String): UpdateInfo? {
        val release = fetchNewestRelease()
        val tag = release.text("tag_name")
        if (tag.isBlank()) throw IOException("没有读到版本号")
        val versionName = AppVersion.normalizeTag(tag)
        if (!AppVersion.isRemoteNewer(versionName, localVersionName)) return null
        val pageUrl = release.text("html_url").ifBlank { AppLinks.GITHUB_RELEASES_LATEST }
        return UpdateInfo(
            releaseTag = tag,
            versionName = versionName,
            releasePageUrl = pageUrl,
            downloadUrl = pickApk(release) ?: pageUrl,
            releaseNotes = release.text("body").take(1500),
        )
    }

    private fun fetchNewestRelease(): JsonObject {
        val listed = runCatching { fetchReleaseList() }.getOrNull()
        if (!listed.isNullOrEmpty()) {
            return listed.maxWith { left, right ->
                val leftVersion = AppVersion.normalizeTag(left.text("tag_name"))
                val rightVersion = AppVersion.normalizeTag(right.text("tag_name"))
                when {
                    AppVersion.isRemoteNewer(leftVersion, rightVersion) -> 1
                    AppVersion.isRemoteNewer(rightVersion, leftVersion) -> -1
                    else -> 0
                }
            }
        }
        return fetchLatest()
    }

    private fun fetchReleaseList(): List<JsonObject> {
        val array = JsonParser.parseString(get(AppLinks.GITHUB_RELEASES_API)).asJsonArray
        return array.map { it.asJsonObject }.filter { item ->
            item.flag("draft") == false && item.flag("prerelease") == false && item.text("tag_name").isNotBlank()
        }
    }

    private fun fetchLatest(): JsonObject = JsonParser.parseString(get(AppLinks.GITHUB_RELEASES_LATEST_API)).asJsonObject

    private fun get(url: String): String {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "UESTC-NightSign")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val hint = if (response.code == 404) "还没有找到发布，或仓库暂时无法访问" else "检查更新失败：HTTP ${response.code}"
                throw IOException(hint)
            }
            if (body.isBlank()) throw IOException("检查更新没有返回内容")
            return body
        }
    }

    private fun pickApk(release: JsonObject): String? {
        val assets = release.getAsJsonArray("assets") ?: return null
        var fallback: String? = null
        for (element in assets) {
            val asset = element.asJsonObject
            val name = asset.text("name")
            val url = asset.text("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue
            if (name.contains("NightSign", ignoreCase = true)) return url
            fallback = url
        }
        return fallback
    }
}

private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

private fun JsonObject.flag(key: String): Boolean = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
