// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package dev.hoshino.bridge.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ShellClipboard（Mika e35865f / 7d2c48b）按能力找 `IClipboard` 读取签名：先认原版 Android 14+ / 12–13，再认已知扩展签名；
 * 认不出（Android 10/11 的两参签名）返回 null，调用方退回 ClipboardManager。参数个数和类型要与签名一一对上，否则反射调用会抛错。
 */
class ShellClipboardCallsTest {
    /** Android 14+：包名、attributionTag、userId、deviceId。 */
    interface Api34 {
        fun getPrimaryClip(pkg: String, attributionTag: String?, userId: Int, deviceId: Int): Any?
        fun getPrimaryClipDescription(pkg: String, attributionTag: String?, userId: Int, deviceId: Int): Any?
        fun hasPrimaryClip(pkg: String, attributionTag: String?, userId: Int, deviceId: Int): Boolean
    }

    /** Android 12–13：包名、attributionTag、userId。 */
    interface Api31 {
        fun getPrimaryClip(pkg: String, attributionTag: String?, userId: Int): Any?
        fun hasPrimaryClip(pkg: String, attributionTag: String?, userId: Int): Boolean
    }

    /** Android 10/11：包名、userId（认不出 → 退回 ClipboardManager）。 */
    interface Api29 {
        fun getPrimaryClip(pkg: String, userId: Int): Any?
        fun hasPrimaryClip(pkg: String, userId: Int): Boolean
    }

    /** 有的 ROM 扩成 7 参，同时保留原版 4 参：优先认原版。 */
    interface RomBoth {
        fun getPrimaryClip(pkg: String, label: String, privacy: String, attributionTag: String?, userId: Int, deviceId: Int, userAction: Boolean): Any?
        fun getPrimaryClip(pkg: String, attributionTag: String?, userId: Int, deviceId: Int): Any?
    }

    /** 只有 7 参扩展签名的 ROM。 */
    interface RomOnly {
        fun getPrimaryClip(pkg: String, label: String, privacy: String, attributionTag: String?, userId: Int, deviceId: Int, userAction: Boolean): Any?
    }

    private fun <T> recorder(type: Class<T>, calls: MutableList<List<Any?>>): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, args ->
            calls += args.orEmpty().toList()
            if (m.returnType == Boolean::class.javaPrimitiveType) true else "clip"
        })

    @Test fun android14SignatureIsFoundAndCalledWithDefaultDevice() {
        val m = ShellClipboardCalls.find(Api34::class.java, "getPrimaryClip")
        assertNotNull(m)
        assertEquals(4, m!!.parameterTypes.size)
        val calls = mutableListOf<List<Any?>>()
        val args = ShellClipboardCalls.args(m, "com.android.shell", "Shell", 10)!!
        assertEquals("clip", m.invoke(recorder(Api34::class.java, calls), *args))
        assertEquals(listOf(listOf("com.android.shell", null, 10, 0)), calls)
        assertNotNull(ShellClipboardCalls.find(Api34::class.java, "getPrimaryClipDescription"))
        assertNotNull(ShellClipboardCalls.find(Api34::class.java, "hasPrimaryClip"))
    }

    @Test fun android12SignatureIsFound() {
        val m = ShellClipboardCalls.find(Api31::class.java, "hasPrimaryClip")!!
        assertEquals(3, m.parameterTypes.size)
        val calls = mutableListOf<List<Any?>>()
        assertEquals(true, m.invoke(recorder(Api31::class.java, calls), *ShellClipboardCalls.args(m, "com.android.shell", "Shell", 0)!!))
        assertEquals(listOf(listOf("com.android.shell", null, 0)), calls)
        // 这一版没有 getPrimaryClipDescription：找不到就退回 ClipboardManager，不抛错
        assertNull(ShellClipboardCalls.find(Api31::class.java, "getPrimaryClipDescription"))
    }

    @Test fun olderTwoArgumentSignatureFallsBack() {
        assertNull(ShellClipboardCalls.find(Api29::class.java, "getPrimaryClip"))
        assertNull(ShellClipboardCalls.find(Api29::class.java, "hasPrimaryClip"))
    }

    @Test fun originalSignatureWinsOverRomExtension() {
        assertEquals(4, ShellClipboardCalls.find(RomBoth::class.java, "getPrimaryClip")!!.parameterTypes.size)
    }

    @Test fun romExtensionDoesNotFakeAUserAction() {
        val m = ShellClipboardCalls.find(RomOnly::class.java, "getPrimaryClip")!!
        val calls = mutableListOf<List<Any?>>()
        m.invoke(recorder(RomOnly::class.java, calls), *ShellClipboardCalls.args(m, "com.android.shell", "Shell", 0)!!)
        assertEquals(listOf(listOf("com.android.shell", "Shell", "", null, 0, 0, false)), calls)
    }

    @Test fun unknownArityGivesNoArguments() {
        val m = Api29::class.java.getMethod("getPrimaryClip", String::class.java, Int::class.javaPrimitiveType)
        assertNull(ShellClipboardCalls.args(m, "com.android.shell", "Shell", 0))
    }
}
