package io.github.typhosj.familycalendar

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val RELEASES = "https://github.com/typhosj/google-calendar-android/releases"
private const val APK_NAME = "familienkalender.apk"
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

    /**
     * Installs through a PackageInstaller session; Android only accepts the file if it carries the
     * signature of the installed app. From Android 12 on, an app that updates itself may skip the
     * confirmation; where Android still wants one, [InstallReceiver] shows it.
     */
    fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite(APK_NAME, 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                // Mutable, because the installer adds its result to the intent.
                val result = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, InstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(result.intentSender)
            }
        } catch (error: Exception) {
            installer.abandonSession(id)
            throw error
        }
    }
}

enum class InstallOutcome { CONFIRM, DONE, FAILED }

/** What the installer result means for the user. */
fun installOutcome(status: Int): InstallOutcome = when (status) {
    PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallOutcome.CONFIRM
    PackageInstaller.STATUS_SUCCESS -> InstallOutcome.DONE
    else -> InstallOutcome.FAILED
}

/** Receives the result of the update session: shows Android's confirmation, or says it failed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (installOutcome(intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE))) {
            InstallOutcome.CONFIRM -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            InstallOutcome.DONE -> Unit
            InstallOutcome.FAILED -> Toast.makeText(
                context,
                "Das Update konnte nicht installiert werden.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
