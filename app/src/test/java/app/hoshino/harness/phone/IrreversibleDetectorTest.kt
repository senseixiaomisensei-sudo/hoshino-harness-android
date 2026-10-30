// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

internal fun el(
    ref: String, name: String, bounds: List<Int>, res: String? = null, clickable: Boolean = true, editable: Boolean = false,
    focused: Boolean = false, scrollable: Boolean = false, hint: Boolean = false, password: Boolean = false, role: String? = null,
) = UiElement(ref = ref, role = role ?: if (editable) "EditText" else "Button", name = name, bounds = bounds, resourceId = res, clickable = clickable,
    editable = editable, focused = focused, scrollable = scrollable, hint = hint, password = password)

internal fun snap(vararg e: UiElement, pkg: String = "com.chat", id: String = "s1") = ScreenSnapshot(id, pkg, 1080, 2400, e.toList())

private fun tap(ref: String? = null, x: Int? = null, y: Int? = null, target: String? = null) = PhoneStep(StepKind.tap, ref = ref, x = x, y = y, target = target)

class IrreversibleDetectorTest {
    private val d = IrreversibleDetector

    @Test fun chineseSendButton() {
        val s = snap(el("e1", "发送", listOf(800, 900, 990, 990)), el("e2", "发送(1)", listOf(0, 0, 100, 100)), el("e3", "确认支付", listOf(0, 200, 100, 300)))
        assertEquals(Irreversible(IrrevAction.send, "发送"), d.classify(tap("e1"), s))
        assertEquals(IrrevAction.send, d.classify(tap("e2"), s)?.action)
        assertEquals(IrrevAction.pay, d.classify(tap("e3"), s)?.action)
        assertNull(d.matchName("发送给朋友的那张照片在哪里"))
        for ((w, a) in listOf("删除" to IrrevAction.delete, "退出群聊" to IrrevAction.delete, "提交订单" to IrrevAction.order,
            "立即购买" to IrrevAction.order, "转账" to IrrevAction.pay, "发 布" to IrrevAction.send, "报名" to IrrevAction.order)) {
            assertEquals(w, a, d.matchName(w))
        }
    }

    @Test fun englishCoordinateAndResourceIdTaps() {
        val s = snap(el("e1", "", listOf(0, 0, 999, 999), clickable = false), el("e2", "Place order", listOf(100, 800, 900, 900)), el("e3", "Buy Now", listOf(100, 600, 900, 700)))
        assertEquals(IrrevAction.order, d.classify(tap(x = 500, y = 850), s)?.action)
        assertNull(d.classify(tap(x = 500, y = 300), s))
        for ((w, a) in listOf("Send" to IrrevAction.send, "post" to IrrevAction.send, "Pay now" to IrrevAction.pay, "Delete" to IrrevAction.delete,
            "Clear all" to IrrevAction.delete, "Checkout" to IrrevAction.order)) assertEquals(w, a, d.matchName(w))
        assertEquals(IrrevAction.send, d.classify(tap("e1"), snap(el("e1", "", listOf(0, 0, 100, 100), res = "com.chat:id/btnSend")))?.action)
        assertNull(d.classify(tap("e1"), snap(el("e1", "", listOf(0, 0, 99, 99), res = "com.x:id/post_list"))))
    }

    @Test fun iconOnlyButtonFallsBackToModelTarget() {
        val icon = snap(el("e1", "", listOf(800, 900, 990, 990), res = "com.chat:id/iv_icon"))
        assertNull(d.classify(tap(x = 900, y = 950), icon))
        assertEquals(Irreversible(IrrevAction.send, "发送"), d.classify(tap(x = 900, y = 950, target = "发送"), icon))
    }

    @Test fun unnamedSpotJudgedByRowText() {
        val s = snap(
            el("e1", "", listOf(0, 0, 999, 999), clickable = false), el("e2", "删除", listOf(800, 400, 990, 460), clickable = false),
            el("e3", "", listOf(50, 400, 150, 460)), el("e4", "张三", listOf(0, 600, 999, 660), clickable = false),
            el("e5", "删除记录说明", listOf(0, 0, 999, 300), clickable = false),
        )
        assertEquals(Irreversible(IrrevAction.delete, "删除"), d.classify(tap(x = 100, y = 430), s))
        assertNull(d.classify(tap(x = 400, y = 630), s))
        assertNull(d.classify(tap(x = 400, y = 200), s))
    }

    @Test fun contactAndApplyButtonsAreSubmissions() {
        for (w in listOf("立即沟通", "沟通", "打招呼", "一键投递", "投递简历", "立即申请", "应聘", "联系TA", "Say hi", "Easy Apply", "Apply now")) assertEquals(w, IrrevAction.submit, d.matchName(w))
        for (w in listOf("继续沟通", "已沟通", "沟通记录", "已投递", "联系客服", "消息", "私信", "申请", "Apply", "Contact us")) assertNull(w, d.matchName(w))
    }

    @Test fun submitOnlyExactMatch() {
        assertEquals(IrrevAction.submit, d.matchName("提交"))
        assertEquals(IrrevAction.submit, d.matchName(" Submit "))
        assertNull(d.matchName("提交搜索"))
        assertNull(d.matchName("Submitted"))
    }

    @Test fun enterInSearchIsNotSendButInChatIs() {
        val search = snap(el("e1", "搜索商品", listOf(0, 0, 800, 100), editable = true, focused = true, hint = true), el("e2", "搜索", listOf(800, 0, 999, 100)))
        assertNull(d.classify(PhoneStep(StepKind.type, ref = "e1", text = "耳机", enter = true), search))
        assertNull(d.classify(PhoneStep(StepKind.key, key = "enter"), search))
        val chat = snap(el("e1", "", listOf(0, 900, 800, 990), editable = true, focused = true), el("e2", "发送", listOf(800, 900, 999, 990)))
        assertEquals(IrrevAction.send, d.classify(PhoneStep(StepKind.type, text = "你好", enter = true), chat)?.action)
        assertNull(d.classify(PhoneStep(StepKind.type, text = "你好"), chat))
        assertEquals(IrrevAction.send, d.classify(PhoneStep(StepKind.key, key = "enter"), chat)?.action)
        assertNull("空格在输入框里只是打字", d.classify(PhoneStep(StepKind.key, key = "space"), chat))
        assertNull(d.classify(PhoneStep(StepKind.key, key = "del"), chat))
        assertNotNull("焦点不明按发送拦", d.classify(PhoneStep(StepKind.key, key = "enter"), null))
    }

    @Test fun genericConfirmNeedsContext() {
        val dialog = snap(el("e1", "确定删除这条消息?", listOf(0, 300, 999, 400), clickable = false), el("e2", "确定", listOf(500, 500, 999, 600)))
        assertEquals(IrrevAction.delete, d.classify(tap("e2"), dialog)?.action)
        val sheet = snap(el("e1", "实付 ¥ 39.90", listOf(0, 300, 999, 400), clickable = false), el("e2", "确认", listOf(500, 500, 999, 600)))
        assertEquals(IrrevAction.order, d.classify(tap("e2"), sheet)?.action)
        val plain = snap(el("e1", "已保存", listOf(0, 300, 999, 400), clickable = false), el("e2", "完成", listOf(500, 500, 999, 600)))
        assertNull(d.classify(tap("e2"), plain))
        for (w in listOf("评论(23)", "回复 3", "支付宝", "支付方式", "预约挂号指南", "Postpone", "Payment history", "这是一段很长的聊天消息里面提到了删除两个字")) assertNull(w, d.matchName(w))
    }

    @Test fun editingFieldIsNotIrreversible() {
        val s = snap(el("e1", "发送内容", listOf(0, 900, 800, 990), editable = true), el("e2", "×", listOf(700, 910, 790, 980)), el("e3", "发送", listOf(800, 900, 999, 990)))
        assertNull(d.classify(tap("e1"), s))
        assertNull(d.classify(tap("e2"), s))
        assertEquals(IrrevAction.send, d.classify(tap("e3"), s)?.action)
    }

    @Test fun longHorizontalSwipeOnRowIsDelete() {
        val s = snap(el("e1", "张三 昨天", listOf(0, 300, 999, 400)), el("e2", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true))
        assertEquals(Irreversible(IrrevAction.delete, "swipe on 张三 昨天"), d.classify(PhoneStep(StepKind.swipe, x = 900, y = 350, x2 = 300, y2 = 350), s))
        assertNull("太短", d.classify(PhoneStep(StepKind.swipe, x = 900, y = 350, x2 = 600, y2 = 350), s))
        assertNull("竖滑", d.classify(PhoneStep(StepKind.swipe, x = 500, y = 800, x2 = 500, y2 = 200), s))
    }

    @Test fun readOnlyStepsNeverMatch() {
        val s = snap(el("e1", "发送", listOf(0, 0, 999, 999)))
        for (k in listOf(StepKind.scroll, StepKind.back, StepKind.open_app, StepKind.wait, StepKind.clear)) {
            assertNull(k.name, d.classify(PhoneStep(k, ref = "e1", direction = "down", app = "x", expect = Expectation(text = "a")), s))
        }
    }

    @Test fun paymentPageRecognised() {
        assertNotNull(d.paymentPage(snap(el("e1", "请输入支付密码", listOf(0, 0, 999, 100), clickable = false))))
        assertNull(d.paymentPage(snap(el("e1", "商品详情 ¥ 99", listOf(0, 0, 999, 100), clickable = false), el("e2", "加入购物车", listOf(0, 900, 500, 999)))))
    }
}
