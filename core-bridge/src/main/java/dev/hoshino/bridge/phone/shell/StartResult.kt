package dev.hoshino.bridge.phone.shell

/**
 * m2.9.3: what an `IActivityTaskManager.startActivityAsUser` result code (or `am start` output) means for phone_launch_app.
 *
 * Before m2.9.3 every result `>= 0` counted as started. But the framework's "start was refused" codes are positive too
 * (`START_SWITCHES_CANCELED` 100, `START_RETURN_LOCK_TASK_MODE_VIOLATION` 101, `START_ABORTED` 102 — ROM security
 * layers such as MIUI/HyperOS 关联启动/自启动 interception abort with these), so a blocked start was reported as
 * success and the model only learned "could not confirm it is in front" after the full readiness wait. Now:
 * 0..3 = started (SUCCESS, RETURN_INTENT_TO_CALLER, TASK_TO_FRONT, DELIVERED_TO_TOP), >= 100 = blocked by the system,
 * negative = error. A blocked/errored binder start is retried once through the shell's own `am start --display`
 * (same shell uid, the documented way to start a third-party activity on a VirtualDisplay).
 */
enum class StartResult {
    STARTED, BLOCKED, ERROR;

    companion object {
        fun ofCode(code: Int): StartResult = when {
            code in 0..3 -> STARTED
            code >= 100 -> BLOCKED
            else -> ERROR
        }

        /** `am start` prints "Error: …" (incl. "Activity not started, unknown error code 102") on refusal; "Warning: …" is benign. */
        fun ofAmOutput(exit: Int, output: String): StartResult = when {
            exit != 0 -> ERROR
            Regex("""error code 10[0-9]""", RegexOption.IGNORE_CASE).containsMatchIn(output) -> BLOCKED
            output.lineSequence().any { it.trimStart().startsWith("Error", ignoreCase = true) } -> ERROR
            else -> STARTED
        }
    }
}
