package ru.stonefree.vkwg.config

import android.content.Context
import androidx.core.content.edit
import ru.stonefree.vkwg.turn.TurnFreeServiceState

class TurnFreePreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): TurnFreeProfile {
        return TurnFreeProfile(
            profileName = prefs.getString(KEY_PROFILE_NAME, "").orEmpty(),
            peer = prefs.getString(KEY_PEER, "").orEmpty(),
            turn = prefs.getString(KEY_TURN, "").orEmpty(),
            listen = prefs.getString(KEY_LISTEN, "127.0.0.1:9000").orEmpty().ifBlank { "127.0.0.1:9000" },
            turnHostOverride = prefs.getString(KEY_TURN_HOST_OVERRIDE, "").orEmpty(),
            turnPortOverride = prefs.getString(KEY_TURN_PORT_OVERRIDE, "").orEmpty(),
            streams = prefs.getInt(KEY_STREAMS, 2).coerceIn(1, 12),
            udp = prefs.getBoolean(KEY_UDP, true),
            noDtls = prefs.getBoolean(KEY_NO_DTLS, false),
            manualCaptcha = prefs.getBoolean(KEY_MANUAL_CAPTCHA, false),
            hasAmneziaWg = prefs.getBoolean(KEY_HAS_AMNEZIA_WG, false),
            wireGuardConfigText = prefs.getString(KEY_WG_TEXT, "").orEmpty(),
            importLabel = prefs.getString(KEY_IMPORT_LABEL, "").orEmpty(),
        )
    }

    fun save(profile: TurnFreeProfile) {
        prefs.edit(commit = true) {
            putString(KEY_PROFILE_NAME, profile.profileName)
            putString(KEY_PEER, profile.peer)
            putString(KEY_TURN, profile.turn)
            putString(KEY_LISTEN, profile.listen)
            putString(KEY_TURN_HOST_OVERRIDE, profile.turnHostOverride)
            putString(KEY_TURN_PORT_OVERRIDE, profile.turnPortOverride)
            putInt(KEY_STREAMS, profile.streams.coerceIn(1, 12))
            putBoolean(KEY_UDP, profile.udp)
            putBoolean(KEY_NO_DTLS, profile.noDtls)
            putBoolean(KEY_MANUAL_CAPTCHA, profile.manualCaptcha)
            putBoolean(KEY_HAS_AMNEZIA_WG, profile.hasAmneziaWg)
            putString(KEY_WG_TEXT, profile.wireGuardConfigText)
            putString(KEY_IMPORT_LABEL, profile.importLabel)
        }
    }

    fun loadServiceState(): TurnFreeServiceState {
        return runCatching {
            TurnFreeServiceState.valueOf(prefs.getString(KEY_SERVICE_STATE, TurnFreeServiceState.Idle.name).orEmpty())
        }.getOrDefault(TurnFreeServiceState.Idle)
    }

    fun saveServiceState(state: TurnFreeServiceState) {
        prefs.edit(commit = true) {
            putString(KEY_SERVICE_STATE, state.name)
        }
    }

    fun hasRequestedNotificationPermission(): Boolean {
        return prefs.getBoolean(KEY_REQUESTED_NOTIFICATION_PERMISSION, false)
    }

    fun markNotificationPermissionRequested() {
        prefs.edit(commit = true) {
            putBoolean(KEY_REQUESTED_NOTIFICATION_PERMISSION, true)
        }
    }

    fun hasRequestedBatteryOptimizationPermission(): Boolean {
        return prefs.getBoolean(KEY_REQUESTED_BATTERY_OPTIMIZATION_PERMISSION, false)
    }

    fun markBatteryOptimizationPermissionRequested() {
        prefs.edit(commit = true) {
            putBoolean(KEY_REQUESTED_BATTERY_OPTIMIZATION_PERMISSION, true)
        }
    }

    private companion object {
        const val PREFS_NAME = "turnfree_prefs"
        const val KEY_PROFILE_NAME = "profile_name"
        const val KEY_PEER = "peer"
        const val KEY_TURN = "turn"
        const val KEY_LISTEN = "listen"
        const val KEY_TURN_HOST_OVERRIDE = "turn_host_override"
        const val KEY_TURN_PORT_OVERRIDE = "turn_port_override"
        const val KEY_STREAMS = "streams"
        const val KEY_UDP = "udp"
        const val KEY_NO_DTLS = "no_dtls"
        const val KEY_MANUAL_CAPTCHA = "manual_captcha"
        const val KEY_HAS_AMNEZIA_WG = "has_amnezia_wg"
        const val KEY_WG_TEXT = "wg_text"
        const val KEY_IMPORT_LABEL = "import_label"
        const val KEY_SERVICE_STATE = "service_state"
        const val KEY_REQUESTED_NOTIFICATION_PERMISSION = "requested_notification_permission"
        const val KEY_REQUESTED_BATTERY_OPTIMIZATION_PERMISSION = "requested_battery_optimization_permission"
    }
}
