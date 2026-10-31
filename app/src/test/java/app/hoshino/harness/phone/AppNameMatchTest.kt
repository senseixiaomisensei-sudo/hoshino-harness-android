package app.hoshino.harness.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNameMatchTest {
    private val apps = listOf(
        AppNameMatch.App("微信", "com.tencent.mm"),
        AppNameMatch.App("微信读书", "com.tencent.weread"),
        AppNameMatch.App("企业微信", "com.tencent.wework"),
        AppNameMatch.App("支付宝", "com.eg.android.AlipayGphone"),
        AppNameMatch.App("抖音", "com.ss.android.ugc.aweme"),
        AppNameMatch.App("设置", "com.android.settings"),
    )

    @Test fun wechatByEveryCommonName() {
        for (q in listOf("微信", " 微信 ", "微信app", "微信 App", "打开微信", "「微信」", "WeChat", "wechat", "weixin", "com.tencent.mm"))
            assertEquals(q, "com.tencent.mm", AppNameMatch.resolve(apps, q))
    }

    @Test fun romLabelWithOddSpacesStillMatches() {
        val rom = listOf(AppNameMatch.App("微 信", "com.tencent.mm"), AppNameMatch.App("微信读书", "com.tencent.weread"))
        assertEquals("com.tencent.mm", AppNameMatch.resolve(rom, "微信"))
    }

    @Test fun aliasOnlyWhenInstalled() {
        assertNull(AppNameMatch.resolve(apps, "淘宝"))
        assertEquals("com.eg.android.AlipayGphone", AppNameMatch.resolve(apps, "alipay"))
    }

    @Test fun exactBeatsContainsAndAmbiguousIsNotGuessed() {
        assertEquals("com.tencent.weread", AppNameMatch.resolve(apps, "微信读书"))
        assertEquals("com.tencent.wework", AppNameMatch.resolve(apps, "企业微信"))
        assertNull("no unique fuzzy match", AppNameMatch.resolve(apps, "信"))
        assertTrue(AppNameMatch.suggest(apps, "微信").map { it.packageName }.containsAll(listOf("com.tencent.mm", "com.tencent.weread")))
    }

    @Test fun ownPackageAndEmpty() {
        assertEquals("app.hoshino.harness", AppNameMatch.resolve(apps, "app.hoshino.harness", ownPackage = "app.hoshino.harness"))
        assertNull(AppNameMatch.resolve(apps, "  "))
        assertNull(AppNameMatch.resolve(apps, "打开"))
    }
}
