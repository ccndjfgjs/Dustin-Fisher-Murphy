package dev.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    maxLines = 1
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

@Composable
fun Chip(
    text: String,
    color: Color = Accent,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val base = modifier
        .clip(RoundedCornerShape(50))
        .background(color.copy(alpha = 0.14f))
        .padding(horizontal = 10.dp, vertical = 4.dp)
    Box(modifier = if (onClick != null) base.clickable { onClick() } else base) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun IconBubble(icon: ImageVector, tint: Color = Accent, onClick: (() -> Unit)? = null) {
    val modifier = Modifier
        .size(34.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(tint.copy(alpha = 0.12f))
        .padding(7.dp)
    Box(
        modifier = if (onClick != null) modifier.clickable { onClick() } else modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/** Мини-рендер markdown: блоки кода, инлайн-код, жирный и курсив. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val parts = remember(text) { splitCodeFences(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            if (part.isCode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0B0D12))
                        .padding(10.dp)
                ) {
                    if (part.language.isNotBlank()) {
                        Text(part.language, color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        part.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = Color(0xFFD7DEEA)
                    )
                }
            } else if (part.text.isNotBlank()) {
                Text(inlineMarkdown(part.text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class TextPart(val text: String, val isCode: Boolean, val language: String = "")

private fun splitCodeFences(text: String): List<TextPart> {
    val result = mutableListOf<TextPart>()
    val regex = Regex("```([\\w+.-]*)\\n?")
    var index = 0
    while (index < text.length) {
        val match = regex.find(text, index)
        if (match == null) {
            result.add(TextPart(text.substring(index), false))
            break
        }
        if (match.range.first > index) {
            result.add(TextPart(text.substring(index, match.range.first), false))
        }
        val lang = match.groupValues[1]
        val codeStart = match.range.last + 1
        val end = text.indexOf("```", codeStart)
        if (end < 0) {
            result.add(TextPart(text.substring(codeStart), true, lang))
            break
        }
        result.add(TextPart(text.substring(codeStart, end), true, lang))
        index = end + 3
    }
    return result
}

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    var bold = false
    var italic = false
    val code = StringBuilder()
    var inCode = false
    while (i < text.length) {
        val c = text[i]
        when {
            c == '`' -> {
                inCode = !inCode
                if (!inCode) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF232734))) {
                        append(code.toString())
                    }
                    code.clear()
                }
                i++
            }
            inCode -> {
                code.append(c)
                i++
            }
            c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                bold = !bold
                i += 2
            }
            c == '*' -> {
                italic = !italic
                i++
            }
            else -> {
                withStyle(
                    SpanStyle(
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
                    )
                ) { append(c) }
                i++
            }
        }
    }
    if (inCode && code.isNotEmpty()) append(code.toString())
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

fun stripAnsi(input: String): String = input.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")
