package ru.stonefree.vkwg

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import ru.stonefree.vkwg.config.TurnFreeConfigParser
import ru.stonefree.vkwg.config.TurnFreeAppInventory
import ru.stonefree.vkwg.config.TurnFreeImportFormat
import ru.stonefree.vkwg.config.TurnFreePreferences
import ru.stonefree.vkwg.config.TurnFreeProfile
import ru.stonefree.vkwg.config.TurnFreeSplitTunnelMode
import ru.stonefree.vkwg.databinding.ActivityMainBinding
import ru.stonefree.vkwg.databinding.DialogSplitTunnelAppsBinding
import ru.stonefree.vkwg.turn.TurnFreeConnectionProgress
import ru.stonefree.vkwg.turn.TurnFreeServiceContract
import ru.stonefree.vkwg.turn.TurnFreeServiceState

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val preferences by lazy { TurnFreePreferences(this) }
    private val appInventory by lazy { TurnFreeAppInventory(this) }
    private val activityManager by lazy { getSystemService<ActivityManager>() }
    private val powerManager by lazy { getSystemService<PowerManager>() }
    private var currentProfile = TurnFreeProfile()
    private var currentServiceState = TurnFreeServiceState.Idle
    private var suppressUiEvents = false
    private var receiverRegistered = false
    private var advancedVisible = false
    private var currentConnectionProgress = TurnFreeConnectionProgress.idle()
    private var lastStableProgress = TurnFreeConnectionProgress.idle()
    private var pendingStartAfterPermissionFlow = false
    private var manualCaptchaDialog: Dialog? = null
    private var captchaWebView: WebView? = null
    private var currentCaptchaSessionId: Long = -1L

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
                currentServiceState = TurnFreeServiceState.Failed
                renderLaunchButton(currentServiceState)
                setStatus(message)
                renderConnectionProgress(currentServiceState, null, message)
                pendingStartAfterPermissionFlow = false
            }
        }

    private val turnServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val progress = TurnFreeServiceContract.readProgress(intent)
            val state = runCatching {
                TurnFreeServiceState.valueOf(intent.getStringExtra(TurnFreeServiceContract.EXTRA_STATE).orEmpty())
            }.getOrNull()

            when (intent.action) {
                TurnFreeServiceContract.ACTION_STATE_CHANGED -> {
                    if (state != null) {
                        currentServiceState = state
                        renderLaunchButton(state)
                        if (state == TurnFreeServiceState.Failed || state == TurnFreeServiceState.Stopping) {
                            dismissManualCaptchaDialog()
                        }
                    }

                    val message = intent.getStringExtra(TurnFreeServiceContract.EXTRA_MESSAGE).orEmpty()
                    if (message.isNotBlank()) {
                        setStatus(message)
                    }
                    renderConnectionProgress(state ?: currentServiceState, progress, message)
                }

                TurnFreeServiceContract.ACTION_PROGRESS_CHANGED -> {
                    renderConnectionProgress(
                        state = state ?: currentServiceState,
                        progress = progress,
                        message = binding.statusText.text?.toString().orEmpty(),
                    )
                }

                TurnFreeServiceContract.ACTION_DTLS_ESTABLISHED -> {
                    state?.let {
                        currentServiceState = it
                        renderLaunchButton(it)
                    }
                    dismissManualCaptchaDialog()
                    val message = intent.getStringExtra(TurnFreeServiceContract.EXTRA_MESSAGE).orEmpty()
                        .ifBlank { getString(R.string.status_turn_established) }
                    setStatus(message)
                    renderConnectionProgress(TurnFreeServiceState.Established, progress, message)
                }

                TurnFreeServiceContract.ACTION_WIREGUARD_START_REQUESTED -> {
                    state?.let {
                        currentServiceState = it
                        renderLaunchButton(it)
                    }
                    dismissManualCaptchaDialog()
                    val message = intent.getStringExtra(TurnFreeServiceContract.EXTRA_MESSAGE).orEmpty()
                        .ifBlank { getString(R.string.status_wireguard_start_requested) }
                    setStatus(message)
                    renderConnectionProgress(TurnFreeServiceState.WireGuardStarting, progress, message)
                }

                TurnFreeServiceContract.ACTION_MANUAL_CAPTCHA_REQUIRED -> {
                    if (state != null) {
                        currentServiceState = state
                    }
                    val message = intent.getStringExtra(TurnFreeServiceContract.EXTRA_MESSAGE).orEmpty()
                    val url = intent.getStringExtra(TurnFreeServiceContract.EXTRA_URL).orEmpty()
                    val sessionId = intent.getLongExtra(TurnFreeServiceContract.EXTRA_CAPTCHA_SESSION_ID, -1L)
                    setStatus(message.ifBlank { getString(R.string.status_manual_captcha_required) })
                    renderConnectionProgress(
                        state = state ?: currentServiceState,
                        progress = progress,
                        message = message,
                    )
                    showManualCaptchaDialog(
                        message = message.ifBlank { getString(R.string.status_manual_captcha_required) },
                        url = url,
                        sessionId = sessionId,
                    )
                }

                TurnFreeServiceContract.ACTION_MANUAL_CAPTCHA_RESOLVED -> {
                    val sessionId = intent.getLongExtra(TurnFreeServiceContract.EXTRA_CAPTCHA_SESSION_ID, -1L)
                    if (sessionId < 0 || sessionId == currentCaptchaSessionId) {
                        dismissManualCaptchaDialog()
                    }
                    renderConnectionProgress(
                        state = state ?: currentServiceState,
                        progress = progress,
                        message = binding.statusText.text?.toString().orEmpty(),
                    )
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
        renderConnectionProgress(
            state = currentServiceState,
            progress = null,
            message = binding.statusText.text?.toString().orEmpty(),
        )
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

        binding.splitTunnelAppsButton.setOnClickListener {
            showSplitTunnelAppsDialog()
        }
    }

    private fun bindAutoSaveListeners() {
        binding.peerInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }
        binding.turnInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }
        binding.profileNameInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }
        binding.listenInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }
        binding.turnHostOverrideInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }
        binding.turnPortOverrideInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }
        binding.wireGuardConfigInput.doAfterTextChanged { if (!suppressUiEvents) saveUiStateFromInputs() }

        binding.udpSwitch.setOnCheckedChangeListener { _, _ ->
            if (!suppressUiEvents) saveUiStateFromInputs()
        }

        binding.noDtlsSwitch.setOnCheckedChangeListener { _, _ ->
            if (!suppressUiEvents) saveUiStateFromInputs()
        }

        binding.alwaysManualCaptchaSwitch.setOnCheckedChangeListener { _, _ ->
            if (!suppressUiEvents) saveUiStateFromInputs()
        }

        binding.splitTunnelModeGroup.setOnCheckedChangeListener { _, _ ->
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
        binding.wireGuardConfigInput.setText(profile.wireGuardConfigText)
        binding.streamsSpinner.setSelection(profile.streams.coerceIn(1, 12) - 1, false)
        binding.udpSwitch.isChecked = profile.udp
        binding.noDtlsSwitch.isChecked = profile.noDtls
        binding.alwaysManualCaptchaSwitch.isChecked = profile.alwaysManualCaptcha
        when (profile.splitTunnelMode) {
            TurnFreeSplitTunnelMode.Disabled -> binding.splitTunnelModeDisabled.isChecked = true
            TurnFreeSplitTunnelMode.ExcludeSelected -> binding.splitTunnelModeExclude.isChecked = true
            TurnFreeSplitTunnelMode.OnlySelected -> binding.splitTunnelModeIncludeOnly.isChecked = true
        }
        binding.lastImportValue.text = if (profile.importLabel.isBlank()) {
            getString(R.string.last_import_empty)
        } else {
            getString(R.string.last_import_source_format, profile.importLabel)
        }
        renderSplitTunnelSummary(profile)
        renderAdvancedSettingsVisibility()
        suppressUiEvents = false
    }

    private fun saveUiStateFromInputs() {
        val splitTunnelMode = selectedSplitTunnelModeFromUi()
        currentProfile = currentProfile.copy(
            profileName = binding.profileNameInput.text?.toString().orEmpty().trim(),
            peer = binding.peerInput.text?.toString().orEmpty().trim(),
            turn = binding.turnInput.text?.toString().orEmpty().trim(),
            listen = binding.listenInput.text?.toString().orEmpty().trim().ifBlank { DEFAULT_LISTEN },
            turnHostOverride = binding.turnHostOverrideInput.text?.toString().orEmpty().trim(),
            turnPortOverride = binding.turnPortOverrideInput.text?.toString().orEmpty().trim(),
            wireGuardConfigText = binding.wireGuardConfigInput.text?.toString().orEmpty(),
            streams = (binding.streamsSpinner.selectedItem?.toString()?.toIntOrNull() ?: 2).coerceIn(1, 12),
            udp = binding.udpSwitch.isChecked,
            noDtls = binding.noDtlsSwitch.isChecked,
            alwaysManualCaptcha = binding.alwaysManualCaptchaSwitch.isChecked,
            splitTunnelMode = splitTunnelMode,
        )
        preferences.save(currentProfile)
        renderSplitTunnelSummary(currentProfile)
    }

    private fun renderAdvancedSettingsVisibility() {
        binding.advancedSettingsContainer.isVisible = advancedVisible
        binding.advancedSettingsToggle.text = if (advancedVisible) {
            getString(R.string.advanced_settings_toggle_hide)
        } else {
            getString(R.string.advanced_settings_toggle_show)
        }
        renderSplitTunnelSummary(currentProfile)
    }

    private fun selectedSplitTunnelModeFromUi(): TurnFreeSplitTunnelMode {
        return when (binding.splitTunnelModeGroup.checkedRadioButtonId) {
            R.id.splitTunnelModeExclude -> TurnFreeSplitTunnelMode.ExcludeSelected
            R.id.splitTunnelModeIncludeOnly -> TurnFreeSplitTunnelMode.OnlySelected
            else -> TurnFreeSplitTunnelMode.Disabled
        }
    }

    private fun renderSplitTunnelSummary(profile: TurnFreeProfile) {
        val selectedCount = profile.splitTunnelPackages.size
        binding.splitTunnelAppsButton.text = if (selectedCount > 0) {
            getString(R.string.split_tunnel_apps_button_with_count, selectedCount)
        } else {
            getString(R.string.split_tunnel_apps_button)
        }

        binding.splitTunnelSummaryText.text = when (profile.splitTunnelMode) {
            TurnFreeSplitTunnelMode.Disabled -> getString(R.string.split_tunnel_summary_disabled)
            TurnFreeSplitTunnelMode.ExcludeSelected -> {
                if (selectedCount == 0) {
                    getString(R.string.split_tunnel_summary_exclude_none)
                } else {
                    getString(R.string.split_tunnel_summary_exclude_count, selectedCount)
                }
            }

            TurnFreeSplitTunnelMode.OnlySelected -> {
                if (selectedCount == 0) {
                    getString(R.string.split_tunnel_summary_include_none)
                } else {
                    getString(R.string.split_tunnel_summary_include_count, selectedCount)
                }
            }
        }
    }

    private fun showSplitTunnelAppsDialog() {
        saveUiStateFromInputs()
        val installedApps = appInventory.loadLaunchableApps()
        if (installedApps.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.split_tunnel_picker_title)
                .setMessage(R.string.split_tunnel_picker_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val dialogBinding = DialogSplitTunnelAppsBinding.inflate(layoutInflater)
        val adapter = TurnFreeSplitTunnelAppAdapter(
            context = this,
            allApps = installedApps,
            initialSelection = currentProfile.splitTunnelPackages,
        )
        dialogBinding.splitTunnelAppsList.adapter = adapter
        dialogBinding.splitTunnelAppsList.emptyView = dialogBinding.splitTunnelAppsEmpty
        dialogBinding.splitTunnelAppsList.setOnItemClickListener { _, _, position, _ ->
            adapter.toggleSelection(position)
        }
        dialogBinding.splitTunnelSearchInput.doAfterTextChanged { text ->
            dialogBinding.splitTunnelAppsEmpty.text = getString(
                if (text.isNullOrBlank()) {
                    R.string.split_tunnel_picker_empty
                } else {
                    R.string.split_tunnel_picker_no_results
                },
            )
            adapter.updateQuery(text?.toString().orEmpty())
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.split_tunnel_picker_title)
            .setView(dialogBinding.root)
            .setNeutralButton(R.string.split_tunnel_picker_clear) { _, _ ->
                applySplitTunnelSelection(emptySet())
                setStatus(splitTunnelSelectionStatus(cleared = true))
            }
            .setNegativeButton(R.string.split_tunnel_picker_cancel, null)
            .setPositiveButton(R.string.split_tunnel_picker_save) { _, _ ->
                applySplitTunnelSelection(adapter.currentSelection())
                setStatus(splitTunnelSelectionStatus(cleared = false))
            }
            .show()
    }

    private fun applySplitTunnelSelection(selectedPackages: Set<String>) {
        currentProfile = currentProfile.copy(splitTunnelPackages = selectedPackages)
        preferences.save(currentProfile)
        renderSplitTunnelSummary(currentProfile)
    }

    private fun splitTunnelSelectionStatus(cleared: Boolean): String {
        return if (
            currentProfile.splitTunnelMode == TurnFreeSplitTunnelMode.OnlySelected &&
            currentProfile.splitTunnelPackages.isEmpty()
        ) {
            getString(R.string.split_tunnel_status_select_required)
        } else if (cleared) {
            getString(R.string.split_tunnel_status_cleared)
        } else {
            getString(R.string.split_tunnel_status_updated)
        }
    }

    private fun renderLaunchButton(state: TurnFreeServiceState) {
        val isConnected = state == TurnFreeServiceState.WireGuardRunning
        val isConnecting = when (state) {
            TurnFreeServiceState.Starting,
            TurnFreeServiceState.Establishing,
            TurnFreeServiceState.Established,
            TurnFreeServiceState.WireGuardStarting,
            TurnFreeServiceState.Reconnecting -> true

            TurnFreeServiceState.Idle,
            TurnFreeServiceState.Stopping,
            TurnFreeServiceState.Failed,
            TurnFreeServiceState.WireGuardRunning -> false
        }
        val isActive = isServiceActive(state)
        val backgroundColor = when {
            isConnected -> R.color.app_button
            isConnecting -> R.color.app_button_soft
            else -> R.color.app_danger_soft
        }
        val strokeColor = when {
            isConnected -> R.color.app_button
            isConnecting -> R.color.app_button
            else -> R.color.app_danger
        }
        val textColor = when {
            isConnected -> R.color.app_surface
            isConnecting -> R.color.app_button
            else -> R.color.app_danger
        }

        binding.launchButton.text = when {
            isConnected -> getString(R.string.launch_button_connected)
            isConnecting -> getString(R.string.launch_button_connecting)
            state == TurnFreeServiceState.Failed -> getString(R.string.launch_button_retry)
            else -> getString(R.string.launch_button)
        }
        binding.launchButton.backgroundTintList = ColorStateList.valueOf(getColor(backgroundColor))
        binding.launchButton.strokeColor = ColorStateList.valueOf(getColor(strokeColor))
        binding.launchButton.setTextColor(getColor(textColor))
        binding.launchButton.isEnabled = true
        binding.launchButton.alpha = if (isActive && !isConnected) 0.98f else 1f
    }

    private fun renderConnectionProgress(
        state: TurnFreeServiceState,
        progress: TurnFreeConnectionProgress? = null,
        message: String,
    ) {
        val fallback = fallbackProgressForState(state)
        val resolved = when (state) {
            TurnFreeServiceState.Failed -> {
                val stable = if (lastStableProgress.stage == TurnFreeConnectionProgress.Stage.Idle) fallback else lastStableProgress
                stable.copy(stage = TurnFreeConnectionProgress.Stage.Failed)
            }

            else -> progress ?: fallback
        }

        if (state != TurnFreeServiceState.Failed && state != TurnFreeServiceState.Reconnecting) {
            lastStableProgress = resolved
        }
        currentConnectionProgress = resolved

        val shouldShow = state != TurnFreeServiceState.Idle || resolved.stage != TurnFreeConnectionProgress.Stage.Idle
        binding.connectionProgressBar.isVisible = shouldShow
        binding.connectionProgressBar.isIndeterminate = false
        binding.connectionProgressBar.setProgressCompat(resolved.progressPercent, true)

        binding.connectionStageText.text = when (state) {
            TurnFreeServiceState.Failed -> getString(
                R.string.progress_stage_failed_at,
                buildProgressStageLabel(resolved),
            )

            TurnFreeServiceState.Reconnecting -> getString(R.string.progress_stage_reconnecting)
            else -> buildProgressStageLabel(resolved)
        }

        binding.connectionCauseText.text = when (state) {
            TurnFreeServiceState.Failed,
            TurnFreeServiceState.Reconnecting -> mapFailureHint(message)

            else -> buildProgressDetail(resolved)
        }
    }

    private fun fallbackProgressForState(state: TurnFreeServiceState): TurnFreeConnectionProgress {
        val totalStreams = currentProfile.streams.coerceIn(1, 12)
        return when (state) {
            TurnFreeServiceState.Idle -> TurnFreeConnectionProgress.idle(totalStreams)
            TurnFreeServiceState.Starting -> TurnFreeConnectionProgress.startingTurn(totalStreams)
            TurnFreeServiceState.Establishing -> TurnFreeConnectionProgress(
                stage = TurnFreeConnectionProgress.Stage.WaitingForStreams,
                progressPercent = 32,
                totalStreams = totalStreams,
                activeStreams = 0,
                readyStreams = 0,
                captchaStep = 0,
                manualCaptcha = false,
                reconnectAttempt = 0,
                reconnectLimit = 0,
            )

            TurnFreeServiceState.Established -> TurnFreeConnectionProgress(
                stage = TurnFreeConnectionProgress.Stage.TurnReady,
                progressPercent = 76,
                totalStreams = totalStreams,
                activeStreams = 1.coerceAtMost(totalStreams),
                readyStreams = 1.coerceAtMost(totalStreams),
                captchaStep = 0,
                manualCaptcha = false,
                reconnectAttempt = 0,
                reconnectLimit = 0,
            )

            TurnFreeServiceState.WireGuardStarting -> TurnFreeConnectionProgress(
                stage = TurnFreeConnectionProgress.Stage.StartingWireGuard,
                progressPercent = 88,
                totalStreams = totalStreams,
                activeStreams = 1.coerceAtMost(totalStreams),
                readyStreams = 1.coerceAtMost(totalStreams),
                captchaStep = 0,
                manualCaptcha = false,
                reconnectAttempt = 0,
                reconnectLimit = 0,
            )

            TurnFreeServiceState.WireGuardRunning -> TurnFreeConnectionProgress(
                stage = TurnFreeConnectionProgress.Stage.Connected,
                progressPercent = 100,
                totalStreams = totalStreams,
                activeStreams = totalStreams,
                readyStreams = totalStreams,
                captchaStep = 0,
                manualCaptcha = false,
                reconnectAttempt = 0,
                reconnectLimit = 0,
            )

            TurnFreeServiceState.Reconnecting -> TurnFreeConnectionProgress(
                stage = TurnFreeConnectionProgress.Stage.Reconnecting,
                progressPercent = 24,
                totalStreams = totalStreams,
                activeStreams = 0,
                readyStreams = 0,
                captchaStep = 0,
                manualCaptcha = false,
                reconnectAttempt = 0,
                reconnectLimit = 0,
            )

            TurnFreeServiceState.Stopping -> TurnFreeConnectionProgress.stopping(totalStreams)
            TurnFreeServiceState.Failed -> lastStableProgress.copy(stage = TurnFreeConnectionProgress.Stage.Failed)
        }
    }

    private fun buildProgressStageLabel(progress: TurnFreeConnectionProgress): String {
        return when (progress.stage) {
            TurnFreeConnectionProgress.Stage.Idle -> getString(R.string.progress_stage_idle)
            TurnFreeConnectionProgress.Stage.RequestingPermissions -> getString(R.string.progress_stage_requesting_permissions)
            TurnFreeConnectionProgress.Stage.StartingService -> getString(R.string.progress_stage_starting_service)
            TurnFreeConnectionProgress.Stage.PreparingTurn -> getString(R.string.progress_stage_preparing_turn)
            TurnFreeConnectionProgress.Stage.StartingTurn -> getString(R.string.progress_stage_starting_dtls)
            TurnFreeConnectionProgress.Stage.WaitingForStreams -> {
                if (progress.readyStreams > 0 || progress.totalStreams > 1) {
                    getString(
                        R.string.progress_stage_waiting_streams_format,
                        progress.readyStreams,
                        progress.totalStreams,
                    )
                } else {
                    getString(R.string.progress_stage_waiting_streams)
                }
            }

            TurnFreeConnectionProgress.Stage.WaitingForCaptcha -> when {
                progress.manualCaptcha -> getString(R.string.progress_stage_waiting_captcha_manual)
                progress.captchaStep > 0 -> getString(
                    R.string.progress_stage_waiting_captcha_step,
                    progress.captchaStep,
                )

                else -> getString(R.string.progress_stage_waiting_captcha)
            }

            TurnFreeConnectionProgress.Stage.TurnReady -> getString(
                R.string.progress_stage_turn_ready_format,
                progress.readyStreams.coerceAtLeast(1),
                progress.totalStreams,
            )

            TurnFreeConnectionProgress.Stage.StartingWireGuard -> getString(
                R.string.progress_stage_starting_wireguard_format,
                progress.readyStreams.coerceAtLeast(1),
                progress.totalStreams,
            )

            TurnFreeConnectionProgress.Stage.Connected -> {
                if (progress.readyStreams in 1 until progress.totalStreams) {
                    getString(
                        R.string.progress_stage_connected_warming_format,
                        progress.readyStreams,
                        progress.totalStreams,
                    )
                } else {
                    getString(R.string.progress_stage_connected)
                }
            }

            TurnFreeConnectionProgress.Stage.Reconnecting -> getString(R.string.progress_stage_reconnecting)
            TurnFreeConnectionProgress.Stage.Stopping -> getString(R.string.progress_stage_stopping)
            TurnFreeConnectionProgress.Stage.Failed -> buildProgressStageLabel(lastStableProgress)
        }
    }

    private fun buildProgressDetail(progress: TurnFreeConnectionProgress): String {
        return when (progress.stage) {
            TurnFreeConnectionProgress.Stage.Idle -> getString(R.string.progress_cause_hint_idle)
            TurnFreeConnectionProgress.Stage.RequestingPermissions -> getString(R.string.progress_detail_permissions)
            TurnFreeConnectionProgress.Stage.StartingService -> getString(R.string.progress_detail_starting_service)
            TurnFreeConnectionProgress.Stage.PreparingTurn -> getString(
                R.string.progress_detail_preparing_turn_format,
                progress.totalStreams,
            )

            TurnFreeConnectionProgress.Stage.StartingTurn,
            TurnFreeConnectionProgress.Stage.WaitingForStreams,
            -> getString(
                R.string.progress_detail_streams_format,
                progress.activeStreams.coerceAtLeast(progress.readyStreams),
                progress.totalStreams,
                progress.readyStreams,
            )

            TurnFreeConnectionProgress.Stage.WaitingForCaptcha -> when {
                progress.manualCaptcha -> getString(R.string.progress_detail_captcha_manual)
                progress.captchaStep > 0 -> getString(
                    R.string.progress_detail_captcha_auto_step,
                    progress.captchaStep,
                )

                else -> getString(R.string.progress_detail_captcha_auto)
            }

            TurnFreeConnectionProgress.Stage.TurnReady -> getString(
                R.string.progress_detail_turn_ready_format,
                progress.readyStreams.coerceAtLeast(1),
                progress.totalStreams,
            )

            TurnFreeConnectionProgress.Stage.StartingWireGuard -> getString(R.string.progress_detail_wireguard_starting)
            TurnFreeConnectionProgress.Stage.Connected -> {
                if (progress.readyStreams in 1 until progress.totalStreams) {
                    getString(
                        R.string.progress_detail_connected_warming_format,
                        progress.readyStreams,
                        progress.totalStreams,
                    )
                } else {
                    getString(R.string.progress_detail_connected)
                }
            }

            TurnFreeConnectionProgress.Stage.Reconnecting,
            TurnFreeConnectionProgress.Stage.Failed,
            TurnFreeConnectionProgress.Stage.Stopping,
            -> getString(R.string.progress_cause_hint_idle)
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
            message.contains("хотя бы одно приложение") || message.contains("split tunnel") -> getString(R.string.progress_cause_split_tunnel)
            message.contains("amnezia") -> getString(R.string.progress_cause_amnezia)
            message.contains("binary") || message.contains("not found") -> getString(R.string.progress_cause_binary)
            message.contains("refused") || message.contains("unreachable") || message.contains("network") -> getString(R.string.progress_cause_network_path)
            message.contains("captcha") || message.contains("капч") -> getString(R.string.progress_cause_captcha)
            message.contains("challenge") ||
                message.contains("turnstile") ||
                message.contains("manual captcha") ||
                message.contains("slider") -> getString(R.string.progress_cause_captcha)
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

    private fun showManualCaptchaDialog(message: String, url: String, sessionId: Long) {
        if (isFinishing || isDestroyed) return
        if (sessionId >= 0 && currentCaptchaSessionId == sessionId && manualCaptchaDialog?.isShowing == true) {
            return
        }

        dismissManualCaptchaDialog()
        currentCaptchaSessionId = sessionId

        if (url.isNotBlank()) {
            manualCaptchaDialog = createCaptchaWebDialog(message, url)
        } else {
            manualCaptchaDialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.manual_captcha_title)
                .setMessage(message)
                .setNegativeButton(R.string.manual_captcha_later, null)
                .create()
        }

        manualCaptchaDialog?.setOnDismissListener {
            clearManualCaptchaDialogState()
        }
        manualCaptchaDialog?.show()
        manualCaptchaDialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun createCaptchaWebDialog(message: String, url: String): Dialog {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_surface))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.manual_captcha_title)
            setTextColor(getColor(R.color.text_primary))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val messageView = TextView(this).apply {
            text = message
            setTextColor(getColor(R.color.text_secondary))
            textSize = 14f
            setLineSpacing(0f, 1.1f)
            setPadding(0, dp(8), 0, dp(12))
        }

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val openInBrowserButton = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = getString(R.string.manual_captcha_open_browser)
            setOnClickListener { openManualCaptchaInBrowser(url) }
        }

        val closeButton = MaterialButton(this).apply {
            text = getString(R.string.manual_captcha_later)
            setOnClickListener { dismissManualCaptchaDialog() }
        }

        actionBar.addView(openInBrowserButton)
        actionBar.addView(closeButton)

        val webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).also { params ->
                params.topMargin = dp(12)
            }
        }

        val loadingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.app_surface))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(ProgressBar(this@MainActivity))
            addView(
                TextView(this@MainActivity).apply {
                    text = getString(R.string.manual_captcha_loading)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 14f
                    setPadding(0, dp(12), 0, 0)
                },
            )
        }

        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(getColor(android.R.color.white))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    loadingView.isVisible = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loadingView.isVisible = false
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        loadingView.isVisible = false
                        setStatus(
                            getString(
                                R.string.manual_captcha_webview_error,
                                error?.description?.toString().orEmpty().ifBlank { getString(R.string.status_unknown_error) },
                            ),
                        )
                    }
                }
            }
            loadUrl(url)
        }

        captchaWebView = webView
        webContainer.addView(webView)
        webContainer.addView(loadingView)

        root.addView(titleView)
        root.addView(messageView)
        root.addView(actionBar)
        root.addView(webContainer)

        dialog.setContentView(root)
        dialog.setCancelable(true)
        return dialog
    }

    private fun openManualCaptchaInBrowser(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure { error ->
            setStatus(
                getString(
                    R.string.manual_captcha_open_browser_failed,
                    error.message ?: getString(R.string.status_unknown_error),
                ),
            )
        }
    }

    private fun dismissManualCaptchaDialog() {
        manualCaptchaDialog?.dismiss()
        clearManualCaptchaDialogState()
    }

    private fun clearManualCaptchaDialogState() {
        captchaWebView?.let { webView ->
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
            runCatching { webView.removeAllViews() }
            runCatching { webView.destroy() }
        }
        captchaWebView = null
        manualCaptchaDialog = null
        currentCaptchaSessionId = -1L
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun startTurnService() {
        saveUiStateFromInputs()
        if (
            currentProfile.splitTunnelMode == TurnFreeSplitTunnelMode.OnlySelected &&
            currentProfile.splitTunnelPackages.isEmpty()
        ) {
            currentServiceState = TurnFreeServiceState.Failed
            renderLaunchButton(currentServiceState)
            val message = getString(R.string.status_split_tunnel_invalid_selection)
            setStatus(message)
            renderConnectionProgress(currentServiceState, null, message)
            return
        }
        currentServiceState = TurnFreeServiceState.Starting
        renderLaunchButton(currentServiceState)
        pendingStartAfterPermissionFlow = true
        renderConnectionProgress(
            state = currentServiceState,
            progress = TurnFreeConnectionProgress.requestingPermissions(currentProfile.streams),
            message = binding.statusText.text?.toString().orEmpty(),
        )
        continueStartupPermissionFlow()
    }

    private fun stopTurnService() {
        val intent = TurnFreeServiceContract.createStopIntent(this)
        ContextCompat.startForegroundService(this, intent)
        currentServiceState = TurnFreeServiceState.Stopping
        renderLaunchButton(currentServiceState)
        val message = getString(R.string.status_turn_stopping)
        setStatus(message)
        renderConnectionProgress(
            state = TurnFreeServiceState.Stopping,
            progress = TurnFreeConnectionProgress.stopping(currentProfile.streams),
            message = message,
        )
    }

    private fun startTurnServiceInternal() {
        pendingStartAfterPermissionFlow = false
        val intent = TurnFreeServiceContract.createStartIntent(this, currentProfile)
        ContextCompat.startForegroundService(this, intent)
        currentServiceState = TurnFreeServiceState.Starting
        renderLaunchButton(currentServiceState)
        val message = getString(R.string.status_turn_service_requested)
        setStatus(message)
        renderConnectionProgress(
            state = TurnFreeServiceState.Starting,
            progress = TurnFreeConnectionProgress.startingService(currentProfile.streams),
            message = message,
        )
    }

    private fun continueStartupPermissionFlow() {
        if (!pendingStartAfterPermissionFlow) return

        if (shouldRequestNotificationPermission()) {
            preferences.markNotificationPermissionRequested()
            val message = getString(R.string.status_notification_permission_requested)
            setStatus(message)
            renderConnectionProgress(
                state = currentServiceState,
                progress = TurnFreeConnectionProgress.requestingPermissions(currentProfile.streams),
                message = message,
            )
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        if (shouldRequestBatteryOptimizationExemption()) {
            preferences.markBatteryOptimizationPermissionRequested()
            val message = getString(R.string.status_battery_optimization_requested)
            setStatus(message)
            renderConnectionProgress(
                state = currentServiceState,
                progress = TurnFreeConnectionProgress.requestingPermissions(currentProfile.streams),
                message = message,
            )
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
            renderConnectionProgress(
                state = currentServiceState,
                progress = TurnFreeConnectionProgress.requestingPermissions(currentProfile.streams),
                message = message,
            )
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
            profile.noDtls ||
            profile.alwaysManualCaptcha ||
            profile.wireGuardConfigText.isNotBlank() ||
            profile.splitTunnelMode != TurnFreeSplitTunnelMode.Disabled ||
            profile.splitTunnelPackages.isNotEmpty()
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
        renderConnectionProgress(
            state = currentServiceState,
            progress = null,
            message = binding.statusText.text?.toString().orEmpty(),
        )
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                turnServiceReceiver,
                IntentFilter().apply {
                    addAction(TurnFreeServiceContract.ACTION_STATE_CHANGED)
                    addAction(TurnFreeServiceContract.ACTION_PROGRESS_CHANGED)
                    addAction(TurnFreeServiceContract.ACTION_DTLS_ESTABLISHED)
                    addAction(TurnFreeServiceContract.ACTION_WIREGUARD_START_REQUESTED)
                    addAction(TurnFreeServiceContract.ACTION_MANUAL_CAPTCHA_REQUIRED)
                    addAction(TurnFreeServiceContract.ACTION_MANUAL_CAPTCHA_RESOLVED)
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

    companion object {
        private const val DEFAULT_LISTEN = "127.0.0.1:9000"
    }
}
