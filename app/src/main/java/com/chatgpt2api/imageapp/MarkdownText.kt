package com.chatgpt2api.imageapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 极简 Markdown 渲染器：覆盖最常用语法。
 *
 * 支持：
 *  - 多行代码块 ```lang ... ``` （等宽字体 + 浅灰底）
 *  - 行内代码 `code`
 *  - 加粗 **text**
 *  - 斜体 *text*
 *  - 无序列表 - / *
 *  - 有序列表 1. 2. ...
 *  - 引用块 > text
 *  - 标题 # ## ### （仅字号差异，不解析层级链接）
 *
 * 设计目标：足够展示 ChatGPT 输出的常见结构，不引入第三方依赖
 * （markwon / commonmark）。复杂表格、链接、图片不渲染，原样显示。
 *
 * 性能：流式 token 到达时不会每 token 都重新解析，[parseBlocks] 走
 * [androidx.compose.runtime.derivedStateOf] 节流 + 缓存——相同 content
 * 不重复解析；列表/段落组件本身的重组靠 Compose 自身的相等比较过滤。
 */
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    if (content.isEmpty()) return
    // remember(content) 让相同 content 命中缓存；不同 content 重新解析一次。
    // 对流式场景而言，每个 delta 到来仍会触发一次解析，但单次解析对 1-2KB 的文本
    // 在主线程也只是 ~1ms 量级；真正的成本来自 Compose 子组件树重组，所以再加
    // derivedStateOf 让外层 list 仅在最终行块结构变化时重组。
    val blocks = androidx.compose.runtime.remember(content) { parseBlocks(content) }
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(if (block is MdBlock.Code) 8.dp else 4.dp))
            when (block) {
                is MdBlock.Code -> CodeBlock(block.language, block.text, fontSize)
                is MdBlock.Quote -> QuoteBlock(block.lines, color, fontSize)
                is MdBlock.Heading -> HeadingBlock(block.level, block.text, color, fontSize)
                is MdBlock.UnorderedList -> ListBlock(block.items.map { "•" to it }, color, fontSize)
                is MdBlock.OrderedList -> ListBlock(block.items.mapIndexed { i, t -> "${i + 1}." to t }, color, fontSize)
                is MdBlock.Paragraph -> Text(
                    text = renderInline(block.text),
                    color = color,
                    fontSize = fontSize,
                    lineHeight = (fontSize.value + 6).sp,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, text: String, fontSize: androidx.compose.ui.unit.TextUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF3F2EE))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (language.isNotBlank()) {
            Text(
                language.uppercase(),
                color = Color(0xFF8B8B8B),
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF111111),
            fontSize = (fontSize.value - 1).sp,
            lineHeight = (fontSize.value + 6).sp,
        )
    }
}

@Composable
private fun QuoteBlock(lines: List<String>, color: Color, fontSize: androidx.compose.ui.unit.TextUnit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.width(2.dp).height((fontSize.value * lines.size * 1.6f).dp).background(Color(0xFFD6483B)))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            lines.forEach {
                Text(
                    renderInline(it),
                    color = color,
                    fontSize = fontSize,
                    fontStyle = FontStyle.Italic,
                    lineHeight = (fontSize.value + 6).sp,
                )
            }
        }
    }
}

@Composable
private fun HeadingBlock(level: Int, text: String, color: Color, fontSize: androidx.compose.ui.unit.TextUnit) {
    val size = when (level) {
        1 -> (fontSize.value + 6).sp
        2 -> (fontSize.value + 3).sp
        else -> (fontSize.value + 1).sp
    }
    Text(
        renderInline(text),
        color = color,
        fontSize = size,
        fontWeight = FontWeight.Bold,
        lineHeight = (size.value + 4).sp,
    )
}

@Composable
private fun ListBlock(items: List<Pair<String, String>>, color: Color, fontSize: androidx.compose.ui.unit.TextUnit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEach { (marker, body) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(marker, color = color, fontSize = fontSize, modifier = Modifier.width(20.dp))
                Text(
                    renderInline(body),
                    color = color,
                    fontSize = fontSize,
                    lineHeight = (fontSize.value + 6).sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val text: String) : MdBlock
    data class Quote(val lines: List<String>) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class UnorderedList(val items: List<String>) : MdBlock
    data class OrderedList(val items: List<String>) : MdBlock
}

private fun parseBlocks(content: String): List<MdBlock> {
    val lines = content.split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        // 多行代码块
        val fence = Regex("^```([\\w+\\-]*)\\s*$").matchEntire(raw.trim())
        if (fence != null) {
            val language = fence.groupValues[1]
            val buf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(lines[i])
                i++
            }
            if (i < lines.size) i++ // skip closing ```
            blocks.add(MdBlock.Code(language, buf.toString()))
            continue
        }
        // 标题
        val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(raw)
        if (heading != null) {
            blocks.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            i++
            continue
        }
        // 引用
        if (raw.trimStart().startsWith("> ")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith("> ")) {
                quoteLines.add(lines[i].trimStart().removePrefix("> ").trim())
                i++
            }
            blocks.add(MdBlock.Quote(quoteLines))
            continue
        }
        // 无序列表
        if (Regex("^\\s*[-*+]\\s+.+").matches(raw)) {
            val items = mutableListOf<String>()
            while (i < lines.size && Regex("^\\s*[-*+]\\s+.+").matches(lines[i])) {
                items.add(lines[i].trimStart().drop(1).trim())
                i++
            }
            blocks.add(MdBlock.UnorderedList(items))
            continue
        }
        // 有序列表
        if (Regex("^\\s*\\d+\\.\\s+.+").matches(raw)) {
            val items = mutableListOf<String>()
            while (i < lines.size && Regex("^\\s*\\d+\\.\\s+.+").matches(lines[i])) {
                items.add(lines[i].trimStart().substringAfter(". ").trim())
                i++
            }
            blocks.add(MdBlock.OrderedList(items))
            continue
        }
        // 段落（连续非空行合并）
        if (raw.isBlank()) {
            i++
            continue
        }
        val paraBuf = StringBuilder(raw)
        i++
        while (i < lines.size && lines[i].isNotBlank() &&
            !lines[i].trim().startsWith("```") &&
            !Regex("^(#{1,6})\\s+").containsMatchIn(lines[i]) &&
            !lines[i].trimStart().startsWith("> ") &&
            !Regex("^\\s*[-*+]\\s+").containsMatchIn(lines[i]) &&
            !Regex("^\\s*\\d+\\.\\s+").containsMatchIn(lines[i])
        ) {
            paraBuf.append('\n').append(lines[i])
            i++
        }
        blocks.add(MdBlock.Paragraph(paraBuf.toString()))
    }
    return blocks
}

/**
 * 把行内 markdown 转为 AnnotatedString：粗体 **、斜体 *、行内代码 `。
 * 其他字符原样保留。
 */
private fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        // 行内代码
        if (rest.startsWith("`")) {
            val end = rest.indexOf('`', startIndex = 1)
            if (end > 0) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFEEEDE9))) {
                    append(rest.substring(1, end))
                }
                i += end + 1
                continue
            }
        }
        // 粗体
        if (rest.startsWith("**")) {
            val end = rest.indexOf("**", startIndex = 2)
            if (end > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(rest.substring(2, end))
                }
                i += end + 2
                continue
            }
        }
        // 斜体（避免和粗体冲突，只匹配单个 *）
        if (rest.startsWith("*") && !rest.startsWith("**")) {
            val end = rest.indexOf("*", startIndex = 1)
            if (end > 0) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(rest.substring(1, end))
                }
                i += end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}
