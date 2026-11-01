// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone.setup

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 能往后推的时限：从最后一次 [extend] 起算 [lengthMs]（移植 Mika dc7965b）。[PairingCoordinator] 用它定看着配对多久：
 * 用户每次回到配对这件事上（点「开始配对 / 去无线调试」、离开或回到停在配对一步的引导页）都从那时重新算。
 */
internal class SlidingDeadline(private val lengthMs: Long, private val now: () -> Long) {
    @Volatile private var until = 0L

    fun extend() {
        until = now() + lengthMs
    }

    /** 等 [job] 做完；等的时候时限可以被推后。做完为 true，时限先到为 false。 */
    suspend fun await(job: Job): Boolean {
        while (true) {
            val left = until - now()
            if (left <= 0) return false
            if (withTimeoutOrNull(left) { job.join() } != null) return true
        }
    }
}
