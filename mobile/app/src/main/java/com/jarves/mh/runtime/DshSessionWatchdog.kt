package com.jarves.mh.runtime

/** Uses monotonic time; only recognized SDK activity extends a deadline. */
internal class DshSessionWatchdog(startedAt: Long) {
    private enum class Phase { INITIALIZE, PROMPT, RESPONSE, TOOL }

    private var phase = Phase.INITIALIZE
    private var lastProgressAt = startedAt
    private val tools = mutableSetOf<String>()

    fun observe(event: DshSdkProtocolEvent, now: Long) {
        val next = when (event) {
            DshSdkProtocolEvent.Initialized -> {
                if (phase != Phase.INITIALIZE) return
                Phase.PROMPT
            }
            DshSdkProtocolEvent.PromptAccepted -> {
                if (phase != Phase.PROMPT) return
                Phase.RESPONSE
            }
            is DshSdkProtocolEvent.Status -> {
                if (!event.running || phase != Phase.PROMPT) return
                Phase.RESPONSE
            }
            is DshSdkProtocolEvent.ToolStarted -> {
                tools += event.callId
                Phase.TOOL
            }
            is DshSdkProtocolEvent.ToolCompleted -> {
                tools -= event.callId
                if (tools.isEmpty()) Phase.RESPONSE else Phase.TOOL
            }
            is DshSdkProtocolEvent.Reasoning, is DshSdkProtocolEvent.AssistantText,
            is DshSdkProtocolEvent.Retry -> if (tools.isEmpty()) Phase.RESPONSE else Phase.TOOL
            else -> return
        }
        phase = next
        lastProgressAt = now
    }

    fun timeoutReason(now: Long): String? {
        val timeout = when (phase) {
            Phase.INITIALIZE -> 30_000L
            Phase.PROMPT -> 60_000L
            Phase.RESPONSE -> 6 * 60_000L
            // Native builds and shell tools can legitimately stay silent for minutes.
            Phase.TOOL -> 30 * 60_000L
        }
        if (now - lastProgressAt < timeout) return null
        val action = when (phase) {
            Phase.INITIALIZE -> "initializing the SDK"
            Phase.PROMPT -> "accepting the prompt"
            Phase.RESPONSE -> "waiting for a model response"
            Phase.TOOL -> "waiting for a tool to finish"
        }
        return "DeepSeek Harness timed out $action after ${timeout / 1_000}s without progress."
    }
}
