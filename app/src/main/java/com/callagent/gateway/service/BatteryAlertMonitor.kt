package com.callagent.gateway.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.callagent.gateway.R

/**
 * Watches external power and battery level and raises a short text alert when
 * the phone loses external power or the charge falls below a configured level.
 * The alert text is handed to [onAlert], which the service relays to the
 * registered softphone as a SIP MESSAGE — an early warning for an unattended
 * gateway whose charger or cable may fail.
 *
 * Device-independent: unlike [BatteryChargeGuard] (Pixel-only sysfs) this reads
 * the standard Android battery broadcast, so it works on the S4 (serrano3g).
 *
 * The SIP MESSAGE only reaches the softphone while the phone still has a network
 * path; a full site outage that also kills the router cannot be reported.  What
 * it does catch is the common case — the phone's own power going away while the
 * network stays up.
 */
class BatteryAlertMonitor(
    private val context: Context,
    private val onAlert: (String) -> Unit
) {
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    private var lastPlugged: Boolean? = null
    private var alertedPowerLost = false
    private var alertedLow = false
    private var alertedCritical = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) handle(intent)
        }
    }

    /** Registering the sticky battery broadcast delivers the current state at once. */
    fun start() {
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not register battery receiver: ${e.message}")
        }
    }

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
            // Not registered — nothing to do.
        }
    }

    private fun handle(intent: Intent) {
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        if (scale <= 0 || raw < 0) return
        val level = raw * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

        val prev = lastPlugged
        lastPlugged = plugged

        val threshold = prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD).coerceIn(5, 95)

        // The first reading only establishes a baseline; alerts fire on changes
        // from here on.  Pre-arm the low/critical flags so a phone that is
        // already discharging below the level at startup does not fire a stale
        // warning for a state that did not just happen.
        if (prev == null) {
            if (!plugged && level <= threshold) alertedLow = true
            if (!plugged && level <= CRITICAL) alertedCritical = true
            return
        }

        if (!prefs.getBoolean(KEY_ENABLED, false)) return

        // Power restored: re-arm for the next outage and send one all-clear.
        if (!prev && plugged) {
            val wasAlerted = alertedPowerLost || alertedLow || alertedCritical
            alertedPowerLost = false
            alertedLow = false
            alertedCritical = false
            if (wasAlerted) onAlert(context.getString(R.string.battery_alert_restored, level))
            return
        }

        // Power lost — the warning that buys the most time.
        if (prev && !plugged && !alertedPowerLost) {
            alertedPowerLost = true
            onAlert(context.getString(R.string.battery_alert_power_lost, level))
        }

        if (plugged) return

        if (level <= CRITICAL && !alertedCritical) {
            alertedCritical = true
            onAlert(context.getString(R.string.battery_alert_critical, level))
        } else if (level <= threshold && !alertedLow) {
            alertedLow = true
            onAlert(context.getString(R.string.battery_alert_low, level, threshold))
        }
    }

    companion object {
        private const val TAG = "BatteryAlertMonitor"
        private const val CRITICAL = 5
        const val DEFAULT_THRESHOLD = 20
        const val KEY_ENABLED = "battery_alert"
        const val KEY_THRESHOLD = "battery_alert_threshold"
    }
}
