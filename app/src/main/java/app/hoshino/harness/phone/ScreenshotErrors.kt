// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.accessibilityservice.AccessibilityService
import android.os.Build

/** 无障碍截图失败码的解读（纯函数，JVM 单测直接跑）。 */
internal object ScreenshotErrors {
    /**
     * 屏上有防截屏窗口：专门的错误码 [AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW] 是 Android 14（API 34）才加的；
     * 更早的系统截出来是黑的、由调用方看画面判断，同一个数值在旧系统上不代表防截屏（移植 Mika f28e8be，原先误写成 API 31）。
     */
    fun isSecureWindow(sdk: Int, errorCode: Int): Boolean =
        sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
}
