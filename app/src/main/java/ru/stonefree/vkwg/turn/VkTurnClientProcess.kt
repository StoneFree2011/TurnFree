package ru.stonefree.vkwg.turn

import android.content.Context
import android.util.Log
import ru.stonefree.vkwg.R
import ru.stonefree.vkwg.config.TurnFreeProfile
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

class VkTurnClientProcess(
    context: Context,
    private val onLogLine: (String) -> Unit,
    private val onEstablished: () -> Unit,
    private val onExited: (exitCode: Int?, reason: String) -> Unit,
) {

    private val appContext = context.applicationContext
    private val binaryFile = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopRequested = false

    private val establishedReported = AtomicBoolean(false)

    fun start(profile: TurnFreeProfile) {
        if (process?.isAlive == true) {
            throw IllegalStateException("DTLS-клиент уже запущен")
        }

        onLogLine("vk-turn-proxy package: $VK_TURN_PROXY_PACKAGE")

        val executable = resolveExecutable()
        val command = buildCommand(executable, profile)

        stopRequested = false
        establishedReported.set(false)

        onLogLine("DTLS command: ${command.joinToString(" ")}")

        val spawnedProcess = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                environment()["VK_PROFILE_PATH"] = File(
                    appContext.filesDir,
                    VK_PROFILE_FILE_NAME,
                ).absolutePath
                directory(appContext.filesDir)
            }
            .start()

        process = spawnedProcess

        thread(name = "VkTurnClientProcess", isDaemon = true) {
            val reason = runCatching {
                BufferedReader(InputStreamReader(spawnedProcess.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val cleanLine = line.orEmpty().trimEnd()
                        if (cleanLine.isNotBlank()) {
                            onLogLine(cleanLine)
                        }
                        if (
                            ESTABLISHED_REGEX.containsMatchIn(cleanLine) &&
                            establishedReported.compareAndSet(false, true)
                        ) {
                            onEstablished()
                        }
                    }
                }
                val exitCode = runCatching { spawnedProcess.waitFor() }.getOrNull()
                if (stopRequested) {
                    "stopped"
                } else {
                    onExited(exitCode, appContext.getString(R.string.status_turn_client_exit, exitCode ?: -1))
                    null
                }
            }.getOrElse { error ->
                if (stopRequested) {
                    "stopped"
                } else {
                    val exitCode = runCatching { spawnedProcess.exitValue() }.getOrNull()
                    val message = error.message?.takeIf { it.isNotBlank() } ?: appContext.getString(R.string.status_unknown_error)
                    onExited(exitCode, message)
                    message
                }
            }

            Log.i(TAG, "DTLS process thread ended: $reason")
        }
    }

    fun stop() {
        stopRequested = true
        process?.let { spawnedProcess ->
            runCatching { spawnedProcess.destroy() }
            if (spawnedProcess.isAlive) {
                runCatching { spawnedProcess.destroyForcibly() }
            }
        }
        process = null
    }

    fun isRunning(): Boolean {
        return process?.isAlive == true
    }

    private fun resolveExecutable(): String {
        if (!binaryFile.exists()) {
            throw IllegalStateException(appContext.getString(R.string.status_turn_binary_missing))
        }

        return binaryFile.absolutePath
    }

    private fun buildCommand(executable: String, profile: TurnFreeProfile): List<String> {
        val turnLink = profile.turn.trim()
        val isYandex = turnLink.contains("yandex", ignoreCase = true) ||
            turnLink.contains("telemost", ignoreCase = true)
        val peerArgument = TurnFreeDnsResolver.resolvePeerAddress(profile.peer.trim())

        if (peerArgument != profile.peer.trim()) {
            onLogLine("Resolved peer: ${profile.peer.trim()} -> $peerArgument")
        }

        return buildList {
            add(executable)
            add("-peer")
            add(peerArgument)
            add(if (isYandex) "-yandex-link" else "-vk-link")
            add(turnLink)
            add("-listen")
            add(profile.listen.ifBlank { DEFAULT_LISTEN })
            if (profile.turnHostOverride.isNotBlank()) {
                add("-turn")
                add(profile.turnHostOverride.trim())
            }
            if (profile.turnPortOverride.isNotBlank()) {
                add("-port")
                add(profile.turnPortOverride.trim())
            }
            if (profile.streams > 0) {
                add("-n")
                add(profile.streams.coerceIn(1, 12).toString())
            }
            if (profile.udp) {
                add("-udp")
            }
            if (profile.noDtls) {
                add("-no-dtls")
            }
        }
    }

    companion object {
        private const val TAG = "VkTurnClientProcess"
        private const val VK_TURN_PROXY_PACKAGE =
            "github.com/cacggghp/vk-turn-proxy@7edb014baef8 (embedded from working Android client)"
        private const val BINARY_NAME = "libvkturn.so"
        private const val VK_PROFILE_FILE_NAME = "vk_profile.json"
        private const val DEFAULT_LISTEN = "127.0.0.1:9000"
        private val ESTABLISHED_REGEX = Regex("""(?:\[STREAM \d+\]\s*)?Established DTLS connection!?""")
    }
}
