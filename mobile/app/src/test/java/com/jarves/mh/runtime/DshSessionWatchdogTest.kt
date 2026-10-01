package com.jarves.mh.runtime

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshSessionWatchdogTest {
    @Test
    fun missingInitializeResponseTimesOutDespiteUnrecognizedOutput() {
        val watchdog = DshSessionWatchdog(0)
        watchdog.observe(DshSdkProtocolEvent.Ignored, 29_999)
        assertNull(watchdog.timeoutReason(29_999))
        assertTrue(watchdog.timeoutReason(30_000)!!.contains("initializing"))
    }

    @Test
    fun missingPromptAcknowledgementTimesOut() {
        val watchdog = DshSessionWatchdog(0)
        watchdog.observe(DshSdkProtocolEvent.Initialized, 10)
        assertNull(watchdog.timeoutReason(60_009))
        assertTrue(watchdog.timeoutReason(60_010)!!.contains("accepting"))
    }

    @Test
    fun runningBeforePromptResponseStartsModelDeadline() {
        val watchdog = DshSessionWatchdog(0)
        watchdog.observe(DshSdkProtocolEvent.Initialized, 10)
        watchdog.observe(DshSdkProtocolEvent.Status(true), 20)
        assertNull(watchdog.timeoutReason(60_010))
        assertNotNull(watchdog.timeoutReason(360_020))
    }

    @Test
    fun retriesRemainRecoverableButCannotWaitForeverWithoutActivity() {
        val watchdog = DshSessionWatchdog(0)
        watchdog.observe(DshSdkProtocolEvent.Initialized, 10)
        watchdog.observe(DshSdkProtocolEvent.PromptAccepted, 20)
        watchdog.observe(DshSdkProtocolEvent.Retry(1, 5, "TRANSPORT"), 300_000)
        assertNull(watchdog.timeoutReason(659_999))
        assertTrue(watchdog.timeoutReason(660_000)!!.contains("model response"))
    }

    @Test
    fun streamingActivityExtendsDeadlineButRepeatedRunningStatusDoesNot() {
        val watchdog = DshSessionWatchdog(0)
        watchdog.observe(DshSdkProtocolEvent.AssistantText("answer"), 100)
        watchdog.observe(DshSdkProtocolEvent.Status(true), 360_000)
        assertNotNull(watchdog.timeoutReason(360_100))
        watchdog.observe(DshSdkProtocolEvent.Reasoning(1, "checking", true, false), 360_101)
        assertNull(watchdog.timeoutReason(360_102))
    }

    @Test
    fun overlappingToolsKeepLongDeadlineUntilAllHaveFinished() {
        val watchdog = DshSessionWatchdog(0)
        watchdog.observe(DshSdkProtocolEvent.ToolStarted("a", "Bash", "build"), 10)
        watchdog.observe(DshSdkProtocolEvent.ToolStarted("b", "Bash", "test"), 20)
        watchdog.observe(DshSdkProtocolEvent.ToolCompleted("a", "Bash", "done"), 30)
        assertNull(watchdog.timeoutReason(360_030))
        assertTrue(watchdog.timeoutReason(1_800_030)!!.contains("tool"))
        watchdog.observe(DshSdkProtocolEvent.ToolCompleted("b", "Bash", "done"), 1_800_040)
        assertTrue(watchdog.timeoutReason(2_160_040)!!.contains("model response"))
    }

    @Test
    fun lateHandshakeReplyDoesNotShortenAnActiveToolDeadline() {
        val watchdog = DshSessionWatchdog(0)
        watchdog.observe(DshSdkProtocolEvent.Initialized, 10)
        watchdog.observe(DshSdkProtocolEvent.ToolStarted("a", "Bash", "build"), 20)
        watchdog.observe(DshSdkProtocolEvent.PromptAccepted, 30)
        assertNull(watchdog.timeoutReason(360_030))
        assertNotNull(watchdog.timeoutReason(1_800_020))
    }
}
