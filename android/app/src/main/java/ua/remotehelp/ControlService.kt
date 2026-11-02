package ua.remotehelp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Accessibility service that lets the helper act on the phone: tap, swipe,
 * long press, Back/Home/Recents, and type into the focused text field.
 *
 * It does nothing on its own — it only executes commands that arrive through an
 * active [ScreenShareService] session that the phone owner started themselves.
 */
class ControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ControlService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(ctx, ControlService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var messageView: View? = null
    private val hideMessage = Runnable { removeMessage() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeMessage()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ------------------------------------------------------------------ gestures

    private fun toPx(nx: Double, ny: Double): Pair<Float, Float> {
        val size = realScreenSize(this)
        val x = (nx.coerceIn(0.0, 1.0) * (size.x - 1)).toFloat()
        val y = (ny.coerceIn(0.0, 1.0) * (size.y - 1)).toFloat()
        return x to y
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 10_000))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tap(nx: Double, ny: Double): Boolean {
        val (x, y) = toPx(nx, ny)
        return dispatch(Path().apply { moveTo(x, y) }, 60)
    }

    fun longPress(nx: Double, ny: Double): Boolean {
        val (x, y) = toPx(nx, ny)
        return dispatch(Path().apply { moveTo(x, y) }, 900)
    }

    /** [points] are normalized (0..1) coordinates. */
    fun swipe(points: List<Pair<Double, Double>>, durationMs: Long): Boolean {
        if (points.isEmpty()) return false
        if (points.size == 1) return tap(points[0].first, points[0].second)
        val path = Path()
        points.forEachIndexed { i, (nx, ny) ->
            val (x, y) = toPx(nx, ny)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return dispatch(path, durationMs)
    }

    /** Mouse-wheel scroll: dx/dy are fractions of the screen; positive dy = scroll down. */
    fun scroll(nx: Double, ny: Double, dx: Double, dy: Double): Boolean {
        fun span(start: Double, delta: Double): Pair<Double, Double> {
            var s = start
            var e = start - delta
            // Keep the whole stroke on-screen and away from the system edges.
            if (e < 0.08) { s += 0.08 - e; e = 0.08 }
            if (e > 0.92) { s -= e - 0.92; e = 0.92 }
            return s.coerceIn(0.08, 0.92) to e.coerceIn(0.08, 0.92)
        }
        val (sy, ey) = span(ny, dy)
        val (sx, ex) = span(nx, dx)
        return swipe(listOf(sx to sy, ex to ey), 350)
    }

    fun global(action: String): Boolean {
        val id = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            else -> return false
        }
        return performGlobalAction(id)
    }

    // ------------------------------------------------------------------ text

    private fun focusedInput(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun currentText(node: AccessibilityNodeInfo): String {
        if (node.isShowingHintText) return ""
        return node.text?.toString() ?: ""
    }

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val c = cursor.coerceIn(0, text.length)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, c)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, c)
            })
        }
        return ok
    }

    /** Returns current text and a sane (start, end) selection inside it. */
    private fun textAndSelection(node: AccessibilityNodeInfo): Triple<String, Int, Int> {
        val current = currentText(node)
        var s = node.textSelectionStart
        var e = node.textSelectionEnd
        if (s < 0 || e < 0 || s > current.length || e > current.length) {
            s = current.length; e = current.length
        }
        if (s > e) { val t = s; s = e; e = t }
        return Triple(current, s, e)
    }

    /** Inserts [text] at the cursor of the focused field. False if no field is focused. */
    fun inputText(text: String): Boolean {
        val node = focusedInput() ?: return false
        if (node.isPassword) {
            // Masked text can't be read back, so build on what we typed ourselves.
            val newText = passwordBase(node) + text
            val ok = setText(node, newText, newText.length)
            if (ok) passwordShadow = newText
            return ok
        }
        val (current, s, e) = textAndSelection(node)
        val newText = current.substring(0, s) + text + current.substring(e)
        return setText(node, newText, s + text.length)
    }

    // For password fields we keep a shadow copy of what *we* typed so appends work.
    private var passwordShadow = ""

    private fun passwordBase(node: AccessibilityNodeInfo): String {
        val len = if (node.isShowingHintText) 0 else node.text?.length ?: 0
        return if (len == passwordShadow.length) passwordShadow else ""
    }

    fun key(key: String): Boolean {
        val node = focusedInput() ?: return false
        return when (key) {
            "backspace" -> {
                if (node.isPassword) {
                    val buf = passwordBase(node).dropLast(1)
                    passwordShadow = buf
                    return setText(node, buf, buf.length)
                }
                val (current, s, e) = textAndSelection(node)
                if (s != e) {
                    setText(node, current.substring(0, s) + current.substring(e), s)
                } else if (s > 0) {
                    val cut = Character.charCount(current.codePointBefore(s))
                    setText(node, current.substring(0, s - cut) + current.substring(s), s - cut)
                } else true
            }
            "clear" -> {
                passwordShadow = ""
                setText(node, "", 0)
            }
            "enter" -> {
                if (Build.VERSION.SDK_INT >= 30 &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }
                ) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else if (node.isMultiLine) {
                    inputText("\n")
                } else false
            }
            else -> false
        }
    }

    // ------------------------------------------------------------------ messages

    /** Shows a big banner with a message from the helper on top of everything. */
    fun showMessage(text: String) {
        removeMessage()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.overlay_bg)
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        box.addView(TextView(this).apply {
            setText(R.string.msg_from_helper)
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 15f
        })
        box.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 24f
            setPadding(0, dp(6), 0, dp(6))
        })
        box.addView(Button(this).apply {
            setText(R.string.ok)
            textSize = 18f
            setOnClickListener { removeMessage() }
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            x = 0
            y = dp(48)
            horizontalMargin = 0.03f
        }
        try {
            wm.addView(box, lp)
            messageView = box
            main.postDelayed(hideMessage, 20_000)
        } catch (_: Exception) {
            messageView = null
        }
    }

    private fun removeMessage() {
        main.removeCallbacks(hideMessage)
        val v = messageView ?: return
        messageView = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }
}
