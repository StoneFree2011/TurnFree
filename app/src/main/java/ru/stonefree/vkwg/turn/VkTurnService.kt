package ru.stonefree.vkwg.turn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.stonefree.vkwg.MainActivity
import ru.stonefree.vkwg.R
import ru.stonefree.vkwg.config.TurnFreeProfile
import ru.stonefree.vkwg.config.TurnFreePreferences
import kotlin.math.min

class VkTurnService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val wireGuardController by lazy {
        TurnFreeWireGuardController(applicationContext, ::onWireGuardStateChanged)
    }
    private val turnClient by lazy {
        VkTurnClientProcess(
            context = applicationContext,
            onLogLine = ::onTurnLogLine,
            onEstablished = ::onDtlsEstablished,
            onExited = ::onTurnClientExited,
        )
    }
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }

    @Volatile
    private var currentState = TurnFreeServiceState.Idle

    @Volatile
    private var currentProfile: TurnFreeProfile = TurnFreeProfile()

    @Volatile
    private var stopRequested = false

    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var reconnectDelayMs = 0L

    @Volatile
    private var lastErrorMessage: String = ""

    @Volatile
    private var captchaGeneration = 0L

    @Volatile
    private var pendingCaptchaUrl: String = ""

    @Volatile
    private var pendingCaptchaSessionId = 0L

    private val servicePreferences by lazy { TurnFreePreferences(applicationContext) }

    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var healthMonitorJob: Job? = null
    private var captchaFallbackJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        Log.i(TAG, "VkTurnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TurnFreeServiceContract.ACTION_START -> startTurn(intent)
            TurnFreeServiceContract.ACTION_STOP -> stopTurn("Stop intent received")
            TurnFreeServiceContract.ACTION_RETRY -> retryTurn("Retry intent received")
            null -> Log.w(TAG, "onStartCommand called without action")
            else -> Log.w(TAG, "Unknown action=${intent.action}")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        cancelReconnectWork()
        cancelHealthMonitor()
        releaseWakeLock()
        runCatching { turnClient.stop() }
        updateState(TurnFreeServiceState.Stopping, getString(R.string.status_service_destroyed))
        serviceScope.cancel()
        notificationManager.cancel(NOTIFICATION_ID)
        Log.i(TAG, "VkTurnService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTurn(intent: Intent) {
        stopRequested = false
        cancelReconnectWork()
        resetReconnectState()
        currentProfile = TurnFreeProfile(
            profileName = intent.getStringExtra(TurnFreeServiceContract.EXTRA_PROFILE_NAME).orEmpty(),
            peer = intent.getStringExtra(TurnFreeServiceContract.EXTRA_PEER).orEmpty(),
            turn = intent.getStringExtra(TurnFreeServiceContract.EXTRA_TURN).orEmpty(),
            listen = intent.getStringExtra(TurnFreeServiceContract.EXTRA_LISTEN).orEmpty().ifBlank { "127.0.0.1:9000" },
            turnHostOverride = intent.getStringExtra(TurnFreeServiceContract.EXTRA_TURN_HOST_OVERRIDE).orEmpty(),
            turnPortOverride = intent.getStringExtra(TurnFreeServiceContract.EXTRA_TURN_PORT_OVERRIDE).orEmpty(),
            streams = intent.getIntExtra(TurnFreeServiceContract.EXTRA_STREAMS, 2).coerceIn(1, 12),
            udp = intent.getBooleanExtra(TurnFreeServiceContract.EXTRA_UDP, true),
            noDtls = intent.getBooleanExtra(TurnFreeServiceContract.EXTRA_NO_DTLS, false),
            hasAmneziaWg = intent.getBooleanExtra(TurnFreeServiceContract.EXTRA_WG_HAS_AMNEZIA, false),
            wireGuardConfigText = intent.getStringExtra(TurnFreeServiceContract.EXTRA_WG_TEXT).orEmpty(),
        )

        if (currentProfile.peer.isBlank() || currentProfile.turn.isBlank()) {
            startForegroundServiceInternal()
            updateState(
                TurnFreeServiceState.Failed,
                getString(R.string.status_turn_missing_fields),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        if (
            currentState == TurnFreeServiceState.Starting ||
            currentState == TurnFreeServiceState.Establishing ||
            currentState == TurnFreeServiceState.WireGuardStarting ||
            currentState == TurnFreeServiceState.WireGuardRunning ||
            currentState == TurnFreeServiceState.Reconnecting
        ) {
            Log.i(TAG, "Start ignored because service is already running")
            updateNotification(
                title = getString(R.string.notification_title_running),
                text = getString(R.string.status_turn_already_running),
            )
            return
        }

        startForegroundServiceInternal()
        acquireWakeLock()
        beginTurnSession(initialAttempt = true)
    }

    private fun stopTurn(reason: String) {
        Log.i(TAG, "Stopping VkTurnService: $reason")
        stopRequested = true
        cancelReconnectWork()
        cancelHealthMonitor()
        clearCaptchaFallback()
        serviceScope.launch {
            runCatching { wireGuardController.stop() }
            runCatching { turnClient.stop() }
            updateState(TurnFreeServiceState.Stopping, getString(R.string.status_turn_stopping))
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun onDtlsEstablished() {
        if (stopRequested) return
        cancelWatchdog()
        resetReconnectState()
        Log.i(TAG, "Established DTLS connection!")
        updateState(TurnFreeServiceState.Established, getString(R.string.status_turn_established))
        sendServiceBroadcast(
            action = TurnFreeServiceContract.ACTION_DTLS_ESTABLISHED,
            state = TurnFreeServiceState.Established,
            message = getString(R.string.status_turn_established),
        )
        serviceScope.launch { startWireGuardTunnel() }
    }

    private fun startForegroundServiceInternal() {
        val initialNotification = buildNotification(
            title = getString(R.string.notification_title_running),
            text = getString(R.string.status_turn_starting),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
    }

    private fun updateState(state: TurnFreeServiceState, message: String) {
        currentState = state
        lastErrorMessage = if (state == TurnFreeServiceState.Failed) message else ""
        servicePreferences.saveServiceState(state)
        if (state == TurnFreeServiceState.WireGuardRunning) {
            scheduleHealthMonitor()
        } else if (state != TurnFreeServiceState.Established) {
            cancelHealthMonitor()
        }
        sendServiceBroadcast(
            action = TurnFreeServiceContract.ACTION_STATE_CHANGED,
            state = state,
            message = message,
        )
        updateNotification(
            title = when (state) {
                TurnFreeServiceState.Established,
                TurnFreeServiceState.WireGuardRunning -> getString(R.string.notification_title_connected)
                TurnFreeServiceState.Reconnecting -> getString(R.string.notification_title_reconnecting)
                TurnFreeServiceState.Failed -> getString(R.string.notification_title_failed)
                else -> getString(R.string.notification_title_running)
            },
            text = message,
        )
    }

    private suspend fun startWireGuardTunnel() {
        if (stopRequested) return
        val profileSnapshot = currentProfile
        if (profileSnapshot.hasAmneziaWg) {
            failWithoutRetry(getString(R.string.status_wireguard_amnezia_not_supported))
            return
        }
        if (profileSnapshot.wireGuardConfigText.isBlank()) {
            failWithoutRetry(getString(R.string.status_wireguard_missing_config))
            return
        }

        updateState(TurnFreeServiceState.WireGuardStarting, getString(R.string.status_wireguard_starting))
        sendServiceBroadcast(
            action = TurnFreeServiceContract.ACTION_WIREGUARD_START_REQUESTED,
            state = TurnFreeServiceState.WireGuardStarting,
            message = getString(R.string.status_wireguard_starting),
        )

        try {
            val tunnelState = wireGuardController.start(profileSnapshot)
            if (stopRequested) {
                return
            }
            if (tunnelState == com.wireguard.android.backend.Tunnel.State.UP) {
                cancelReconnectWork()
                resetReconnectState()
                updateState(TurnFreeServiceState.WireGuardRunning, getString(R.string.status_wireguard_started))
                scheduleHealthMonitor()
                updateNotification(
                    title = getString(R.string.notification_title_connected),
                    text = getString(R.string.status_wireguard_started),
                )
            } else {
                scheduleReconnect(getString(R.string.status_wireguard_failed))
            }
        } catch (error: Exception) {
            if (stopRequested) {
                return
            }
            Log.e(TAG, "WireGuard start failed", error)
            scheduleReconnect(
                getString(
                    R.string.status_wireguard_failed_with_reason,
                    error.message ?: getString(R.string.status_unknown_error),
                ),
            )
        }
    }

    private fun beginTurnSession(initialAttempt: Boolean) {
        if (stopRequested) return
        if (initialAttempt) {
            updateState(TurnFreeServiceState.Starting, getString(R.string.status_turn_starting))
        } else {
            updateState(
                TurnFreeServiceState.Starting,
                getString(R.string.status_turn_retrying, reconnectAttempt, MAX_RECONNECT_ATTEMPTS),
            )
        }

        serviceScope.launch {
            if (stopRequested) return@launch
            updateState(TurnFreeServiceState.Establishing, buildEstablishingMessage(currentProfile))
            scheduleDtlsWatchdog()
            runCatching { turnClient.start(profileSnapshot()) }
                .onFailure { error ->
                    if (stopRequested) return@onFailure
                    Log.e(TAG, "DTLS client start failed", error)
                    failWithoutRetry(
                        error.message ?: getString(R.string.status_unknown_error),
                    )
                }
        }
    }

    private fun retryTurn(reason: String) {
        if (stopRequested) return
        Log.i(TAG, "Manual retry requested: $reason")
        cancelReconnectWork()
        resetReconnectState()
        serviceScope.launch {
            runCatching { wireGuardController.stop() }
            runCatching { turnClient.stop() }
            beginTurnSession(initialAttempt = true)
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (stopRequested) return
        clearCaptchaFallback()

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            failWithoutRetry(
                getString(
                    R.string.status_turn_reconnect_exhausted,
                    reason,
                    MAX_RECONNECT_ATTEMPTS,
                ),
            )
            return
        }

        reconnectAttempt += 1
        reconnectDelayMs = computeReconnectDelayMs(reconnectAttempt)
        val reconnectMessage = getString(
            R.string.status_turn_reconnecting,
            reconnectAttempt,
            MAX_RECONNECT_ATTEMPTS,
            reason,
            formatDelay(reconnectDelayMs),
        )
        updateState(TurnFreeServiceState.Reconnecting, reconnectMessage)

        cancelReconnectWork()
        reconnectJob = serviceScope.launch {
            delay(reconnectDelayMs)
            if (stopRequested || currentState != TurnFreeServiceState.Reconnecting) {
                return@launch
            }

            Log.i(TAG, "Reconnect attempt #$reconnectAttempt after ${reconnectDelayMs}ms")
            runCatching { wireGuardController.stop() }
            runCatching { turnClient.stop() }
            beginTurnSession(initialAttempt = false)
        }
    }

    private fun scheduleDtlsWatchdog(timeoutMs: Long = DTLS_ESTABLISH_TIMEOUT_MS) {
        cancelWatchdog()
        watchdogJob = serviceScope.launch {
            delay(timeoutMs)
            if (stopRequested || currentState != TurnFreeServiceState.Establishing) {
                return@launch
            }

            Log.w(TAG, "DTLS watchdog timeout")
            scheduleReconnect(getString(R.string.status_turn_watchdog_timeout))
        }
    }

    private fun onWireGuardStateChanged(newState: Tunnel.State) {
        if (newState == Tunnel.State.DOWN && !stopRequested) {
            Log.w(TAG, "WireGuard tunnel dropped unexpectedly")
            scheduleReconnect(getString(R.string.status_wireguard_dropped))
        }
    }

    private fun onTurnLogLine(line: String) {
        Log.i(TAG, "[vk-turn] $line")
        val lower = line.lowercase()
        if (
            lower.contains("failed") ||
            lower.contains("error") ||
            lower.contains("panic") ||
            lower.contains("captcha") ||
            lower.contains("challenge") ||
            lower.contains("turnstile") ||
            lower.contains("refresh permissions") ||
            lower.contains("manual captcha") ||
            lower.contains("slider")
        ) {
            lastErrorMessage = line
        }

        if (lower.contains("captcha_wait_required")) {
            scheduleCaptchaFallback(triggerNow = false)
        }

        if (lower.contains("fatal_captcha")) {
            scheduleCaptchaFallback(triggerNow = true)
        }

        if (lower.contains("open this url in your browser:")) {
            val url = extractUrl(line)
            if (!url.isNullOrBlank()) {
                scheduleCaptchaFallback(url = url, triggerNow = false)
            }
        }

        if (looksLikeCaptchaSolved(line)) {
            if (pendingCaptchaSessionId > 0L) {
                sendServiceBroadcast(
                    action = TurnFreeServiceContract.ACTION_MANUAL_CAPTCHA_RESOLVED,
                    state = currentState,
                    message = line,
                    captchaSessionId = pendingCaptchaSessionId,
                )
            }
            clearCaptchaFallback()
            lastErrorMessage = ""
        }
    }

    private fun onTurnClientExited(exitCode: Int?, reason: String) {
        if (stopRequested) return

        Log.w(TAG, "DTLS client exited: code=$exitCode reason=$reason")
        val detail = lastErrorMessage.ifBlank {
            reason.ifBlank { getString(R.string.status_turn_client_exit, exitCode ?: -1) }
        }
        scheduleReconnect(detail)
    }

    private fun failWithoutRetry(message: String) {
        if (stopRequested) return
        cancelReconnectWork()
        cancelHealthMonitor()
        clearCaptchaFallback()
        updateState(TurnFreeServiceState.Failed, message)
    }

    private fun resetReconnectState() {
        reconnectAttempt = 0
        reconnectDelayMs = 0L
        lastErrorMessage = ""
        clearCaptchaFallback()
        cancelReconnectWork()
    }

    private fun cancelReconnectWork() {
        reconnectJob?.cancel()
        reconnectJob = null
        cancelWatchdog()
    }

    private fun scheduleCaptchaFallback(url: String = "", triggerNow: Boolean) {
        if (stopRequested) return

        if (url.isNotBlank()) {
            pendingCaptchaUrl = url
            pendingCaptchaSessionId += 1
        } else if (pendingCaptchaUrl.isBlank()) {
            return
        }

        if (currentState == TurnFreeServiceState.Starting ||
            currentState == TurnFreeServiceState.Establishing ||
            currentState == TurnFreeServiceState.Reconnecting
        ) {
            scheduleDtlsWatchdog(CAPTCHA_DTLS_ESTABLISH_TIMEOUT_MS)
        }

        val generationAtSchedule = captchaGeneration
        captchaFallbackJob?.cancel()
        captchaFallbackJob = serviceScope.launch {
            if (!triggerNow) {
                delay(CAPTCHA_AUTO_WAIT_TIMEOUT_MS)
            }
            if (stopRequested) return@launch
            if (generationAtSchedule != captchaGeneration) return@launch
            if (currentState != TurnFreeServiceState.Starting &&
                currentState != TurnFreeServiceState.Establishing &&
                currentState != TurnFreeServiceState.Reconnecting
            ) {
                return@launch
            }

            val captchaUrl = pendingCaptchaUrl
            if (captchaUrl.isBlank()) return@launch

            sendServiceBroadcast(
                action = TurnFreeServiceContract.ACTION_MANUAL_CAPTCHA_REQUIRED,
                state = currentState,
                message = getString(R.string.status_manual_captcha_required),
                extraUrl = captchaUrl,
                captchaSessionId = pendingCaptchaSessionId,
            )
        }
    }

    private fun clearCaptchaFallback() {
        captchaFallbackJob?.cancel()
        captchaFallbackJob = null
        pendingCaptchaUrl = ""
        pendingCaptchaSessionId = 0L
        captchaGeneration += 1
    }

    private fun scheduleHealthMonitor() {
        if (stopRequested) return
        if (healthMonitorJob?.isActive == true) return

        healthMonitorJob = serviceScope.launch {
            while (!stopRequested) {
                delay(CONNECTION_HEALTH_CHECK_INTERVAL_MS)
                if (stopRequested) return@launch
                if (currentState != TurnFreeServiceState.WireGuardRunning) continue

                if (!turnClient.isRunning()) {
                    Log.w(TAG, "DTLS client is not running anymore")
                    scheduleReconnect(getString(R.string.status_turn_client_exit, -1))
                    return@launch
                }

                if (!wireGuardController.isRunning()) {
                    Log.w(TAG, "WireGuard tunnel is not running anymore")
                    scheduleReconnect(getString(R.string.status_wireguard_dropped))
                    return@launch
                }
            }
        }
    }

    private fun cancelHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = null
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun sendServiceBroadcast(
        action: String,
        state: TurnFreeServiceState,
        message: String,
        extraUrl: String? = null,
        captchaSessionId: Long? = null,
    ) {
        val intent = Intent(action).apply {
            setPackage(packageName)
            putExtra(TurnFreeServiceContract.EXTRA_STATE, state.name)
            putExtra(TurnFreeServiceContract.EXTRA_MESSAGE, message)
            if (captchaSessionId != null) {
                putExtra(TurnFreeServiceContract.EXTRA_CAPTCHA_SESSION_ID, captchaSessionId)
            }
            if (!extraUrl.isNullOrBlank()) {
                putExtra(TurnFreeServiceContract.EXTRA_URL, extraUrl)
            }
        }
        sendBroadcast(intent)
    }

    private fun updateNotification(title: String, text: String) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(title, text),
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_APP,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags(),
        )

        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            TurnFreeServiceContract.createStopIntent(this),
            pendingIntentFlags(),
        )

        val retryIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_RETRY,
            TurnFreeServiceContract.createRetryIntent(this),
            pendingIntentFlags(),
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        when (currentState) {
            TurnFreeServiceState.Reconnecting -> {
                builder
                    .setSubText(
                        getString(
                            R.string.notification_reconnecting_subtext,
                            reconnectAttempt,
                            MAX_RECONNECT_ATTEMPTS,
                            formatDelay(reconnectDelayMs),
                        ),
                    )
                    .addAction(
                        android.R.drawable.ic_popup_sync,
                        getString(R.string.notification_retry),
                        retryIntent,
                    )
            }

            TurnFreeServiceState.Failed -> {
                builder
                    .setSubText(
                        lastErrorMessage.ifBlank { getString(R.string.notification_failed_subtext) },
                    )
                    .addAction(
                        android.R.drawable.ic_popup_sync,
                        getString(R.string.notification_retry),
                        retryIntent,
                    )
            }

            TurnFreeServiceState.WireGuardRunning,
            TurnFreeServiceState.Established -> {
                builder.setSubText(getString(R.string.notification_connected_subtext))
            }

            else -> Unit
        }

        return builder
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_stop),
                stopIntent,
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun buildEstablishingMessage(profile: TurnFreeProfile): String {
        return getString(
            R.string.status_turn_connecting,
            profile.turn.ifBlank { getString(R.string.status_turn_no_turn_link) },
            profile.peer.ifBlank { getString(R.string.status_turn_no_peer) },
            profile.streams,
            if (profile.udp) getString(R.string.status_turn_udp_on) else getString(R.string.status_turn_udp_off),
        )
    }

    private fun profileSnapshot(): TurnFreeProfile {
        return currentProfile
    }

    private fun formatDelay(delayMs: Long): String {
        val seconds = (delayMs / 1000L).coerceAtLeast(1L)
        return getString(R.string.status_turn_seconds_format, seconds)
    }

    private fun extractUrl(text: String): String? {
        return URL_PATTERN.find(text)?.value?.trimEnd('.', ',', ')')
    }

    private fun looksLikeCaptchaSolved(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("[vk auth] success") ||
            lower.contains("[captcha] success") ||
            lower.contains("captcha solved") ||
            lower.contains("componentdone status: success") ||
            lower.contains("slider check status: success") ||
            lower.contains("auth success") ||
            lower.contains("success! got success_token") ||
            lower.contains("established dtls connection")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:WakeLock")
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "VkTurnService"
        private const val CHANNEL_ID = "turnfree_turn_channel"
        private const val NOTIFICATION_ID = 1011
        private const val REQUEST_CODE_OPEN_APP = 1101
        private const val REQUEST_CODE_STOP = 1102
        private const val REQUEST_CODE_RETRY = 1103
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val DTLS_ESTABLISH_TIMEOUT_MS = 30_000L
        private const val CAPTCHA_DTLS_ESTABLISH_TIMEOUT_MS = 120_000L
        private const val CONNECTION_HEALTH_CHECK_INTERVAL_MS = 5_000L
        private const val CAPTCHA_AUTO_WAIT_TIMEOUT_MS = 45_000L
        private val URL_PATTERN = Regex("""https?://\S+""")

        private fun computeReconnectDelayMs(attempt: Int): Long {
            val base = 2_000L
            val max = 30_000L
            val multiplier = 1L shl (attempt - 1).coerceAtLeast(0)
            return min(base * multiplier, max)
        }
    }
}
