// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
package app.hoshino.harness.phone

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Word-list safety net before a phone step: button names, Enter on a non-search field and long horizontal
 * swipes on list rows. No semantic understanding; when unsure it flags (the user approves once), only
 * `submit` requires an exact match. Phone-only port of Mika's detector (web parts removed).
 */
object IrreversibleDetector {
    val STRONG: Map<String, IrrevAction> = linkedMapOf(
        "支付" to IrrevAction.pay, "付款" to IrrevAction.pay, "转账" to IrrevAction.pay,
        "删除" to IrrevAction.delete, "撤回" to IrrevAction.delete,
        "下单" to IrrevAction.order, "购买" to IrrevAction.order, "结算" to IrrevAction.order,
        "取消订单" to IrrevAction.order, "确认收货" to IrrevAction.order, "退款" to IrrevAction.order,
        "转发" to IrrevAction.send,
    )
    private const val STRONG_MAX_LEN = 12
    private val STATUS_PREFIXES = listOf('待', '已')
    val STATUS_SUFFIXES = listOf("成功", "记录", "售后", "明细", "方式", "列表", "中心", "入口", "码")
    val EXACT_PAY = listOf("收银台", "买单", "充值", "提现", "转出", "打赏", "缴费")
    private val EXACT_PAY_PREFIXES = listOf("", "立即", "确认", "去")
    val RED_PACKET = listOf("红包", "发红包", "塞钱进红包")
    val PREFIX_WORDS: Map<IrrevAction, List<String>> = linkedMapOf(
        IrrevAction.send to listOf("发送", "发布", "发表"),
        IrrevAction.delete to listOf("清空", "移除", "注销", "退出群聊"),
        IrrevAction.order to listOf("提交订单"),
    )
    private const val PREFIX_SLACK = 4
    val PUNCT_WORDS: Map<IrrevAction, List<String>> = linkedMapOf(IrrevAction.send to listOf("评论", "回复"))
    val BOOKING_WORDS = listOf("预约", "报名")
    private val BOOKING_PREFIXES = listOf("", "立即", "确认")
    val SUBMIT_WORDS = listOf("提交", "确认提交", "submit")
    val CONTACT_WORDS = listOf("沟通", "打招呼", "打个招呼", "发起聊天", "发起沟通", "联系ta", "联系他", "联系她", "联系对方", "投递", "投简历", "投递简历", "应聘")
    private val CONTACT_PREFIXES = listOf("", "立即", "马上", "一键", "去")
    val APPLY_WORDS = listOf("立即申请", "一键申请", "申请职位", "申请岗位", "申请该职位")
    val CONTACT_ENGLISH = listOf(
        "say hi", "say hello", "send greeting", "apply now", "easy apply", "quick apply", "apply for this job", "apply to this job",
        "submit application", "contact now", "contact recruiter", "contact employer", "contact seller", "contact host", "contact owner",
        "contact landlord", "contact agent",
    )
    val ENGLISH: Map<String, IrrevAction> = linkedMapOf(
        "send" to IrrevAction.send, "post" to IrrevAction.send, "reply" to IrrevAction.send, "pay" to IrrevAction.pay,
        "delete" to IrrevAction.delete, "remove" to IrrevAction.delete, "clear all" to IrrevAction.delete,
        "place order" to IrrevAction.order, "buy now" to IrrevAction.order, "checkout" to IrrevAction.order,
    )
    val GENERIC = listOf("确认", "确定", "ok", "完成", "confirm")
    private val AMOUNT = Regex("[¥￥]\\s*\\d|\\d+(\\.\\d+)?\\s*元")
    val TOTAL_LABELS = listOf("合计", "实付", "应付", "总计")
    val PAY_SHEET_WORDS = listOf("支付方式", "付款方式", "支付宝", "银行卡", "收银台", "免密")
    val WEAK_PAY_SHEET_WORDS = listOf("余额", "密码", "指纹", "零钱")
    private val PAYMENT_ADS = Regex("支持[^\\s，,。]{0,8}?(?:付款|支付)|货到付款|先用后付|花呗|分期|付款方式说明")
    private val ORDER_CONTEXT = listOf("立即购买", "加入购物车", "立即下单")
    private const val BOTTOM_REGION = 500
    private val PAY_SHEET_ENGLISH = Regex("(?<![A-Za-z0-9_])(pay|payment|checkout total)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)
    val EXCLUDED = listOf("支付宝", "支付方式", "支付设置")
    private val RESOURCE_WORDS: Map<String, IrrevAction> = mapOf(
        "send" to IrrevAction.send, "reply" to IrrevAction.send, "pay" to IrrevAction.pay,
        "delete" to IrrevAction.delete, "remove" to IrrevAction.delete, "checkout" to IrrevAction.order,
    )
    private val SEARCH_FIELD = listOf("search", "搜索")
    private val SEARCH_BUTTONS = setOf("搜索", "search")
    private val ACTIVATING_KEYS = setOf("enter", "center", "space")
    private const val SWIPE_DELETE_MIN = 400
    private const val ROW_MIN_WIDTH = 600
    private const val ROW_MAX_HEIGHT = 150
    private const val CAROUSEL_MAX_HEIGHT = 400
    private val FIELD_CLEAR_WORDS = setOf("清空", "清除", "清空输入", "清除输入", "清空搜索", "清除搜索", "清空内容", "清除内容", "clear", "cleartext", "clearsearch", "clearquery", "×", "✕", "x")
    private const val FIELD_NEAR = 80
    /** Payment page words (from Mika's ScreenDiagnosis). */
    val PAYMENT_TEXT = listOf("支付密码", "确认支付", "确认付款", "立即支付", "输入密码支付", "payment password", "confirm payment", "pay now")

    /** Null when the step is not irreversible on [s]. [target] is the element the step resolves to, when known. */
    fun classify(step: PhoneStep, s: ScreenSnapshot?, target: UiElement? = null): Irreversible? = when (step.kind) {
        StepKind.tap, StepKind.long_press -> tapHit(step, s, target)
        StepKind.type -> if (step.enter) enterHit(step, s, target, "enter") else null
        StepKind.key -> step.key?.takeIf { it in ACTIVATING_KEYS }?.let { enterHit(step, s, target, it) }
        StepKind.swipe -> swipeHit(step, s)
        else -> null
    }

    /** This screen is a payment page/sheet: payment wording, or an amount on a pay-like sheet. */
    fun paymentPage(s: ScreenSnapshot): String? {
        val text = s.texts.joinToString("\n").lowercase()
        PAYMENT_TEXT.firstOrNull { it in text }?.let { return it }
        val shown = s.elements.filter { !it.editable && !it.hint }
        val amount = shown.any { e -> TOTAL_LABELS.any { it in e.name } || AMOUNT.containsMatchIn(rowText(e, shown)) }
        return if (amount && paySheet(shown)) "payment sheet with an amount" else null
    }

    fun matchName(name: String, screen: ScreenSnapshot? = null, hit: UiElement? = null): IrrevAction? {
        val raw = name.trim()
        if (raw.isEmpty()) return null
        val n = raw.filterNot(Char::isWhitespace).lowercase()
        if (n in SUBMIT_WORDS) return IrrevAction.submit
        if (contacts(raw)) return IrrevAction.submit
        if (n in RED_PACKET) return IrrevAction.pay
        if (EXACT_PAY.any { w -> EXACT_PAY_PREFIXES.any { n == it + w } }) return IrrevAction.pay
        strong(raw)?.let { return it }
        for ((action, words) in PREFIX_WORDS) for (w in words) if (n.startsWith(w) && n.length <= w.length + PREFIX_SLACK) return action
        for ((action, words) in PUNCT_WORDS) for (w in words) if (n.startsWith(w) && n.drop(w.length).none { it.isLetterOrDigit() }) return action
        if (BOOKING_WORDS.any { w -> BOOKING_PREFIXES.any { n == it + w } }) return IrrevAction.order
        val lower = raw.lowercase()
        for ((w, action) in ENGLISH) if (lower.startsWith(w) && lower.getOrNull(w.length)?.isLetter() != true) return action
        if (screen != null && n.trimEnd { !it.isLetterOrDigit() } in GENERIC) return contextAction(screen, hit)
        return null
    }

    private fun contacts(raw: String): Boolean {
        val n = raw.filterNot(Char::isWhitespace).lowercase().trimEnd { !it.isLetterOrDigit() }
        if (n in APPLY_WORDS || CONTACT_WORDS.any { w -> CONTACT_PREFIXES.any { n == it + w } }) return true
        val e = raw.lowercase().trim().trimEnd { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " ")
        return e in CONTACT_ENGLISH
    }

    private fun strong(label: String): IrrevAction? {
        for (p in listOf(label) + label.split(Regex("\\s+"))) {
            var t = p.filterNot(Char::isWhitespace).replace(PAYMENT_ADS, "")
            EXCLUDED.forEach { t = t.replace(it, "") }
            if (t.isEmpty() || t.length > STRONG_MAX_LEN) continue
            STRONG.entries.firstOrNull { (w, _) -> occurrences(t, w).any { actionAt(t, w, it) } }?.let { return it.value }
        }
        return null
    }

    private fun occurrences(t: String, w: String) = generateSequence(t.indexOf(w).takeIf { it >= 0 }) { i -> t.indexOf(w, i + 1).takeIf { it >= 0 } }

    private fun actionAt(t: String, w: String, i: Int): Boolean {
        if (i > 0 && t[i - 1] in STATUS_PREFIXES) return false
        val rest = t.substring(i + w.length).dropWhile { !it.isLetterOrDigit() }
        return STATUS_SUFFIXES.none { rest.startsWith(it) }
    }

    private fun contextAction(screen: ScreenSnapshot, hit: UiElement?): IrrevAction? {
        val found = screen.elements.mapNotNull { strong(it.name) }
        if (IrrevAction.pay in found) return IrrevAction.pay
        found.firstOrNull { it != IrrevAction.order }?.let { return it }
        val shown = screen.elements.filter { !it.editable && !it.hint }
        val scrollables = screen.elements.filter { it.scrollable }
        val amount = shown.any { e ->
            TOTAL_LABELS.any { it in e.name } || (AMOUNT.containsMatchIn(rowText(e, shown)) && scrollables.none { s -> inside(e, s) && hit != null && !inside(hit, s) })
        }
        if (amount && paySheet(shown)) return IrrevAction.pay
        return if (IrrevAction.order in found || amount) IrrevAction.order else null
    }

    private fun paySheet(shown: List<UiElement>): Boolean {
        if (shown.any { e -> ORDER_CONTEXT.any { it in e.name.filterNot(Char::isWhitespace) } }) return false
        val bottomButton = shown.any { b ->
            b.clickable && top(b) >= BOTTOM_REGION && b.name.filterNot(Char::isWhitespace).lowercase().let { n -> n.trimEnd { !it.isLetterOrDigit() } in GENERIC || "支付" in n || "付款" in n }
        }
        return shown.any { e ->
            val t = e.name.filterNot(Char::isWhitespace).replace(PAYMENT_ADS, "")
            PAY_SHEET_WORDS.any { it in t } || PAY_SHEET_ENGLISH.containsMatchIn(e.name) ||
                listOf("支付", "付款").any { w -> occurrences(t, w).any { actionAt(t, w, it) } } ||
                (bottomButton && top(e) >= BOTTOM_REGION && WEAK_PAY_SHEET_WORDS.any { it in t })
        }
    }

    internal fun rowText(start: UiElement, elements: List<UiElement>): String {
        if (start.bounds.size != 4) return start.name
        fun cy(e: UiElement) = (e.bounds[1] + e.bounds[3]) / 2
        val row = elements.filter { e ->
            e !== start && e.bounds.size == 4 && e.bounds[0] >= start.bounds[0] && cy(e) in start.bounds[1]..start.bounds[3] && cy(start) in e.bounds[1]..e.bounds[3]
        }.sortedBy { it.bounds[0] }
        return start.name + row.joinToString("") { it.name.filterNot(Char::isWhitespace) }
    }

    private fun top(e: UiElement) = if (e.bounds.size == 4) e.bounds[1] else 0
    private fun matchResource(id: String): IrrevAction? = resourceWords(id).firstNotNullOfOrNull { RESOURCE_WORDS[it] }
    private fun resourceWords(id: String): List<String> = id.substringAfterLast('/').replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().split('_', '-', '.')

    /** Tapping an input field or its clear button only edits text: not irreversible by itself. */
    internal fun editsField(e: UiElement, s: ScreenSnapshot): Boolean {
        if (e.editable) return true
        if (e.bounds.size != 4) return false
        val fields = s.elements.filter { it.editable && it.bounds.size == 4 && it !== e }
        val n = e.name.filterNot(Char::isWhitespace).lowercase()
        val clears = n in FIELD_CLEAR_WORDS || e.resourceId?.let { r -> "clear" in resourceWords(r) } == true
        val bareIcon = n.isEmpty() && e.resourceId?.let(::matchResource) == null
        if ((clears || bareIcon) && fields.any { inside(e, it) }) return true
        if (!clears) return false
        val cy = (e.bounds[1] + e.bounds[3]) / 2
        return fields.any { f -> cy in f.bounds[1]..f.bounds[3] && e.bounds[0] >= f.bounds[0] - FIELD_NEAR && e.bounds[0] <= f.bounds[2] + FIELD_NEAR }
    }

    private fun elementHit(e: UiElement, s: ScreenSnapshot): Irreversible? {
        for (x in withChildren(e, s)) {
            if (editsField(x, s)) continue
            matchName(x.name, s, x)?.let { return Irreversible(it, x.name.trim()) }
            x.resourceId?.let { r -> matchResource(r)?.let { return Irreversible(it, r) } }
        }
        return null
    }

    private fun tapHit(step: PhoneStep, s: ScreenSnapshot?, target: UiElement?): Irreversible? {
        val e = target ?: s?.let { at(it, step) }
        if (e != null && s != null) {
            if (editsField(e, s)) return null
            elementHit(e, s)?.let { return it }
            // A point on an unnamed element (self-drawn UI, icon): judge the text on the same row.
            if (step.ref == null && withChildren(e, s).all { it.name.isBlank() }) step.y?.let { y -> rowHit(s, y)?.let { return it } }
        } else if (s != null && step.y != null && step.ref == null) {
            rowHit(s, step.y)?.let { return it }
        }
        // The model's own description of the target also counts.
        return step.target?.let { t -> matchName(t, s)?.let { Irreversible(it, t.trim()) } }
    }

    private fun at(s: ScreenSnapshot, step: PhoneStep): UiElement? {
        step.ref?.let { r -> return s.byRef(r) }
        val x = step.x ?: return null
        val y = step.y ?: return null
        val under = s.elements.filter { contains(it, x, y) }
        return under.filter { it.clickable }.minByOrNull(::area) ?: under.minByOrNull(::area)
    }

    private fun rowHit(s: ScreenSnapshot, y: Int): Irreversible? = s.elements
        .filter { it.name.isNotBlank() && !it.editable && it.bounds.size == 4 && y in it.bounds[1]..it.bounds[3] && height(it) <= ROW_MAX_HEIGHT }
        .filterNot { editsField(it, s) }
        .firstNotNullOfOrNull { e -> matchName(e.name, s, e)?.let { Irreversible(it, e.name.trim()) } }

    private fun withChildren(e: UiElement, s: ScreenSnapshot): List<UiElement> {
        if (e.name.isNotBlank() || !e.clickable) return listOf(e)
        return listOf(e) + s.elements.filter { it !== e && !it.clickable && it.name.isNotBlank() && inside(it, e) }
    }

    /**
     * Enter/center/space act on the focus: a non-search input means "send"; a focused button is judged
     * by its name; focus unknown and not every field on screen is a search box → flagged.
     */
    private fun enterHit(step: PhoneStep, s: ScreenSnapshot?, target: UiElement?, key: String): Irreversible? {
        val keyName = if (key == "space") "空格" else "回车"
        val field = target ?: step.ref?.let { s?.byRef(it) }
        val focused = field ?: s?.elements?.firstOrNull { it.focused }
        if (focused != null) {
            if (focused.editable) {
                if (key == "space" || isSearch(focused, s)) return null
                return Irreversible(IrrevAction.send, "$keyName:${label(focused)}")
            }
            return s?.let { elementHit(focused, it) }
        }
        val fields = s?.elements?.filter { it.editable }.orEmpty()
        if (fields.isNotEmpty() && fields.all { isSearch(it, s) }) return null
        return Irreversible(IrrevAction.send, "$keyName:焦点不明")
    }

    private fun isSearch(e: UiElement, s: ScreenSnapshot?): Boolean {
        if (s != null && row(e, s).any { b -> b.clickable && !b.editable && sendLike(b.name) }) return false
        if (e.searchField || "search" in e.role.lowercase()) return true
        val hint = listOfNotNull(e.name.takeIf { e.hint }, e.placeholder).joinToString(" ").lowercase()
        if (SEARCH_FIELD.any { it in hint } || e.resourceId?.let { "search" in resourceWords(it) } == true) return true
        if (s == null || e.bounds.size != 4) return false
        return s.elements.any { b ->
            b !== e && b.clickable && !b.editable && b.bounds.size == 4 && b.name.filterNot(Char::isWhitespace).lowercase() in SEARCH_BUTTONS && (b.bounds[1] + b.bounds[3]) / 2 in e.bounds[1]..e.bounds[3]
        }
    }

    private fun row(e: UiElement, s: ScreenSnapshot): List<UiElement> {
        if (e.bounds.size != 4) return emptyList()
        val cy = (e.bounds[1] + e.bounds[3]) / 2
        return s.elements.filter { o -> o !== e && o.bounds.size == 4 && ((o.bounds[1] + o.bounds[3]) / 2 in e.bounds[1]..e.bounds[3] || cy in o.bounds[1]..o.bounds[3]) }
    }

    private fun sendLike(name: String): Boolean {
        val n = name.filterNot(Char::isWhitespace).lowercase()
        if (n.isEmpty()) return false
        return PREFIX_WORDS.getValue(IrrevAction.send).any { n.startsWith(it) && n.length <= it.length + PREFIX_SLACK } || n == "send" || n == "post"
    }

    /** A long horizontal swipe starting on a list row: many apps delete a row this way. */
    private fun swipeHit(step: PhoneStep, s: ScreenSnapshot?): Irreversible? {
        val x1 = step.x ?: return null; val y1 = step.y ?: return null; val x2 = step.x2 ?: return null; val y2 = step.y2 ?: return null
        val dx = abs(x2 - x1)
        if (dx < SWIPE_DELETE_MIN || dx <= abs(y2 - y1)) return null
        s ?: return null
        if (s.elements.any { it.scrollable && contains(it, x1, y1) && height(it) < CAROUSEL_MAX_HEIGHT }) return null
        val row = s.elements.filter { contains(it, x1, y1) && it.clickable && !it.scrollable && width(it) >= ROW_MIN_WIDTH }.minByOrNull(::area) ?: return null
        return Irreversible(IrrevAction.delete, "swipe on ${label(row)}")
    }

    private fun label(e: UiElement) = e.name.trim().ifEmpty { e.resourceId ?: e.ref }
    private fun contains(e: UiElement, x: Int, y: Int) = e.bounds.size == 4 && x in e.bounds[0]..e.bounds[2] && y in e.bounds[1]..e.bounds[3]
    private fun inside(child: UiElement, parent: UiElement): Boolean {
        val c = child.bounds; val p = parent.bounds
        return c.size == 4 && p.size == 4 && c[0] >= p[0] && c[1] >= p[1] && c[2] <= p[2] && c[3] <= p[3]
    }
    private fun height(e: UiElement) = if (e.bounds.size != 4) 0 else e.bounds[3] - e.bounds[1]
    private fun width(e: UiElement) = if (e.bounds.size != 4) 0 else e.bounds[2] - e.bounds[0]
    private fun area(e: UiElement): Long = if (e.bounds.size != 4) Long.MAX_VALUE else (e.bounds[2] - e.bounds[0]).toLong() * (e.bounds[3] - e.bounds[1])
    @Suppress("unused") private fun clamp(v: Int) = max(0, min(999, v))
}
