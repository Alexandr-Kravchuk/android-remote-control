package ua.remotehelp

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

object Prefs {
    private const val FILE = "remote_help"
    private const val KEY_SERVER = "server_url"

    fun serverUrl(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SERVER, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_SERVER_URL

    fun setServerUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVER, url.trim().trimEnd('/'))
            .apply()
    }

    fun isServerConfigured(ctx: Context): Boolean {
        val u = serverUrl(ctx)
        return u.startsWith("http") && !u.contains("CHANGE-ME")
    }

    /** https://host → wss://host/ws/device */
    fun deviceSocketUrl(ctx: Context): String {
        val base = serverUrl(ctx).trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> "wss://$base"
        }
        return "$ws/ws/device"
    }
}

/** Physical screen size in pixels for the current rotation. */
@Suppress("DEPRECATION")
fun realScreenSize(ctx: Context): Point {
    val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val p = Point()
    dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
    return p
}

@Suppress("DEPRECATION")
fun realDensityDpi(ctx: Context): Int {
    val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val metrics = android.util.DisplayMetrics()
    dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
    return metrics.densityDpi
}
