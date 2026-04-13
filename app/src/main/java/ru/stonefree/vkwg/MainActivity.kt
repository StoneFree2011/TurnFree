package ru.stonefree.vkwg

import android.Manifest
import android.app.ActivityManager
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.net.VpnService
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.stonefree.vkwg.config.TurnFreeConfigParser
import ru.stonefree.vkwg.config.TurnFreeImportFormat
import ru.stonefree.vkwg.config.TurnFreePreferences
import ru.stonefree.vkwg.config.TurnFreeProfile
import ru.stonefree.vkwg.databinding.ActivityMainBinding
import ru.stonefree.vkwg.turn.TurnFreeServiceContract
import ru.stonefree.vkwg.turn.TurnFreeServiceState

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val preferences by lazy { TurnFreePreferences(this) }
    private val activityManager by lazy { getSystemService<ActivityManager>() }
    private val powerManager by lazy { getSystemService<PowerManager>() }
    private var currentProfile = TurnFreeProfile()
    private var currentServiceState = TurnFreeServiceState.Idle
    private var suppressUiEvents = false
    private var receiverRegistered = false
    private var advancedVisible = false
    private var lastStablePhase = ConnectionPhase.Idle
    private var pendingStartAfterPermissionFlow = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            preferences.markNotificationPermissionRequested()
            if (!granted) {
                setStatus(getString(R.string.status_notification_permission_denied))
            }
            continueStartupPermissionFlow()
        }

    private val batteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            preferences.markBatteryOptimizationPermissionRequested()
            if (!isIgnoringBatteryOptimizations()) {
                setStatus(getString(R.string.status_battery_optimization_denied))
            }
            continueStartupPermissionFlow()
        }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startTurnServiceInternal()
            } else {
                val message = getString(R.string.status_vpn_permission_denied)
                setStatus(message)
                renderConnectionProgress(currentServiceState, message)
                pendingStartAfterPermissionFlow = false
            }
        }

    private val turnServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            when (intent.action) {
                TurnFreeServiceContract.ACTION_STATE_CHANGED -> {
                    val state = runCatching {
                        TurnFreeServiceState.valueOf(intent.getStringExtra(TurnFreeServiceContract.EXTRA_STATE).orEmpty())
                    }.getOrNull()

                    if (state != null) {
                        currentServiceState = state
                        renderLaunchButton(state)
                    }

                    val message = intent.getStringExtra(TurnFreeServiceContract.EXTRA_MESSAGE).orEmpty()
                    if (message.isNotBlank()) {
                        setStatus(message)
                    }
                    renderConnectionProgress(state ?: currentServiceState, message)
                }

                TurnFreeServiceContract.ACTION_DTLS_ESTABLISHED -> {
                    val message = intent.getStringExtra(TurnFreeServiceContract.EXTRA_MESSAGE).orEmpty()
                        .ifBlank { getString(R.string.status_turn_established) }
                    setStatus(message)
                    renderConnectionProgress(TurnFreeServiceState.Established, message)
                }

                TurnFreeServiceContract.ACTION_WIREGUARD_START_REQUESTED -> {
                    val message = intent.getStringExtra(TurnFreeServiceContract.EXTRA_MESSAGE).orEmpty()
                        .ifBlank { getString(R.string.status_wireguard_start_requested) }
                    setStatus(message)
                    renderConnectionProgress(TurnFreeServiceState.WireGuardStarting, message)
                }
            }
        }
    }

    private val importConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                setStatus(getString(R.string.status_import_cancelled))
                return@registerForActivityResult
            }

            val fileName = queryDisplayName(uri) ?: uri.lastPathSegment ?: getString(R.string.status_file_fallback)
            val rawText = readTextFromUri(uri)
            if (rawText.isNullOrBlank()) {
                binding.lastImportValue.text = getString(R.string.last_import_file_format, fileName)
                setStatus(getString(R.string.status_import_failed, getString(R.string.status_empty_file)))
                return@registerForActivityResult
            }

            handleImportedText(rawText, fileName)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupStreamsSpinner()
        currentProfile = preferences.load()
        currentServiceState = preferences.loadServiceState()
        advancedVisible = false
        applyProfileToUi(currentProfile)
        bindActions()
        bindAutoSaveListeners()

        if (currentProfile.peer.isBlank() && currentProfile.turn.isBlank()) {
            setStatus(getString(R.string.status_idle))
        } else {
            setStatus(getString(R.string.status_restored))
        }
        reconcileSavedServiceState()
        renderLaunchButton(currentServiceState)
        renderConnectionProgress(currentServiceState, binding.statusText.text?.toString().orEmpty())
    }

    private fun setupStreamsSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.streams_options,
            android.R.layout.simple_spinner_item,
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.streamsSpinner.adapter = adapter
        binding.streamsSpinner.setSelection(1)
    }

    private fun bindActions() {
        binding.importConfigButton.setOnClickListener {
            showImportChooser()
        }

        binding.launchButton.setOnClickListener {
            if (isServiceActive(currentServiceState)) {
                stopTurnService()
            } else {
                startTurnService()
            }
        }

        binding.advancedSettingsToggle.setOnClickListener {
            advancedVisible = !advancedVisible
            renderAdvancedSettingsVisibility()
        }
    }

    private fun bindAutoSaveListeners() {
        binding.peerInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }
        binding.turnInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }
        binding.profileNameInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }
        binding.listenInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }
        binding.turnHostOverrideInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }
        binding.turnPortOverrideInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }

        binding.udpSwitch.setOnCheckedChangeListener { _, _ ->
            if (!suppressUiEvents) saveUiStateFromInputs()
        }

        binding.noDtlsSwitch.setOnCheckedChangeListener { _, _ ->
            if (!suppressUiEvents) saveUiStateFromInputs()
        }

        binding.streamsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                if (!suppressUiEvents) saveUiStateFromInputs()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showImportChooser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_dialog_title)
            .setItems(
                arrayOf(
                    getString(R.string.import_from_file),
                    getString(R.string.import_from_clipboard),
                ),
            ) { _, which ->
                when (which) {
                    0 -> importConfigLauncher.launch(arrayOf("*/*"))
                    1 -> importFromClipboard()
                }
            }
            .show()
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()

        if (text.isBlank()) {
            setStatus(getString(R.string.status_clipboard_empty))
            return
        }

        handleImportedText(text, getString(R.string.source_clipboard_label))
    }

    private fun handleImportedText(rawText: String, sourceLabel: String) {
        val result = try {
            TurnFreeConfigParser.parse(rawText)
        } catch (error: Exception) {
            binding.lastImportValue.text = getString(R.string.last_import_source_format, sourceLabel)
            setStatus(getString(R.string.status_import_failed, error.message ?: getString(R.string.status_unknown_error)))
            return
        }

        currentProfile = result.profile.copy(importLabel = sourceLabel)
        applyProfileToUi(currentProfile)
        preferences.save(currentProfile)

        val successStatus = when (result.format) {
            TurnFreeImportFormat.Json -> getString(R.string.status_imported_json)
            TurnFreeImportFormat.TurnbridgeLink -> getString(R.string.status_imported_turnbridge)
            TurnFreeImportFormat.WireGuardConf -> getString(R.string.status_imported_conf)
        }
        val warning = result.warning
        setStatus(
            if (warning.isNullOrBlank()) {
                successStatus
            } else {
                getString(R.string.status_imported_with_warning, successStatus, warning)
            },
        )
    }

    private fun applyProfileToUi(profile: TurnFreeProfile) {
        suppressUiEvents = true
        binding.profileNameInput.setText(profile.profileName)
        binding.peerInput.setText(profile.peer)
        binding.turnInput.setText(profile.turn)
        binding.listenInput.setText(profile.listen)
        binding.turnHostOverrideInput.setText(profile.turnHostOverride)
        binding.turnPortOverrideInput.setText(profile.turnPortOverride)
        binding.streamsSpinner.setSelection(profile.streams.coerceIn(1, 12) - 1, false)
        binding.udpSwitch.isChecked = profile.udp
        binding.noDtlsSwitch.isChecked = profile.noDtls
        binding.lastImportValue.text = if (profile.importLabel.isBlank()) {
            getString(R.string.last_import_empty)
        } else {
            getString(R.string.last_import_source_format, profile.importLabel)
        }
        renderAdvancedSettingsVisibility()
        suppressUiEvents = false
    }

    private fun saveUiStateFromInputs() {
        currentProfile = currentProfile.copy(
            profileName = binding.profileNameInput.text?.toString().orEmpty().trim(),
            peer = binding.peerInput.text?.toString().orEmpty().trim(),
            turn = binding.turnInput.text?.toString().orEmpty().trim(),
            listen = binding.listenInput.text?.toString().orEmpty().trim().ifBlank { DEFAULT_LISTEN },
            turnHostOverride = binding.turnHostOverrideInput.text?.toString().orEmpty().trim(),
            turnPortOverride = binding.turnPortOverrideInput.text?.toString().orEmpty().trim(),
            streams = (binding.streamsSpinner.selectedItem?.toString()?.toIntOrNull() ?: 2).coerceIn(1, 12),
            udp = binding.udpSwitch.isChecked,
            noDtls = binding.noDtlsSwitch.isChecked,
        )
        preferences.save(currentProfile)
    }

    private fun renderAdvancedSettingsVisibility() {
        binding.advancedSettingsContainer.isVisible = advancedVisible
        binding.advancedSettingsToggle.text = if (advancedVisible) {
            getString(R.string.advanced_settings_toggle_hide)
        } else {
            getString(R.string.advanced_settings_toggle_show)
        }
    }

    private fun renderLaunchButton(state: TurnFreeServiceState) {
        val isConnected = state == TurnFreeServiceState.WireGuardRunning
        val isActive = isServiceActive(state)
        val backgroundColor = if (isConnected) R.color.app_button else R.color.app_danger_soft
        val strokeColor = if (isConnected) R.color.app_button else R.color.app_danger
        val textColor = if (isConnected) R.color.app_surface else R.color.app_danger

        binding.launchButton.text = if (isConnected) {
            getString(R.string.launch_button_connected)
        } else {
            getString(R.string.launch_button)
        }
        binding.launchButton.backgroundTintList = ColorStateList.valueOf(getColor(backgroundColor))
        binding.launchButton.strokeColor = ColorStateList.valueOf(getColor(strokeColor))
        binding.launchButton.setTextColor(getColor(textColor))
        binding.launchButton.isEnabled = true
        binding.launchButton.alpha = if (isActive && !isConnected) 0.98f else 1f
    }

    private fun renderConnectionProgress(state: TurnFreeServiceState, message: String) {
        val phase = when (state) {
            TurnFreeServiceState.Failed -> lastStablePhase
            else -> mapPhase(state)
        }

        if (state != TurnFreeServiceState.Failed && state != TurnFreeServiceState.Reconnecting) {
            lastStablePhase = phase
        }

        val shouldShow = state != TurnFreeServiceState.Idle
        binding.connectionProgressBar.isVisible = shouldShow
        binding.connectionProgressBar.progress = phase.progress

        binding.connectionStageText.text = when (state) {
            TurnFreeServiceState.Failed -> getString(
                R.string.progress_stage_failed_at,
                getString(phase.stageLabelRes),
            )
            TurnFreeServiceState.Reconnecting -> getString(R.string.progress_stage_reconnecting)
            else -> getString(phase.stageLabelRes)
        }

        binding.connectionCauseText.text = when (state) {
            TurnFreeServiceState.Failed,
            TurnFreeServiceState.Reconnecting -> mapFailureHint(message)
            else -> getString(R.string.progress_cause_hint_idle)
        }
    }

    private fun mapPhase(state: TurnFreeServiceState): ConnectionPhase {
        return when (state) {
            TurnFreeServiceState.Idle -> ConnectionPhase.Idle
            TurnFreeServiceState.Starting -> ConnectionPhase.StartingDtls
            TurnFreeServiceState.Establishing -> ConnectionPhase.WaitingDtls
            TurnFreeServiceState.Established -> ConnectionPhase.DtlsEstablished
            TurnFreeServiceState.WireGuardStarting -> ConnectionPhase.WireGuardStarting
            TurnFreeServiceState.WireGuardRunning -> ConnectionPhase.Connected
            TurnFreeServiceState.Reconnecting -> ConnectionPhase.Reconnecting
            TurnFreeServiceState.Stopping -> ConnectionPhase.Stopping
            TurnFreeServiceState.Failed -> lastStablePhase
        }
    }

    private fun mapFailureHint(rawMessage: String): String {
        val message = rawMessage.lowercase()
        return when {
            message.isBlank() -> getString(R.string.progress_cause_default)
            message.contains("missing") ||
                message.contains("не заполн") ||
                message.contains("peer") && message.contains("turn") -> getString(R.string.progress_cause_missing_fields)
            message.contains("vpn") || message.contains("permission") -> getString(R.string.progress_cause_vpn_permission)
            message.contains("dns") || message.contains("resolve") -> getString(R.string.progress_cause_dns)
            message.contains("timeout") || message.contains("watchdog") -> getString(R.string.progress_cause_timeout)
            message.contains("wireguard") || message.contains("wg") -> getString(R.string.progress_cause_wireguard)
            message.contains("amnezia") -> getString(R.string.progress_cause_amnezia)
            message.contains("binary") || message.contains("not found") -> getString(R.string.progress_cause_binary)
            message.contains("refused") || message.contains("unreachable") || message.contains("network") -> getString(R.string.progress_cause_network_path)
            message.contains("captcha") -> getString(R.string.progress_cause_captcha)
            message.contains("refresh permission") -> getString(R.string.progress_cause_refresh_permission)
            else -> getString(R.string.progress_cause_default)
        }
    }

    private fun isServiceActive(state: TurnFreeServiceState): Boolean {
        return when (state) {
            TurnFreeServiceState.Starting,
            TurnFreeServiceState.Establishing,
            TurnFreeServiceState.Established,
            TurnFreeServiceState.WireGuardStarting,
            TurnFreeServiceState.WireGuardRunning,
            TurnFreeServiceState.Reconnecting -> true

            TurnFreeServiceState.Idle,
            TurnFreeServiceState.Stopping,
            TurnFreeServiceState.Failed -> false
        }
    }

    private fun setStatus(message: String) {
        binding.statusText.text = message
    }

    private fun startTurnService() {
        saveUiStateFromInputs()
        pendingStartAfterPermissionFlow = true
        continueStartupPermissionFlow()
    }

    private fun stopTurnService() {
        val intent = TurnFreeServiceContract.createStopIntent(this)
        ContextCompat.startForegroundService(this, intent)
        currentServiceState = TurnFreeServiceState.Stopping
        renderLaunchButton(currentServiceState)
        val message = getString(R.string.status_turn_stopping)
        setStatus(message)
        renderConnectionProgress(TurnFreeServiceState.Stopping, message)
    }

    private fun startTurnServiceInternal() {
        pendingStartAfterPermissionFlow = false
        val intent = TurnFreeServiceContract.createStartIntent(this, currentProfile)
        ContextCompat.startForegroundService(this, intent)
        val message = getString(R.string.status_turn_service_requested)
        setStatus(message)
        renderConnectionProgress(TurnFreeServiceState.Starting, message)
    }

    private fun continueStartupPermissionFlow() {
        if (!pendingStartAfterPermissionFlow) return

        if (shouldRequestNotificationPermission()) {
            preferences.markNotificationPermissionRequested()
            setStatus(getString(R.string.status_notification_permission_requested))
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        if (shouldRequestBatteryOptimizationExemption()) {
            preferences.markBatteryOptimizationPermissionRequested()
            setStatus(getString(R.string.status_battery_optimization_requested))
            batteryOptimizationLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                ).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
            return
        }

        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            val message = getString(R.string.status_vpn_permission_required)
            setStatus(message)
            renderConnectionProgress(TurnFreeServiceState.Starting, message)
            vpnPermissionLauncher.launch(permissionIntent)
            return
        }

        startTurnServiceInternal()
    }

    private fun shouldShowAdvancedSettings(profile: TurnFreeProfile): Boolean {
        return profile.profileName.isNotBlank() ||
            profile.listen.isNotBlank() && profile.listen != DEFAULT_LISTEN ||
            profile.turnHostOverride.isNotBlank() ||
            profile.turnPortOverride.isNotBlank() ||
            profile.noDtls
    }

    private fun shouldRequestNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !preferences.hasRequestedNotificationPermission()
    }

    private fun shouldRequestBatteryOptimizationExemption(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !isIgnoringBatteryOptimizations() &&
            !preferences.hasRequestedBatteryOptimizationPermission()
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            powerManager?.isIgnoringBatteryOptimizations(packageName) == true
    }

    private fun reconcileSavedServiceState() {
        if (!isServiceRunning() && isServiceActive(currentServiceState)) {
            currentServiceState = TurnFreeServiceState.Idle
            preferences.saveServiceState(currentServiceState)
        }
    }

    private fun isServiceRunning(): Boolean {
        val runningServices = activityManager?.getRunningServices(Int.MAX_VALUE) ?: return false
        return runningServices.any { it.service.className == ru.stonefree.vkwg.turn.VkTurnService::class.java.name }
    }

    private fun readTextFromUri(uri: Uri): String? {
        return contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(columnIndex)
                }
            }
        return null
    }

    override fun onStart() {
        super.onStart()
        reconcileSavedServiceState()
        renderLaunchButton(currentServiceState)
        renderConnectionProgress(currentServiceState, binding.statusText.text?.toString().orEmpty())
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                turnServiceReceiver,
                IntentFilter().apply {
                    addAction(TurnFreeServiceContract.ACTION_STATE_CHANGED)
                    addAction(TurnFreeServiceContract.ACTION_DTLS_ESTABLISHED)
                    addAction(TurnFreeServiceContract.ACTION_WIREGUARD_START_REQUESTED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(turnServiceReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private enum class ConnectionPhase(val progress: Int, val stageLabelRes: Int) {
        Idle(0, R.string.progress_stage_idle),
        StartingDtls(20, R.string.progress_stage_starting_dtls),
        WaitingDtls(50, R.string.progress_stage_waiting_dtls),
        DtlsEstablished(70, R.string.progress_stage_dtls_established),
        WireGuardStarting(85, R.string.progress_stage_starting_wireguard),
        Connected(100, R.string.progress_stage_connected),
        Reconnecting(35, R.string.progress_stage_reconnecting),
        Stopping(5, R.string.progress_stage_stopping),
    }

    companion object {
        private const val DEFAULT_LISTEN = "127.0.0.1:9000"
    }
}
