package ru.stonefree.vkwg.turn

import android.content.Context
import android.content.Intent
import ru.stonefree.vkwg.config.TurnFreeProfile

object TurnFreeServiceContract {
    const val ACTION_START = "ru.stonefree.vkwg.turn.action.START"
    const val ACTION_STOP = "ru.stonefree.vkwg.turn.action.STOP"
    const val ACTION_RETRY = "ru.stonefree.vkwg.turn.action.RETRY"
    const val ACTION_STATE_CHANGED = "ru.stonefree.vkwg.turn.action.STATE_CHANGED"
    const val ACTION_DTLS_ESTABLISHED = "ru.stonefree.vkwg.turn.action.DTLS_ESTABLISHED"
    const val ACTION_WIREGUARD_START_REQUESTED = "ru.stonefree.vkwg.turn.action.WIREGUARD_START_REQUESTED"
    const val ACTION_MANUAL_CAPTCHA_REQUIRED = "ru.stonefree.vkwg.turn.action.MANUAL_CAPTCHA_REQUIRED"
    const val ACTION_MANUAL_CAPTCHA_RESOLVED = "ru.stonefree.vkwg.turn.action.MANUAL_CAPTCHA_RESOLVED"

    const val EXTRA_STATE = "extra_state"
    const val EXTRA_MESSAGE = "extra_message"
    const val EXTRA_CAPTCHA_SESSION_ID = "extra_captcha_session_id"
    const val EXTRA_PROFILE_NAME = "extra_profile_name"
    const val EXTRA_URL = "extra_url"
    const val EXTRA_PEER = "extra_peer"
    const val EXTRA_TURN = "extra_turn"
    const val EXTRA_LISTEN = "extra_listen"
    const val EXTRA_TURN_HOST_OVERRIDE = "extra_turn_host_override"
    const val EXTRA_TURN_PORT_OVERRIDE = "extra_turn_port_override"
    const val EXTRA_STREAMS = "extra_streams"
    const val EXTRA_UDP = "extra_udp"
    const val EXTRA_NO_DTLS = "extra_no_dtls"
    const val EXTRA_WG_TEXT = "extra_wg_text"
    const val EXTRA_WG_HAS_AMNEZIA = "extra_wg_has_amnezia"

    fun createStartIntent(context: Context, profile: TurnFreeProfile): Intent {
        return Intent(context, VkTurnService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_PROFILE_NAME, profile.profileName)
            putExtra(EXTRA_PEER, profile.peer)
            putExtra(EXTRA_TURN, profile.turn)
            putExtra(EXTRA_LISTEN, profile.listen)
            putExtra(EXTRA_TURN_HOST_OVERRIDE, profile.turnHostOverride)
            putExtra(EXTRA_TURN_PORT_OVERRIDE, profile.turnPortOverride)
            putExtra(EXTRA_STREAMS, profile.streams)
            putExtra(EXTRA_UDP, profile.udp)
            putExtra(EXTRA_NO_DTLS, profile.noDtls)
            putExtra(EXTRA_WG_TEXT, profile.wireGuardConfigText)
            putExtra(EXTRA_WG_HAS_AMNEZIA, profile.hasAmneziaWg)
        }
    }

    fun createStopIntent(context: Context): Intent {
        return Intent(context, VkTurnService::class.java).apply {
            action = ACTION_STOP
        }
    }

    fun createRetryIntent(context: Context): Intent {
        return Intent(context, VkTurnService::class.java).apply {
            action = ACTION_RETRY
        }
    }
}
