package ru.stonefree.vkwg.turn

import kotlin.math.roundToInt

data class TurnFreeConnectionProgress(
    val stage: Stage,
    val progressPercent: Int,
    val totalStreams: Int,
    val activeStreams: Int,
    val readyStreams: Int,
    val captchaStep: Int,
    val manualCaptcha: Boolean,
    val reconnectAttempt: Int,
    val reconnectLimit: Int,
) {

    enum class Stage {
        Idle,
        RequestingPermissions,
        StartingService,
        PreparingTurn,
        StartingTurn,
        WaitingForStreams,
        WaitingForCaptcha,
        TurnReady,
        StartingWireGuard,
        Connected,
        Reconnecting,
        Stopping,
        Failed,
    }

    companion object {
        fun idle(totalStreams: Int = 1): TurnFreeConnectionProgress {
            return TurnFreeConnectionProgress(
                stage = Stage.Idle,
                progressPercent = 0,
                totalStreams = totalStreams.coerceAtLeast(1),
                activeStreams = 0,
                readyStreams = 0,
                captchaStep = 0,
                manualCaptcha = false,
                reconnectAttempt = 0,
                reconnectLimit = 0,
            )
        }

        fun requestingPermissions(totalStreams: Int): TurnFreeConnectionProgress {
            return idle(totalStreams).copy(
                stage = Stage.RequestingPermissions,
                progressPercent = 6,
            )
        }

        fun startingService(totalStreams: Int): TurnFreeConnectionProgress {
            return idle(totalStreams).copy(
                stage = Stage.StartingService,
                progressPercent = 12,
            )
        }

        fun startingTurn(totalStreams: Int): TurnFreeConnectionProgress {
            return idle(totalStreams).copy(
                stage = Stage.StartingTurn,
                progressPercent = 24,
            )
        }

        fun stopping(totalStreams: Int): TurnFreeConnectionProgress {
            return idle(totalStreams).copy(
                stage = Stage.Stopping,
                progressPercent = 5,
            )
        }
    }
}

class TurnFreeConnectionProgressTracker {

    private val streamIdRegex = Regex("""\[STREAM\s+(\d+)]""")
    private val captchaStepRegex = Regex("""\[Captcha]\s+Step\s+(\d+)\s*/\s*(\d+)""")

    private var totalStreams = 1
    private val activeStreamIds = linkedSetOf<Int>()
    private val readyStreamIds = linkedSetOf<Int>()
    private var captchaStep = 0
    private var manualCaptcha = false
    private var waitingForCaptcha = false
    private var lastProgress = TurnFreeConnectionProgress.idle()

    fun reset(totalStreams: Int): TurnFreeConnectionProgress {
        this.totalStreams = totalStreams.coerceAtLeast(1)
        activeStreamIds.clear()
        readyStreamIds.clear()
        captchaStep = 0
        manualCaptcha = false
        waitingForCaptcha = false
        lastProgress = TurnFreeConnectionProgress.idle(this.totalStreams)
        return lastProgress
    }

    fun snapshot(): TurnFreeConnectionProgress = lastProgress

    fun permissionsRequested(totalStreams: Int = this.totalStreams): TurnFreeConnectionProgress {
        if (totalStreams > 0) {
            this.totalStreams = totalStreams.coerceAtLeast(1)
        }
        return update(TurnFreeConnectionProgress.requestingPermissions(this.totalStreams))
    }

    fun serviceStarting(): TurnFreeConnectionProgress {
        return update(TurnFreeConnectionProgress.startingService(totalStreams))
    }

    fun preparingTurn(): TurnFreeConnectionProgress {
        return update(stageSnapshot(TurnFreeConnectionProgress.Stage.PreparingTurn, preparingTurnProgress()))
    }

    fun turnStarting(): TurnFreeConnectionProgress {
        return update(TurnFreeConnectionProgress.startingTurn(totalStreams))
    }

    fun waitingForStreams(): TurnFreeConnectionProgress {
        waitingForCaptcha = false
        manualCaptcha = false
        captchaStep = 0
        return update(buildWaitingForStreamsSnapshot())
    }

    fun manualCaptchaRequired(): TurnFreeConnectionProgress {
        waitingForCaptcha = true
        manualCaptcha = true
        if (captchaStep == 0) {
            captchaStep = 2
        }
        return update(buildWaitingForCaptchaSnapshot())
    }

    fun turnReady(): TurnFreeConnectionProgress {
        waitingForCaptcha = false
        manualCaptcha = false
        captchaStep = 0
        return update(stageSnapshot(TurnFreeConnectionProgress.Stage.TurnReady, turnReadyProgress()))
    }

    fun wireGuardStarting(): TurnFreeConnectionProgress {
        waitingForCaptcha = false
        manualCaptcha = false
        captchaStep = 0
        return update(stageSnapshot(TurnFreeConnectionProgress.Stage.StartingWireGuard, startingWireGuardProgress()))
    }

    fun wireGuardConnected(): TurnFreeConnectionProgress {
        waitingForCaptcha = false
        manualCaptcha = false
        captchaStep = 0
        return update(stageSnapshot(TurnFreeConnectionProgress.Stage.Connected, connectedProgress()))
    }

    fun reconnecting(attempt: Int, limit: Int): TurnFreeConnectionProgress {
        activeStreamIds.clear()
        readyStreamIds.clear()
        waitingForCaptcha = false
        manualCaptcha = false
        captchaStep = 0
        return update(
            stageSnapshot(
                stage = TurnFreeConnectionProgress.Stage.Reconnecting,
                progressPercent = reconnectingProgress(attempt, limit),
                reconnectAttempt = attempt,
                reconnectLimit = limit,
            ),
        )
    }

    fun failed(): TurnFreeConnectionProgress {
        return update(
            stageSnapshot(
                stage = TurnFreeConnectionProgress.Stage.Failed,
                progressPercent = lastProgress.progressPercent,
                reconnectAttempt = lastProgress.reconnectAttempt,
                reconnectLimit = lastProgress.reconnectLimit,
            ),
        )
    }

    fun stopping(): TurnFreeConnectionProgress {
        return update(TurnFreeConnectionProgress.stopping(totalStreams))
    }

    fun onLogLine(line: String): TurnFreeConnectionProgress? {
        val lower = line.lowercase()
        val streamId = extractStreamId(line)
        var streamCountChanged = false

        if (streamId != null && activeStreamIds.add(streamId)) {
            streamCountChanged = true
        }

        captchaStepRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { parsedStep ->
            waitingForCaptcha = true
            manualCaptcha = false
            captchaStep = parsedStep.coerceIn(1, 4)
            return update(buildWaitingForCaptchaSnapshot())
        }

        if (
            lower.contains("action required: manual captcha solving needed") ||
            lower.contains("open this url in your browser:")
        ) {
            return manualCaptchaRequired()
        }

        if (
            lower.contains("captcha_wait_required") ||
            lower.contains("global lockout active") ||
            lower.contains("backing off for 60 seconds")
        ) {
            waitingForCaptcha = true
            if (captchaStep == 0) {
                captchaStep = 1
            }
            return update(buildWaitingForCaptchaSnapshot())
        }

        if (looksLikeCaptchaSolved(lower)) {
            waitingForCaptcha = false
            manualCaptcha = false
            captchaStep = 0
            return update(buildProgressAfterCaptcha())
        }

        if (lower.contains("established dtls connection")) {
            val readyId = streamId ?: nextSyntheticReadyStreamId()
            activeStreamIds += readyId
            readyStreamIds += readyId
            waitingForCaptcha = false
            manualCaptcha = false
            captchaStep = 0
            return update(buildProgressAfterStreamReady())
        }

        if (
            lower.contains("trying credentials") ||
            lower.contains("connecting identity") ||
            lower.contains("using cached credentials") ||
            lower.contains("turn_server urls")
        ) {
            return update(stageSnapshot(TurnFreeConnectionProgress.Stage.PreparingTurn, preparingTurnProgress()))
        }

        if (
            lower.contains("[turn] dialing") ||
            lower.contains("relayed-address=") ||
            lower.contains("extra alloc")
        ) {
            return update(buildWaitingForStreamsSnapshot())
        }

        if (streamCountChanged) {
            return update(buildProgressForCurrentPhase())
        }

        return null
    }

    private fun buildProgressAfterCaptcha(): TurnFreeConnectionProgress {
        return when (lastProgress.stage) {
            TurnFreeConnectionProgress.Stage.Connected -> stageSnapshot(
                TurnFreeConnectionProgress.Stage.Connected,
                connectedProgress(),
            )

            TurnFreeConnectionProgress.Stage.StartingWireGuard -> stageSnapshot(
                TurnFreeConnectionProgress.Stage.StartingWireGuard,
                startingWireGuardProgress(),
            )

            TurnFreeConnectionProgress.Stage.TurnReady -> stageSnapshot(
                TurnFreeConnectionProgress.Stage.TurnReady,
                turnReadyProgress(),
            )

            else -> buildWaitingForStreamsSnapshot()
        }
    }

    private fun buildProgressAfterStreamReady(): TurnFreeConnectionProgress {
        return when (lastProgress.stage) {
            TurnFreeConnectionProgress.Stage.Connected -> stageSnapshot(
                TurnFreeConnectionProgress.Stage.Connected,
                connectedProgress(),
            )

            TurnFreeConnectionProgress.Stage.StartingWireGuard -> stageSnapshot(
                TurnFreeConnectionProgress.Stage.StartingWireGuard,
                startingWireGuardProgress(),
            )

            else -> stageSnapshot(TurnFreeConnectionProgress.Stage.TurnReady, turnReadyProgress())
        }
    }

    private fun buildProgressForCurrentPhase(): TurnFreeConnectionProgress {
        return when (lastProgress.stage) {
            TurnFreeConnectionProgress.Stage.Connected -> stageSnapshot(
                TurnFreeConnectionProgress.Stage.Connected,
                connectedProgress(),
            )

            TurnFreeConnectionProgress.Stage.StartingWireGuard -> stageSnapshot(
                TurnFreeConnectionProgress.Stage.StartingWireGuard,
                startingWireGuardProgress(),
            )

            TurnFreeConnectionProgress.Stage.TurnReady -> stageSnapshot(
                TurnFreeConnectionProgress.Stage.TurnReady,
                turnReadyProgress(),
            )

            TurnFreeConnectionProgress.Stage.WaitingForCaptcha -> buildWaitingForCaptchaSnapshot()
            TurnFreeConnectionProgress.Stage.PreparingTurn,
            TurnFreeConnectionProgress.Stage.StartingTurn,
            TurnFreeConnectionProgress.Stage.WaitingForStreams,
            TurnFreeConnectionProgress.Stage.StartingService,
            TurnFreeConnectionProgress.Stage.RequestingPermissions,
            TurnFreeConnectionProgress.Stage.Idle,
            TurnFreeConnectionProgress.Stage.Reconnecting,
            TurnFreeConnectionProgress.Stage.Stopping,
            TurnFreeConnectionProgress.Stage.Failed,
            -> if (waitingForCaptcha) {
                buildWaitingForCaptchaSnapshot()
            } else {
                buildWaitingForStreamsSnapshot()
            }
        }
    }

    private fun buildWaitingForStreamsSnapshot(): TurnFreeConnectionProgress {
        return stageSnapshot(
            stage = TurnFreeConnectionProgress.Stage.WaitingForStreams,
            progressPercent = waitingForStreamsProgress(),
        )
    }

    private fun buildWaitingForCaptchaSnapshot(): TurnFreeConnectionProgress {
        return stageSnapshot(
            stage = TurnFreeConnectionProgress.Stage.WaitingForCaptcha,
            progressPercent = waitingForCaptchaProgress(),
        )
    }

    private fun stageSnapshot(
        stage: TurnFreeConnectionProgress.Stage,
        progressPercent: Int,
        reconnectAttempt: Int = 0,
        reconnectLimit: Int = 0,
    ): TurnFreeConnectionProgress {
        return TurnFreeConnectionProgress(
            stage = stage,
            progressPercent = progressPercent.coerceIn(0, 100),
            totalStreams = totalStreams.coerceAtLeast(1),
            activeStreams = activeStreamIds.size.coerceAtMost(totalStreams),
            readyStreams = readyStreamIds.size.coerceAtMost(totalStreams),
            captchaStep = captchaStep.coerceIn(0, 4),
            manualCaptcha = manualCaptcha,
            reconnectAttempt = reconnectAttempt.coerceAtLeast(0),
            reconnectLimit = reconnectLimit.coerceAtLeast(0),
        )
    }

    private fun update(progress: TurnFreeConnectionProgress): TurnFreeConnectionProgress {
        lastProgress = progress
        return progress
    }

    private fun waitingForStreamsProgress(): Int {
        val total = totalStreams.coerceAtLeast(1)
        val activeWeight = (activeStreamIds.size.toDouble() / total * 14.0).roundToInt()
        val readyWeight = (readyStreamIds.size.toDouble() / total * 26.0).roundToInt()
        return (28 + activeWeight + readyWeight).coerceIn(28, 70)
    }

    private fun waitingForCaptchaProgress(): Int {
        val total = totalStreams.coerceAtLeast(1)
        val stepWeight = captchaStep.coerceIn(0, 4) * 8
        val readyWeight = (readyStreamIds.size.toDouble() / total * 8.0).roundToInt()
        val manualBoost = if (manualCaptcha) 6 else 0
        return (36 + stepWeight + readyWeight + manualBoost).coerceIn(36, 76)
    }

    private fun preparingTurnProgress(): Int {
        val total = totalStreams.coerceAtLeast(1)
        val activeWeight = (activeStreamIds.size.toDouble() / total * 5.0).roundToInt()
        return (18 + activeWeight).coerceIn(18, 24)
    }

    private fun turnReadyProgress(): Int {
        val total = totalStreams.coerceAtLeast(1)
        val readyWeight = (readyStreamIds.size.toDouble() / total * 10.0).roundToInt()
        return (74 + readyWeight).coerceIn(74, 84)
    }

    private fun startingWireGuardProgress(): Int {
        val total = totalStreams.coerceAtLeast(1)
        val readyWeight = (readyStreamIds.size.toDouble() / total * 8.0).roundToInt()
        return (84 + readyWeight).coerceIn(84, 92)
    }

    private fun connectedProgress(): Int {
        val total = totalStreams.coerceAtLeast(1)
        val ready = readyStreamIds.size.coerceAtMost(total)
        if (ready >= total) {
            return 100
        }
        return (92 + (ready.toDouble() / total * 8.0).roundToInt()).coerceIn(92, 99)
    }

    private fun reconnectingProgress(attempt: Int, limit: Int): Int {
        if (attempt <= 0 || limit <= 0) {
            return 18
        }
        return (18 + (attempt.toDouble() / limit * 12.0).roundToInt()).coerceIn(18, 30)
    }

    private fun extractStreamId(line: String): Int? {
        return streamIdRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun looksLikeCaptchaSolved(lower: String): Boolean {
        return lower.contains("auth success") ||
            lower.contains("captcha solved") ||
            lower.contains("success! got success_token") ||
            lower.contains("slider check status: success")
    }

    private fun nextSyntheticReadyStreamId(): Int {
        return (1..totalStreams).firstOrNull { it !in readyStreamIds } ?: (readyStreamIds.size + 1)
    }
}
