// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mika f28e8be：防截屏窗口的截图错误码是 API 34 才有，旧系统上同一个数值不算防截屏。 */
class ScreenshotErrorsTest {
    private val secure = AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW

    @Test fun secureWindowCodeCountsFromAndroid14() {
        assertTrue(ScreenshotErrors.isSecureWindow(34, secure))
        assertTrue(ScreenshotErrors.isSecureWindow(36, secure))
    }

    @Test fun secureWindowCodeDoesNotCountBeforeAndroid14() {
        for (sdk in 29..33) assertFalse("sdk=$sdk", ScreenshotErrors.isSecureWindow(sdk, secure))
    }

    @Test fun otherCodesAreNotSecure() {
        assertFalse(ScreenshotErrors.isSecureWindow(34, AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
        assertFalse(ScreenshotErrors.isSecureWindow(34, AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT))
    }
}
