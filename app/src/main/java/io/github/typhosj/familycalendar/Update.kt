package io.github.typhosj.familycalendar

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val RELEASES = "https://github.com/typhosj/google-calendar-android/releases"
private const val APK_NAME = "familienkalender.apk"
private const val APK_TYPE = "application/vnd.android.package-archive"
private const val TIMEOUT_MS = 15_000
private const val MAX_APK_BYTES = 64L * 1024 * 1024

private val TAG_URL = Regex("""^${Regex.escape(RELEASES)}/tag/v(\d+\.\d+\.\d+)$""")
private val VERSION = Regex("""^\d+\.\d+\.\d+$""")

/** The version GitHub's redirect for the latest release points to, or null if there is none. */
fun versionFromRedirect(location: String?): String? = location?.let { TAG_URL.find(it)?.groupValues?.get(1) }

/** Compares by number; anything but a plain x.y.z is never newer, so a debug build is not offered updates. */
fun isNewer(latest: String, current: String?): Boolean {
    if (current == null || !VERSION.matches(latest) || !VERSION.matches(current)) return false
    val a = latest.split(".").map { it.toInt() }
    val b = current.split(".").map { it.toInt() }
    val difference = a.zip(b).firstOrNull { (x, y) -> x != y } ?: return false
    return difference.first > difference.second
}

/** Looks for a newer GitHub release and hands its APK to the system installer. */
class Updater(private val context: Context) {
    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
    }

    /** The newer version, or null when there is none or GitHub cannot be reached. */
    fun available(): String? {
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return runCatching { latest() }.getOrNull()?.takeIf { isNewer(it, current) }
    }

    /** Reads the redirect instead of the GitHub API, which limits anonymous requests. */
    private fun latest(): String? {
        val connection = open("$RELEASES/latest").apply {
            instanceFollowRedirects = false
            requestMethod = "HEAD"
        }
        try {
            connection.responseCode
            return versionFromRedirect(connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }

    fun download(): File {
        val file = File(File(context.cacheDir, "updates").apply { mkdirs() }, APK_NAME)
        val connection = open("$RELEASES/latest/download/$APK_NAME")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) throw IOException("APK too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return file
    }

    /** Android only accepts the file if it carries the signature of the installed app. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
