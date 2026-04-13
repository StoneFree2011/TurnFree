package ru.stonefree.vkwg.turn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.stonefree.vkwg.config.TurnFreeProfile
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class TurnFreeWireGuardController(
    context: Context,
    private val onTunnelStateChanged: (Tunnel.State) -> Unit = {},
) {

    private val appContext = context.applicationContext
    private val backend = GoBackend(appContext)

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            lastState = newState
            onTunnelStateChanged(newState)
        }
    }

    @Volatile
    private var lastState: Tunnel.State = Tunnel.State.DOWN

    suspend fun start(profile: TurnFreeProfile): Tunnel.State = withContext(Dispatchers.IO) {
        val configText = ensureExcludedApplication(profile.wireGuardConfigText)
        val config = Config.parse(
            ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)),
        )
        lastState = backend.setState(tunnel, Tunnel.State.UP, config)
        lastState
    }

    suspend fun stop(): Tunnel.State = withContext(Dispatchers.IO) {
        lastState = backend.setState(tunnel, Tunnel.State.DOWN, null)
        lastState
    }

    fun isRunning(): Boolean {
        return lastState == Tunnel.State.UP
    }

    private fun ensureExcludedApplication(configText: String): String {
        val packageName = appContext.packageName
        if (configText.contains("ExcludedApplications", ignoreCase = true) &&
            configText.contains(packageName, ignoreCase = true)
        ) {
            return configText
        }

        val excludedLine = "ExcludedApplications = $packageName"
        val lines = configText.lineSequence().toMutableList()
        if (lines.isEmpty()) {
            error("WireGuard-конфиг пустой")
        }

        val result = mutableListOf<String>()
        var inserted = false

        lines.forEach { line ->
            if (!inserted && line.trim().equals("[Peer]", ignoreCase = true)) {
                result += excludedLine
                inserted = true
            }
            result += line
        }

        if (!inserted) {
            result += excludedLine
        }

        return result.joinToString(separator = "\n").trim()
    }

    companion object {
        private const val TUNNEL_NAME = "turnfree"
    }
}
