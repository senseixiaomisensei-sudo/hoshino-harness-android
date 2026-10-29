package app.hoshino.harness.ui.extensions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hoshino.harness.ui.ConfigViewModel
import app.hoshino.harness.ui.components.BtnStyle
import app.hoshino.harness.ui.components.EmptyState
import app.hoshino.harness.ui.components.HButton
import app.hoshino.harness.ui.components.HDialog
import app.hoshino.harness.ui.components.HIcon
import app.hoshino.harness.ui.components.HSheet
import app.hoshino.harness.ui.components.HTextField
import app.hoshino.harness.ui.components.Hairline
import app.hoshino.harness.ui.components.IconBtn
import app.hoshino.harness.ui.components.Pill
import app.hoshino.harness.ui.components.Tag
import app.hoshino.harness.ui.components.TextInputDialog
import app.hoshino.harness.ui.components.hairlineCard
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ================================================================ 插件市场（发现）
// 读取与电脑版 DSH「插件 → 发现」相同的 format 1 插件索引；搜索、分页、排序都在内核侧完成，界面只拿当前一页。

@Immutable
data class MarketSource(val url: String, val title: String, val count: Int?, val error: String?)

@Immutable
data class MarketCard(
    val name: String, val title: String, val description: String, val icon: String, val author: String,
    val category: String, val recommended: String?, val compatible: Boolean, val status: String, val installedVersion: String?,
)

@Immutable
data class MarketOption(val id: String, val title: String)

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)
private fun parseSources(o: JSONObject) = o.optJSONArray("sources").objects().map { MarketSource(it.getString("url"), it.str("title").ifBlank { it.getString("url") }, if (it.isNull("count")) null else it.optInt("count"), it.str("error").ifBlank { null }) }
private fun parseCard(o: JSONObject) = MarketCard(
    o.getString("name"), o.str("title").ifBlank { o.getString("name") }, o.str("description"), o.str("icon"), o.str("author"),
    o.str("category"), o.str("recommended").ifBlank { null }, o.optBoolean("compatible", true), o.str("status"), o.str("installedVersion").ifBlank { null },
)

private const val PAGE = 30

@Composable
fun ColumnScope.PluginMarketTab(vm: ConfigViewModel) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<MarketSource>>(emptyList()) }
    var sourceUrl by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("") }
    var categories by remember { mutableStateOf<List<MarketOption>>(emptyList()) }
    var sortKeys by remember { mutableStateOf<List<MarketOption>>(emptyList()) }
    var rows by remember { mutableStateOf<List<MarketCard>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var manage by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<MarketCard?>(null) }
    var confirm by remember { mutableStateOf<Pair<MarketCard, String?>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()

    LaunchedEffect(Unit) {
        runCatching { parseSources(vm.client.market("sources")) }
            .onSuccess { sources = it; if (sourceUrl.isBlank() || it.none { s -> s.url == sourceUrl }) sourceUrl = it.firstOrNull()?.url.orEmpty() }
            .onFailure { error = it.message ?: "内核未就绪" }
    }

    suspend fun page(offset: Int, refresh: Boolean = false) {
        if (sourceUrl.isBlank()) return
        loading = true
        val args = JSONObject().put("url", sourceUrl).put("q", query).put("offset", offset).put("limit", PAGE)
        if (category.isNotBlank()) args.put("category", category)
        if (sort.isNotBlank()) args.put("sort", sort)
        if (refresh) args.put("refresh", true)
        runCatching { vm.client.market("query", args) }
            .onSuccess { r ->
                val got = r.optJSONArray("plugins").objects().map(::parseCard)
                rows = if (offset == 0) got else (rows + got).distinctBy { it.name }
                total = r.optInt("total")
                categories = r.optJSONArray("categories").objects().map { MarketOption(it.getString("id"), it.str("title")) }
                sortKeys = listOf(MarketOption("", "默认"), MarketOption("publish", "发布时间")) + r.optJSONArray("sortKeys").objects().map { MarketOption(it.getString("id"), it.str("title")) }
                error = null
            }
            .onFailure { if (offset == 0) { rows = emptyList(); total = 0 }; error = it.message ?: "索引读取失败" }
        loading = false
    }

    // 输入防抖：停止输入 300ms 后才查询，避免每个字符都打到内核。
    LaunchedEffect(sourceUrl, query, category, sort, reload) {
        delay(if (query.isBlank()) 0 else 300)
        page(0, refresh = reload > 0)
        list.scrollToItem(0)
    }
    val nearEnd by remember { derivedStateOf { val info = list.layoutInfo; val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0; info.totalItemsCount > 0 && last >= info.totalItemsCount - 6 } }
    LaunchedEffect(list) {
        snapshotFlow { nearEnd }.distinctUntilChanged().filter { it }.collect { if (!loading && rows.size < total) page(rows.size) }
    }

    val current = sources.firstOrNull { it.url == sourceUrl }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(Modifier.clip(HoshinoShapes.small).clickable { sourceMenu = true }.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                HIcon(HIcons.Globe, size = 16.dp, tint = c.text2)
                Spacer(Modifier.width(6.dp))
                Text(current?.title ?: "选择索引源", style = t.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                HIcon(HIcons.ChevronDown, size = 16.dp, tint = c.text3)
            }
            DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                sources.forEach { s ->
                    DropdownMenuItem(text = { Text(s.title, style = t.body, color = if (s.url == sourceUrl) c.accent else c.text) }, onClick = { sourceMenu = false; category = ""; sort = ""; sourceUrl = s.url })
                }
                DropdownMenuItem(text = { Text("管理索引源…", style = t.body, color = c.text) }, leadingIcon = { HIcon(HIcons.Settings, size = 18.dp) }, onClick = { sourceMenu = false; manage = true })
            }
        }
        Box {
            IconBtn(HIcons.Sort, "排序", { sortMenu = true }, tint = if (sort.isBlank()) c.text3 else c.accent)
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                sortKeys.ifEmpty { listOf(MarketOption("", "默认"), MarketOption("publish", "发布时间")) }.forEach { k ->
                    DropdownMenuItem(text = { Text(k.title, style = t.body, color = if (k.id == sort) c.accent else c.text) }, onClick = { sortMenu = false; sort = k.id })
                }
            }
        }
        IconBtn(HIcons.Refresh, "刷新索引", { reload++ }, tint = c.text3)
    }
    HTextField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), placeholder = "搜索名称、作者、关键词", leading = HIcons.Search)
    if (categories.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("全部", selected = category.isBlank(), onClick = { category = "" })
        categories.forEach { cat -> Pill(cat.title, selected = category == cat.id, onClick = { category = cat.id }) }
    }
    Text(
        when { busy != null -> busy!!; loading && rows.isEmpty() -> "正在读取索引…"; else -> "共 $total 个插件" + (current?.let { " · 来自 ${it.title}" } ?: "") },
        style = t.caption, color = if (busy != null) c.accent else c.text3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    Box(Modifier.fillMaxWidth().weight(1f)) {
        when {
            sources.isEmpty() && error == null -> EmptyState("正在连接内核…", icon = HIcons.Plug)
            rows.isEmpty() && error != null -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState("索引读取失败", subtitle = error, icon = HIcons.Warning)
                HButton("重试", { reload++ }, compact = true)
            }
            rows.isEmpty() && !loading -> EmptyState("没有匹配的插件", icon = HIcons.Search)
            else -> LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.name }, contentType = { "card" }) { p ->
                    MarketCardRow(p, enabled = busy == null, onOpen = { detail = p }, onInstall = { confirm = p to null })
                }
                if (rows.size < total) item(key = "more", contentType = "more") {
                    Text("正在加载更多…", style = t.caption, color = c.text3, modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
        }
    }

    fun install(p: MarketCard, version: String?) {
        busy = "正在安装 ${p.title}…（首次安装会下载 npm，可能需要几分钟）"
        scope.launch {
            val r = vm.client.installFromMarket(sourceUrl, p.name, version)
            busy = null
            vm.toast(r.fold({ "已安装 ${p.title}" }, { it.message?.take(400) ?: "安装失败" }))
            if (r.isSuccess) page(0)
        }
    }

    detail?.let { p -> MarketDetailSheet(vm, sourceUrl, p, onDismiss = { detail = null }, onInstall = { v -> detail = null; confirm = p to v }) }
    confirm?.let { (p, version) -> MarketInstallDialog(vm, sourceUrl, p, version, onDismiss = { confirm = null }, onConfirm = { v -> confirm = null; install(p, v) }) }
    if (manage) MarketSourcesSheet(vm, sources, onChanged = { list -> sources = list; if (list.none { it.url == sourceUrl }) sourceUrl = list.firstOrNull()?.url.orEmpty() }, onDismiss = { manage = false })
}

@Composable
private fun MarketCardRow(p: MarketCard, enabled: Boolean, onOpen: () -> Unit, onInstall: () -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    Row(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line).clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        MarketIcon(p.icon, p.title)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = t.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(p.author.ifBlank { null }, p.recommended?.let { "v$it" }).joinToString(" · ").ifBlank { p.name }, style = t.codeSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.description.isNotBlank()) Text(p.description, style = t.caption, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        when (p.status) {
            "installed" -> Tag("已安装", color = c.success)
            "provided" -> Tag("DSH 内置")
            "update" -> HButton("更新", onInstall, style = BtnStyle.Primary, compact = true, enabled = enabled)
            else -> if (p.compatible) HButton("安装", onInstall, compact = true, enabled = enabled) else Tag("不兼容", color = c.text3)
        }
    }
}

private val iconCache = LruCache<String, ImageBitmap>(160)

@Composable
private fun MarketIcon(url: String, title: String) {
    val c = HoshinoTheme.colors
    val bitmap by produceState<ImageBitmap?>(iconCache.get(url), url) {
        if (value != null || url.isBlank() || url.contains("svg", ignoreCase = true)) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { loadIcon(url) }.getOrNull() }?.also { iconCache.put(url, it) }
    }
    Box(Modifier.size(40.dp).clip(HoshinoShapes.small).background(c.accentSoft), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b, null, Modifier.size(40.dp), contentScale = ContentScale.Crop)
        else Text(title.trim().firstOrNull()?.uppercase() ?: "?", style = HoshinoTheme.type.title, color = c.accent)
    }
}

private fun loadIcon(url: String): ImageBitmap? {
    val bytes = if (url.startsWith("data:")) {
        val comma = url.indexOf(','); if (comma < 0 || !url.substring(0, comma).contains("base64")) return null
        Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
    } else {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input -> val out = java.io.ByteArrayOutputStream(); val b = ByteArray(16384); while (true) { val n = input.read(b); if (n < 0) break; out.write(b, 0, n); if (out.size() > 512 * 1024) return null }; out.toByteArray() }
        } finally { conn.disconnect() }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1; while (bounds.outWidth / (sample * 2) >= 96) sample *= 2
    val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return bmp.asImageBitmap()
}

@Composable
private fun MarketDetailSheet(vm: ConfigViewModel, source: String, p: MarketCard, onDismiss: () -> Unit, onInstall: (String) -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val uri = LocalUriHandler.current
    val info by produceState<Result<JSONObject>?>(null, p.name) { value = runCatching { vm.client.market("plugin", JSONObject().put("url", source).put("name", p.name)) } }
    HSheet(onDismiss = onDismiss, title = p.title) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(p.name, style = t.codeSmall, color = c.text3)
            if (p.description.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(p.description, style = t.body, color = c.text2) }
            val r = info
            when {
                r == null -> Text("正在读取…", style = t.caption, color = c.text3, modifier = Modifier.padding(vertical = 12.dp))
                r.isFailure -> Text(r.exceptionOrNull()?.message ?: "读取失败", style = t.caption, color = c.danger, modifier = Modifier.padding(vertical = 12.dp))
                else -> {
                    val o = r.getOrThrow()
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        o.str("homepage").takeIf { it.startsWith("http") }?.let { HButton("主页", { runCatching { uri.openUri(it) } }, icon = HIcons.Link, compact = true) }
                        o.str("repository").takeIf { it.startsWith("http") }?.let { HButton("源代码", { runCatching { uri.openUri(it) } }, icon = HIcons.Code, compact = true) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("版本", style = t.caption, color = c.text3)
                    Spacer(Modifier.height(4.dp))
                    o.optJSONArray("versions").objects().forEachIndexed { i, v ->
                        if (i > 0) Hairline()
                        val ok = v.opt("compatible") != false
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(v.getString("version"), style = t.code, color = c.text)
                                    if (v.optBoolean("prerelease")) { Spacer(Modifier.width(6.dp)); Tag("预发布") }
                                    if (v.getString("version") == p.installedVersion) { Spacer(Modifier.width(6.dp)); Tag("当前", color = c.success) }
                                }
                                Text(v.str("source") + (if (!ok) " · 需要 DSH ${v.str("engines")}" else ""), style = t.caption, color = if (ok) c.text3 else c.warning, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (ok && p.status != "provided") HButton("安装", { onInstall(v.getString("version")) }, compact = true)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MarketInstallDialog(vm: ConfigViewModel, source: String, p: MarketCard, version: String?, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val info by produceState<Result<JSONObject>?>(null, p.name) { value = runCatching { vm.client.market("plugin", JSONObject().put("url", source).put("name", p.name)) } }
    val chosen = version ?: p.recommended
    val src = info?.getOrNull()?.optJSONArray("versions").objects().firstOrNull { it.optString("version") == chosen }?.str("source")
    HDialog("安装 ${p.title}？", onDismiss = onDismiss, confirmText = "安装", confirmEnabled = src != null, onConfirm = { onConfirm(chosen) }) {
        Text("版本 ${chosen ?: "—"}", style = t.body, color = c.text)
        Text(src ?: info?.exceptionOrNull()?.message ?: "正在读取安装来源…", style = t.codeSmall, color = c.text2)
        Text("插件索引是第三方内容。确认来源可信后再安装；安装会通过 npm/git 下载并在内核中运行它的代码。", style = t.caption, color = c.text3)
    }
}

@Composable
private fun MarketSourcesSheet(vm: ConfigViewModel, sources: List<MarketSource>, onChanged: (List<MarketSource>) -> Unit, onDismiss: () -> Unit) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    HSheet(onDismiss = onDismiss, title = "索引源") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            sources.forEachIndexed { i, s ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = t.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.url, style = t.codeSmall, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.error != null) Text(s.error, style = t.caption, color = c.danger, maxLines = 2) else if (s.count != null) Text("${s.count} 个插件", style = t.caption, color = c.text3)
                    }
                    IconBtn(HIcons.Trash, "移除", {
                        scope.launch { runCatching { parseSources(vm.client.market("source-remove", JSONObject().put("url", s.url))) }.onSuccess(onChanged).onFailure { vm.toast(it.message ?: "移除失败") } }
                    }, tint = c.danger)
                }
            }
            Spacer(Modifier.height(8.dp))
            HButton(if (working) "正在验证索引…" else "添加索引源", { adding = true }, Modifier.fillMaxWidth(), icon = HIcons.Plus, enabled = !working)
            Spacer(Modifier.height(6.dp))
            Text("与电脑版 DSH 相同的 format 1 索引文件，例如 https://cdn.jsdelivr.net/npm/dsh-plugin-catalog/index.json", style = t.caption, color = c.text3)
            Spacer(Modifier.height(8.dp))
        }
    }
    if (adding) TextInputDialog("添加索引源", "https://", onDismiss = { adding = false }, onConfirm = { url ->
        adding = false; working = true
        scope.launch {
            runCatching { parseSources(vm.client.market("source-add", JSONObject().put("url", url))) }
                .onSuccess { onChanged(it); vm.toast("已添加索引源") }
                .onFailure { vm.toast(it.message ?: "索引无效") }
            working = false
        }
    }, placeholder = "https://example.com/index.json", mono = true, hint = "必须是 http(s) 地址，不能含账号密码或 # 片段")
}
