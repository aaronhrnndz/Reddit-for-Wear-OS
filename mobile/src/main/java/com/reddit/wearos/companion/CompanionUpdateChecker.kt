package com.reddit.wearos.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

data class CompanionReleaseInfo(
    val latestTag: String,
    val latestVersion: String,
    val isUpdateAvailable: Boolean,
    val releaseHtmlUrl: String,
    val wearApkUrl: String?,
    val mobileApkUrl: String?
)

object CompanionUpdateChecker {
    private const val CODEBERG_MANIFEST_URL = "https://codeberg.org/Aaronhrndz/Reddit-for-Wear-OS/raw/branch/main/update.json"
    private const val CODEBERG_RELEASES_API = "https://codeberg.org/api/v1/repos/Aaronhrndz/Reddit-for-Wear-OS/releases?limit=5"
    private val GITHUB_REPOS = listOf("Aaronhrndz/Reddit-for-Wear-OS", "aaronhrnndz/Reddit-for-Wear-OS")
    private const val PREFS_NAME = "companion_ota_prefs"
    private const val KEY_BETA_CHANNEL = "beta_channel_enabled"
    private val json = Json { ignoreUnknownKeys = true }

    fun isBetaChannel(context: android.content.Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_BETA_CHANNEL, false)
    }

    fun setBetaChannel(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BETA_CHANNEL, enabled)
            .apply()
    }

    suspend fun checkLatestRelease(currentVersionName: String, isBeta: Boolean = false): CompanionReleaseInfo? = withContext(Dispatchers.IO) {
        val cleanCurrent = currentVersionName.removePrefix("v").trim()
        var best: CompanionReleaseInfo? = null

        fun pickHigher(candidate: CompanionReleaseInfo?) {
            if (candidate == null) return
            val cur = best
            if (cur == null || isNewerVersion(candidate.latestVersion, cur.latestVersion)) {
                best = candidate
            }
        }

        // 1. Codeberg Master Manifest (update.json)
        fetchText(CODEBERG_MANIFEST_URL)?.let { body ->
            try {
                val root = json.parseToJsonElement(body).jsonObject
                val ver = root["version"]?.jsonPrimitive?.content?.removePrefix("v")?.trim()
                val isPre = root["isPrerelease"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                if (!ver.isNullOrEmpty() && (isBeta || !isPre)) {
                    pickHigher(
                        CompanionReleaseInfo(
                            latestTag = "v$ver",
                            latestVersion = ver,
                            isUpdateAvailable = isNewerVersion(ver, cleanCurrent),
                            releaseHtmlUrl = root["htmlUrl"]?.jsonPrimitive?.content
                                ?: "https://codeberg.org/Aaronhrndz/Reddit-for-Wear-OS/releases/latest",
                            wearApkUrl = root["wearApkUrl"]?.jsonPrimitive?.content,
                            mobileApkUrl = root["mobileApkUrl"]?.jsonPrimitive?.content
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        // 2 & 3. GitHub Primary (Aaronhrndz) & Secondary (aaronhrnndz)
        for (repo in GITHUB_REPOS) {
            val apiUrl = if (isBeta) {
                "https://api.github.com/repos/$repo/releases?per_page=5"
            } else {
                "https://api.github.com/repos/$repo/releases/latest"
            }
            fetchText(apiUrl, "application/vnd.github+json")?.let { body ->
                try {
                    if (isBeta) {
                        val arr = json.parseToJsonElement(body).jsonArray
                        for (elem in arr) {
                            val obj = elem.jsonObject
                            if (obj["draft"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) continue
                            pickHigher(parseReleaseObj(obj, cleanCurrent, repo))
                        }
                    } else {
                        pickHigher(parseReleaseObj(json.parseToJsonElement(body).jsonObject, cleanCurrent, repo))
                    }
                } catch (_: Exception) {}
            }
        }

        // 4. Codeberg Releases API
        fetchText(CODEBERG_RELEASES_API, "application/json")?.let { body ->
            try {
                val arr = json.parseToJsonElement(body).jsonArray
                for (elem in arr) {
                    val obj = elem.jsonObject
                    if (obj["draft"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) continue
                    val isPre = obj["prerelease"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    if (!isBeta && isPre) continue
                    pickHigher(parseReleaseObj(obj, cleanCurrent, "Aaronhrndz/Reddit-for-Wear-OS"))
                }
            } catch (_: Exception) {}
        }

        best
    }

    private fun fetchText(urlStr: String, accept: String? = null): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                if (accept != null) setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "RedForReddit-CompanionUpdateChecker")
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
            }
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parseReleaseObj(
        root: kotlinx.serialization.json.JsonObject,
        cleanCurrent: String,
        fallbackRepo: String
    ): CompanionReleaseInfo? {
        val tagName = root["tag_name"]?.jsonPrimitive?.content ?: return null
        val htmlUrl = root["html_url"]?.jsonPrimitive?.content
            ?: "https://github.com/$fallbackRepo/releases/latest"
        val cleanLatest = tagName.removePrefix("v").trim()
        var wearUrl: String? = null
        var mobileUrl: String? = null

        root["assets"]?.jsonArray?.forEach { elem ->
            val obj = elem.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: ""
            val dl = obj["browser_download_url"]?.jsonPrimitive?.content
            if (name.contains("Wear", ignoreCase = true) && name.endsWith(".apk", ignoreCase = true)) {
                wearUrl = dl
            } else if (name.contains("Mobile", ignoreCase = true) && name.endsWith(".apk", ignoreCase = true)) {
                mobileUrl = dl
            }
        }

        return CompanionReleaseInfo(
            latestTag = tagName,
            latestVersion = cleanLatest,
            isUpdateAvailable = isNewerVersion(cleanLatest, cleanCurrent),
            releaseHtmlUrl = htmlUrl,
            wearApkUrl = wearUrl,
            mobileApkUrl = mobileUrl
        )
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        val rClean = remote.substringBefore("-").trim()
        val lClean = local.substringBefore("-").trim()

        val rParts = rClean.split(".").map { it.toIntOrNull() ?: 0 }
        val lParts = lClean.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }

        // Si la parte numérica base es igual (ej. 1.6.8 y 1.6.8-beta.1), comparar sufijos
        if (remote.contains("-") && !local.contains("-")) return false
        if (!remote.contains("-") && local.contains("-")) return true
        if (remote.contains("-") && local.contains("-")) {
            val rSuffix = remote.substringAfter("-")
            val lSuffix = local.substringAfter("-")
            return rSuffix.compareTo(lSuffix) > 0
        }

        return false
    }
}
