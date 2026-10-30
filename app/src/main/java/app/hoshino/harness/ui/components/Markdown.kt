package app.hoshino.harness.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.hoshino.harness.ui.theme.HIcons
import app.hoshino.harness.ui.theme.HoshinoColors
import app.hoshino.harness.ui.theme.HoshinoShapes
import app.hoshino.harness.ui.theme.HoshinoTheme
import app.hoshino.harness.ui.theme.MonoFamily

/** 轻量 Markdown 渲染：块结构见 [MdBlock]/[parseMarkdown]（MarkdownParser.kt）；行内 `code` / **粗体** / *斜体*。 */
fun inlineMarkdown(text: String, c: HoshinoColors): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) && text.indexOf("**", i + 2) > 0 -> {
                val end = text.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                i = end + 2
            }
            text[i] == '`' && text.indexOf('`', i + 1) > 0 -> {
                val end = text.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = MonoFamily, background = c.surface2, color = c.text)) {
                    append("\u2009"); append(text.substring(i + 1, end)); append("\u2009")
                }
                i = end + 1
            }
            text[i] == '*' && i + 1 < text.length && text[i + 1] != ' ' && text.indexOf('*', i + 1) > 0 -> {
                val end = text.indexOf('*', i + 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                i = end + 1
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

/**
 * 渲染 Markdown。流式回答传入 [StreamText]：[IncrementalMarkdown] 只重解析最后一个变化块，
 * 已稳定的块是同一实例/相等的不可变数据，对应的子组合直接跳过重组。
 */
@Composable
fun MarkdownText(markdown: CharSequence, modifier: Modifier = Modifier) {
    val parser = remember { IncrementalMarkdown() }
    val parsed = remember(markdown) { parser.update(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parsed.stable.forEachIndexed { i, b -> key("s$i") { MdBlockView(b) } }
        parsed.tail.forEachIndexed { i, b -> key("t$i") { MdBlockView(b) } }
    }
}

@Composable
private fun MdBlockView(b: MdBlock) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    when (b) {
        is MdBlock.Heading -> Text(
            remember(b, c) { inlineMarkdown(b.text, c) },
            style = when (b.level) {
                1 -> t.headline
                2 -> t.title
                else -> t.bodyMedium
            },
            color = c.text,
            modifier = Modifier.padding(top = 4.dp),
        )
        is MdBlock.Paragraph -> Text(remember(b, c) { inlineMarkdown(b.text, c) }, style = t.body, color = c.text)
        is MdBlock.ListItem -> Row {
            Text(b.marker, style = t.body, color = c.text2, modifier = Modifier.widthIn(min = 18.dp))
            Spacer(Modifier.width(4.dp))
            Text(remember(b, c) { inlineMarkdown(b.text, c) }, style = t.body, color = c.text)
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            VHairline(Modifier.padding(vertical = 2.dp), color = c.text3)
            Spacer(Modifier.width(10.dp))
            Text(remember(b, c) { inlineMarkdown(b.text, c) }, style = t.body, color = c.text2)
        }
        is MdBlock.Code -> CodeBlock(b.code, b.lang)
        is MdBlock.Table -> MdTable(b)
        MdBlock.Rule -> Hairline()
    }
}

@Composable
fun CodeBlock(code: String, lang: String = "", modifier: Modifier = Modifier) {
    val c = HoshinoTheme.colors
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if(copied) { delay(1500);copied=false } }
    Column(modifier.fillMaxWidth().clip(HoshinoShapes.card).background(c.surface2).border(1.dp, c.line, HoshinoShapes.card)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(lang.ifBlank { "code" }, style = HoshinoTheme.type.codeSmall, color = c.text3, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(HoshinoShapes.small).clickable { copyToClipboard(ctx, code);copied=true }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HIcon(if(copied)HIcons.Check else HIcons.Copy, size = 14.dp, tint = c.text3)
                Spacer(Modifier.width(4.dp))
                Text(if(copied)"已复制" else "复制", style = HoshinoTheme.type.caption, color = c.text3)
            }
        }
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            SelectionContainer { Text(code, style = HoshinoTheme.type.code, color = c.text, softWrap = false) }
        }
    }
}

@Composable
private fun MdTable(table: MdBlock.Table) {
    val c = HoshinoTheme.colors
    val t = HoshinoTheme.type
    Column(Modifier.fillMaxWidth().hairlineCard(c.surface, c.line)) {
        Row(Modifier.fillMaxWidth().background(c.surface2).padding(horizontal = 12.dp, vertical = 8.dp)) {
            table.header.forEach { h ->
                Text(inlineMarkdown(h, c), style = t.caption.copy(fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
            }
        }
        table.rows.forEach { r ->
            Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                table.header.indices.forEach { idx ->
                    Text(inlineMarkdown(r.getOrElse(idx) { "" }, c), style = t.caption, color = c.text2, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
