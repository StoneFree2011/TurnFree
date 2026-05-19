package ru.stonefree.vkwg.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnFreeConnectionProgressTest {

    @Test
    fun `captcha stage outranks generic waiting state`() {
        val tracker = TurnFreeConnectionProgressTracker()

        tracker.reset(totalStreams = 4)
        tracker.waitingForStreams()
        val progress = tracker.onLogLine("[STREAM 2] [Captcha] Step 2/4: componentDone")

        requireNotNull(progress)
        assertEquals(TurnFreeConnectionProgress.Stage.WaitingForCaptcha, progress.stage)
        assertEquals(2, progress.captchaStep)
        assertTrue(progress.progressPercent in 36..76)
    }

    @Test
    fun `each ready stream increases connected progress`() {
        val tracker = TurnFreeConnectionProgressTracker()

        tracker.reset(totalStreams = 4)
        tracker.wireGuardConnected()

        val first = tracker.onLogLine("[STREAM 1] Established DTLS connection!")
        val second = tracker.onLogLine("[STREAM 2] Established DTLS connection!")

        requireNotNull(first)
        requireNotNull(second)
        assertEquals(TurnFreeConnectionProgress.Stage.Connected, first.stage)
        assertEquals(1, first.readyStreams)
        assertEquals(2, second.readyStreams)
        assertTrue(second.progressPercent > first.progressPercent)
    }

    @Test
    fun `manual captcha toggles dedicated progress state`() {
        val tracker = TurnFreeConnectionProgressTracker()

        tracker.reset(totalStreams = 2)
        val progress = tracker.onLogLine("ACTION REQUIRED: MANUAL CAPTCHA SOLVING NEEDED")

        requireNotNull(progress)
        assertEquals(TurnFreeConnectionProgress.Stage.WaitingForCaptcha, progress.stage)
        assertTrue(progress.manualCaptcha)
    }
}
