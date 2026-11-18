package com.example.system

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Automates Do Not Disturb (DND) during active Audio and Video recordings
 * (both in-app and 1x1 widget) and restores the previous DND filter when stopped.
 *
 * Uses Android's standard NotificationManager policy APIs (no prohibited permissions).
 * On Android 15+ (API 35+), setInterruptionFilter toggles an app-scoped implicit
 * AutomaticZenRule without clobbering user-defined system schedules.
 */
class DndController(private val context: Context) {

    private val lock = Any()

    @Volatile
    private var dndActivatedByApp: Boolean = false

    @Volatile
    private var previousInterruptionFilter: Int? = null

    fun hasPolicyAccess(): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            nm.isNotificationPolicyAccessGranted
        } catch (e: Exception) {
            false
        }
    }

    fun isDndActiveByApp(): Boolean = dndActivatedByApp

    fun enableDndForRecording(): Boolean {
        synchronized(lock) {
            if (!hasPolicyAccess()) {
                return false
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            return try {
                if (!dndActivatedByApp) {
                    previousInterruptionFilter = nm.currentInterruptionFilter
                }
                // INTERRUPTION_FILTER_ALARMS suppresses all incoming phone call ringtones,
                // messages, and notifications while allowing alarms to continue.
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                dndActivatedByApp = true
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    fun restoreDndAfterRecording(): Boolean {
        synchronized(lock) {
            if (!dndActivatedByApp) {
                return false
            }
            val prevFilter = previousInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            dndActivatedByApp = false
            previousInterruptionFilter = null

            if (!hasPolicyAccess()) {
                return false
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    // On Android 15+, setting INTERRUPTION_FILTER_ALL deactivates only this app's
                    // implicit AutomaticZenRule while keeping user rules intact.
                    val target = if (prevFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    } else {
                        prevFilter
                    }
                    nm.setInterruptionFilter(target)
                } else {
                    nm.setInterruptionFilter(prevFilter)
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    companion object {
        fun createPolicyAccessSettingsIntent(): Intent {
            return Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
