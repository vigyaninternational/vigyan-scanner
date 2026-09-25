package com.vigyan.scanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates: asks GitHub for the latest release, downloads its APK and opens Android's
 * installer. The new version installs over the old one (same signing key, higher versionCode),
 * so saved scans stay.
 */
object Updater {

    private const val LATEST = "https://api.github.com/repos/vigyaninternational/vigyan-scanner/releases/latest"

    class Release(val code: Int, val name: String, val url: String, val size: Long)

    /** The latest published release, or null if it has no APK. Blocking: call off the main thread. */
    fun latest(): Release? {
        val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "VigyanScanner")
        }
        try {
            if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            return Release(
                code = Versions.codeFromTag(tag) ?: return null,
                name = tag.removePrefix("v"),
                url = apk.getString("browser_download_url"),
                size = apk.optLong("size"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK (GitHub redirects to its file server; HttpURLConnection follows). */
    fun download(release: Release, dest: File, onProgress: (Int) -> Unit) {
        if (dest.exists() && release.size > 0 && dest.length() == release.size) return
        dest.parentFile?.apply { deleteRecursively(); mkdirs() }
        val part = File(dest.parentFile, dest.name + ".part")
        val conn = (URL(release.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VigyanScanner")
        }
        try {
            if (conn.responseCode != 200) error("Download failed (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(dest)) error("Could not save the update")
    }

    /**
     * Opens Android's installer. On Android 8+ the app first needs "Install unknown apps"
     * permission; if it's missing, that settings page opens instead and this returns false.
     */
    fun install(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        return true
    }
}
