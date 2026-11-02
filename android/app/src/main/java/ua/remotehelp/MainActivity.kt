package ua.remotehelp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import ua.remotehelp.SessionState.Phase
import ua.remotehelp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val projectionConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                SessionState.set(SessionState.State(Phase.CONNECTING))
                ScreenShareService.start(this, res.resultCode, data)
            } else {
                SessionState.set(SessionState.State(Phase.IDLE))
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askScreenCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.mainBtn.setOnClickListener {
            if (SessionState.state.value.phase == Phase.IDLE) startHelp() else ScreenShareService.stop(this)
        }
        b.a11yBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        b.settingsBtn.setOnClickListener { showSettings() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SessionState.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        b.a11yCard.visibility = if (ControlService.isEnabled(this)) View.GONE else View.VISIBLE
    }

    private fun render(s: SessionState.State) {
        val idle = s.phase == Phase.IDLE
        b.mainBtn.setText(if (idle) R.string.btn_get_help else R.string.btn_stop)
        b.mainBtn.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (idle) R.color.go else R.color.stop)
        )

        b.statusText.text = when (s.phase) {
            Phase.IDLE -> s.error ?: getString(R.string.idle_hint)
            Phase.CONNECTING -> getString(R.string.connecting)
            Phase.WAITING -> getString(R.string.your_code)
            Phase.CONNECTED -> getString(R.string.helper_connected)
            Phase.RECONNECTING -> getString(R.string.reconnecting)
        }
        val showCode = s.code != null && (s.phase == Phase.WAITING || s.phase == Phase.CONNECTED || s.phase == Phase.RECONNECTING)
        b.codeText.visibility = if (showCode) View.VISIBLE else View.GONE
        b.codeText.text = formatCode(s.code)
        b.hintText.visibility = if (s.phase == Phase.WAITING) View.VISIBLE else View.GONE
        b.hintText.setText(R.string.tell_code)
    }

    private fun startHelp() {
        if (!Prefs.isServerConfigured(this)) {
            Toast.makeText(this, R.string.server_not_set, Toast.LENGTH_LONG).show()
            showSettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askScreenCapture()
        }
    }

    private fun askScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Force "entire screen" — sharing a single app is useless for helping with settings.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        SessionState.set(SessionState.State(Phase.CONNECTING))
        projectionConsent.launch(intent)
    }

    private fun showSettings() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Prefs.serverUrl(this@MainActivity).takeUnless { it.contains("CHANGE-ME") } ?: "https://")
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.server_url)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                Prefs.setServerUrl(this, input.text.toString())
                SessionState.update { it.copy(error = null) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
