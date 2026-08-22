package com.example.adskip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var debugOverlayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        enableButton = findViewById(R.id.enableButton)
        debugOverlayButton = findViewById(R.id.debugOverlayButton)

        enableButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Only present in debug builds — release variant of DebugOverlayFactory
        // reports isDebugBuild=false and never requests SYSTEM_ALERT_WINDOW.
        if (DebugOverlayFactory.isDebugBuild) {
            debugOverlayButton.visibility = View.VISIBLE
            debugOverlayButton.setOnClickListener { requestOverlayPermissionIfNeeded() }
        } else {
            debugOverlayButton.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateDebugOverlayButton()
    }

    private fun updateStatus() {
        val enabled = isAccessibilityServiceEnabled()
        statusText.text = if (enabled) {
            getString(R.string.status_enabled)
        } else {
            getString(R.string.status_disabled)
        }
        enableButton.text = if (enabled) {
            getString(R.string.button_manage)
        } else {
            getString(R.string.button_enable)
        }
    }

    private fun updateDebugOverlayButton() {
        if (!DebugOverlayFactory.isDebugBuild) return
        val granted = Settings.canDrawOverlays(this)
        debugOverlayButton.text = if (granted) {
            getString(R.string.button_debug_overlay_on)
        } else {
            getString(R.string.button_debug_overlay_off)
        }
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                getString(R.string.toast_debug_overlay_granted),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${YoutubeAdSkipService::class.java.canonicalName}"
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
