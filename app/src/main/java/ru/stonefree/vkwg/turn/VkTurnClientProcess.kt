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
                            cleanLine.contains(ESTABLISHED_MARKER) &&
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

        return buildList {
            add(executable)
            add("-peer")
            add(profile.peer.trim())
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
            if (profile.manualCaptcha) {
                add("-manual-captcha")
            }
        }
    }

    companion object {
        private const val TAG = "VkTurnClientProcess"
        private const val VK_TURN_PROXY_PACKAGE = "github.com/cacggghp/vk-turn-proxy@1.8.3"
        private const val BINARY_NAME = "libvkturn.so"
        private const val DEFAULT_LISTEN = "127.0.0.1:9000"
        private const val ESTABLISHED_MARKER = "Established DTLS connection!"
    }
}
