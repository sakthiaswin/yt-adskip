package com.example.adskip

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var batteryOptimizeButton: Button
    private lateinit var debugOverlayButton: Button
    private lateinit var overlayToggleSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        enableButton = findViewById(R.id.enableButton)
        batteryOptimizeButton = findViewById(R.id.batteryOptimizeButton)
        debugOverlayButton = findViewById(R.id.debugOverlayButton)
        overlayToggleSwitch = findViewById(R.id.overlayToggleSwitch)

        enableButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        batteryOptimizeButton.setOnClickListener { requestBatteryExemptionIfNeeded() }

        // Only present in debug builds — release variant of DebugOverlayFactory
        // reports isDebugBuild=false and never requests SYSTEM_ALERT_WINDOW.
        if (DebugOverlayFactory.isDebugBuild) {
            debugOverlayButton.visibility = View.VISIBLE
            debugOverlayButton.setOnClickListener { requestOverlayPermissionIfNeeded() }

            overlayToggleSwitch.visibility = View.VISIBLE
            overlayToggleSwitch.isChecked = DebugPrefs.isOverlayEnabled(this)
            overlayToggleSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                DebugPrefs.setOverlayEnabled(this, checked)
                Toast.makeText(
                    this,
                    if (checked) getString(R.string.toast_overlay_started)
                    else getString(R.string.toast_overlay_stopped),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            debugOverlayButton.visibility = View.GONE
            overlayToggleSwitch.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateBatteryOptimizeButton()
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

    /**
     * On several OEM skins (Samsung One UI in particular — see the header
     * comment in YoutubeAdSkipService.kt) an app that isn't exempted from
     * battery/background restrictions can have its window-content IPC
     * calls silently starved while backgrounded: the accessibility service
     * stays bound and keeps receiving events, but rootInActiveWindow() and
     * getWindows() return nothing for as long as the restriction applies.
     * This is exactly the "root: unavailable" symptom the debug overlay
     * reports. Requesting this exemption is the actual fix for that case.
     */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryOptimizeButton() {
        val exempted = isIgnoringBatteryOptimizations()
        batteryOptimizeButton.text = if (exempted) {
            getString(R.string.button_battery_optimize_on)
        } else {
            getString(R.string.button_battery_optimize_off)
        }
        batteryOptimizeButton.isEnabled = !exempted
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemptionIfNeeded() {
        if (isIgnoringBatteryOptimizations()) {
            updateBatteryOptimizeButton()
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
        )
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
