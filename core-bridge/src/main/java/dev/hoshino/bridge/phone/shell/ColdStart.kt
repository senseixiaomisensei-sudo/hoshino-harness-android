// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

/**
 * launch_app cold=true: may the app's process be force-stopped? Force-stop wipes the app's state on every screen,
 * so only when all of its tasks are on the agent display; a task anywhere else (even in the background) falls back
 * to a normal start; visible on the user's main screen = in use, not started at all. Ported from Mika's ColdStart.
 */
enum class ColdStart {
    RESTART, WARM, IN_USE;

    companion object {
        fun decide(taskDisplays: List<Int>, agentDisplay: Int, visibleOnMain: Boolean): ColdStart = when {
            visibleOnMain -> IN_USE
            taskDisplays.any { it != agentDisplay } -> WARM
            else -> RESTART
        }
    }
}
