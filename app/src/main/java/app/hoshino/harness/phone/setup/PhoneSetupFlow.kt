// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone.setup

import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * The phone setup wizard as a pure function (PH22): every step reads what the system reports and only ticks
 * itself; nothing blocks the user ("稍后再说" always works), and the first unfinished step is the one shown.
 * Switches we cannot read reliably on some ROMs (developer options, wireless debugging) never block: the user
 * can say "我已经打开了".
 */
object PhoneSetupFlow {
    enum class Step { Version, Consent, Developer, Pairing, Accessibility, Ocr, Done }
    enum class Mark { Done, Todo, Optional, Unsupported }

    data class Input(
        val sdk: Int,
        val consentConfirmed: Boolean,
        val shizukuReady: Boolean,
        val developerOptions: Boolean?,
        val wirelessDebugging: Boolean?,
        val paired: Boolean,
        val helperRunning: Boolean,
        val accessibility: Boolean,
        val ocrReady: Boolean,
        val userSaysDeveloperOn: Boolean = false,
    )

    fun marks(i: Input): Map<Step, Mark> {
        val supported = i.sdk >= 33
        val viaShizuku = i.shizukuReady
        return linkedMapOf(
            Step.Version to if (supported) Mark.Done else Mark.Unsupported,
            Step.Consent to if (i.consentConfirmed) Mark.Done else Mark.Todo,
            Step.Developer to when {
                viaShizuku || i.helperRunning || i.paired -> Mark.Done
                i.userSaysDeveloperOn || (i.developerOptions == true && i.wirelessDebugging == true) -> Mark.Done
                else -> Mark.Todo
            },
            Step.Pairing to if (viaShizuku || i.helperRunning) Mark.Done else Mark.Todo,
            Step.Accessibility to if (i.accessibility) Mark.Done else Mark.Todo,
            Step.Ocr to if (i.ocrReady) Mark.Done else Mark.Optional,
            Step.Done to if (supported && i.consentConfirmed && (viaShizuku || i.helperRunning) && i.accessibility) Mark.Done else Mark.Todo,
        )
    }

    /** First step that still needs the user (optional OCR is skipped). */
    fun current(i: Input): Step = marks(i).entries.firstOrNull { (s, m) -> s != Step.Done && (m == Mark.Todo || m == Mark.Unsupported) }?.key ?: Step.Done

    /** Most likely broken step when phone control fails, for "指出最可能出错的一步". */
    fun likelyProblem(i: Input): String? = when {
        i.sdk < 33 -> "系统版本低于 Android 13，手机操作不可用"
        !i.consentConfirmed -> "还没在「同意」这一步确认要开启的项目"
        !(i.shizukuReady || i.helperRunning) && !i.paired -> "还没配对无线调试"
        !(i.shizukuReady || i.helperRunning) && i.wirelessDebugging == false -> "无线调试关着（重启或换 Wi-Fi 后系统会关掉它）"
        !(i.shizukuReady || i.helperRunning) -> "手机助手没有运行：确认连着 Wi-Fi 且无线调试开着，然后点「重新连接」"
        !i.accessibility -> "Hoshino 的无障碍服务没开（HyperOS 可能在清理后台后把它关掉）"
        else -> null
    }

    /** Xiaomi HyperOS / MIUI pitfalls shown in the wizard. */
    val HYPEROS_TIPS = listOf(
        "开发者选项：设置 → 我的设备 → 全部参数与信息 → 连点 7 次「OS 版本」。",
        "小米必须额外打开开发者选项里的「USB 调试（安全设置）」，否则模拟点击和输入会被系统拦下（需要插 SIM 卡并登录小米账号）。",
        "无线调试：开发者选项 → 无线调试；第一次在某个 Wi-Fi 上打开时系统会问是否允许，选「允许」。",
        "Android 13+ 侧载的 App 开无障碍会被「受限制的设置」拦住：应用信息 → 右上角 ⋮ →「允许受限制的设置」，再回来开。",
        "省电策略：设置 → 应用设置 → 应用管理 → Hoshino → 省电策略选「无限制」，并允许自启动，否则长任务会被系统中断。",
        "HyperOS 有时会在重启、换网或一段时间后自动关闭无线调试和「USB 调试（安全设置）」：手机操作不可用时先检查这两个开关。",
    )

    private const val FRAGMENT_KEY = ":settings:fragment_args_key"
    private const val FRAGMENT_ARGS = ":settings:show_fragment_args"

    fun developerOptionsPage(): List<Intent> = listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    fun wirelessDebuggingPage(): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(FRAGMENT_KEY, "toggle_adb_wireless").putExtra(FRAGMENT_ARGS, Bundle().apply { putString(FRAGMENT_KEY, "toggle_adb_wireless") }),
    ) + developerOptionsPage()
    fun accessibilityPage(): List<Intent> = listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
}
