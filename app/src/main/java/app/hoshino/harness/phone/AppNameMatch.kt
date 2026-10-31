package app.hoshino.harness.phone

/**
 * m2.9.3: app name → package for phone_launch_app (pure, unit-tested). Before, only the exact package, the exact
 * launcher label or a single label containing the query matched, so 「WeChat」 / 「weixin」 / 「微信app」 / 「打开微信」
 * on a phone whose label is 「微信」 (or a ROM label such as 「微信 」 with odd spaces) found nothing.
 *
 * Order: exact package → exact label (whitespace/case-insensitive, after trimming 「打开」「App」「应用」…) → well-known alias
 * whose package is installed → the single label containing the query. Several
 * candidates on a fuzzy step → no guess (the caller lists them).
 */
object AppNameMatch {
    data class App(val label: String, val packageName: String)

    /** Common Chinese apps: names users and models use (zh / en / pinyin) → package. Only used when that package is installed. */
    val ALIASES: Map<String, String> = buildMap {
        fun add(pkg: String, vararg names: String) { for (n in names) put(key(n), pkg) }
        add("com.tencent.mm", "微信", "wechat", "weixin", "wx")
        add("com.tencent.mobileqq", "qq", "手机qq")
        add("com.eg.android.AlipayGphone", "支付宝", "alipay", "zhifubao")
        add("com.ss.android.ugc.aweme", "抖音", "douyin", "tiktok")
        add("com.taobao.taobao", "淘宝", "taobao")
        add("com.xingin.xhs", "小红书", "xiaohongshu", "rednote")
        add("com.sina.weibo", "微博", "weibo")
        add("tv.danmaku.bili", "哔哩哔哩", "b站", "bilibili")
        add("com.sankuai.meituan", "美团", "meituan")
        add("me.ele", "饿了么", "eleme")
        add("com.jingdong.app.mall", "京东", "jd", "jingdong")
        add("com.xunmeng.pinduoduo", "拼多多", "pinduoduo", "pdd")
        add("com.alibaba.android.rimet", "钉钉", "dingtalk")
        add("com.ss.android.lark", "飞书", "feishu", "lark")
        add("com.tencent.wework", "企业微信", "wecom", "wework")
        add("com.autonavi.minimap", "高德地图", "高德", "amap", "gaode")
        add("com.baidu.BaiduMap", "百度地图", "baidumap")
        add("com.netease.cloudmusic", "网易云音乐", "网易云", "cloudmusic")
        add("com.tencent.qqmusic", "qq音乐", "qqmusic")
        add("com.smile.gifmaker", "快手", "kuaishou")
        add("com.miui.gallery", "相册", "图库", "gallery")
        add("com.android.settings", "设置", "settings")
    }

    private val NOISE = Regex("""^(请|帮我|帮忙)?(打开|启动|进入|运行|open|launch|start)\s*|\s*(app|应用|软件|客户端|程序)$""", RegexOption.IGNORE_CASE)

    fun key(s: String): String = s.filterNot { it.isWhitespace() || it == '\u200B' || it == '\u00A0' }.lowercase()

    /** The query without 「打开」/「open」 in front and 「App」/「应用」 behind. */
    fun clean(query: String): String {
        var q = query.trim().trim('「', '」', '"', '\'', '“', '”', '《', '》')
        repeat(2) { q = NOISE.replace(q, "").trim() }
        return q
    }

    fun resolve(apps: List<App>, query: String, ownPackage: String? = null): String? {
        val raw = query.trim()
        if (raw.isEmpty()) return null
        apps.firstOrNull { it.packageName == raw }?.let { return it.packageName }
        if (raw == ownPackage) return raw
        val q = key(clean(raw))
        if (q.isEmpty()) return null
        apps.filter { key(it.label) == q }.let { if (it.size == 1) return it[0].packageName; if (it.size > 1) return it.firstOrNull { a -> ALIASES[q] == a.packageName }?.packageName }
        ALIASES[q]?.let { pkg -> apps.firstOrNull { it.packageName == pkg }?.let { return it.packageName } }
        return apps.filter { key(it.label).contains(q) }.singleOrNull()?.packageName
    }

    /** Up to [max] close candidates for an error message (labels containing the query or vice versa). */
    fun suggest(apps: List<App>, query: String, max: Int = 5): List<App> {
        val q = key(clean(query))
        if (q.isEmpty()) return emptyList()
        return apps.filter { val l = key(it.label); l.contains(q) || (l.length >= 2 && q.contains(l)) || it.packageName.lowercase().contains(q) }.take(max)
    }
}
