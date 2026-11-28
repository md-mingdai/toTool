package com.mingdai.toTool.markdown

import android.content.Context
import org.commonmark.Extension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Built-in Markdown rendering presets.
 *
 * The CSS lives in `assets/markdown/markdown.css`; changing [id] changes the
 * body class (`theme-github`, `theme-vitepress`, `theme-blog`). The background
 * colors are also used before the WebView paints its first frame and when
 * filling the export bitmap.
 */
enum class MarkdownStyle(
    val id: String,
    val label: String,
    val lightBackground: Int,
    val darkBackground: Int,
) {
    GITHUB(
        id = "github",
        label = "GitHub",
        lightBackground = 0xFFFFFFFF.toInt(),
        darkBackground = 0xFF0D1117.toInt(),
    ),
    VITEPRESS(
        id = "vitepress",
        label = "VitePress",
        lightBackground = 0xFFFFFFFF.toInt(),
        darkBackground = 0xFF1B1B1F.toInt(),
    ),
    BLOG(
        id = "blog",
        label = "博客",
        lightBackground = 0xFFEDEDED.toInt(),
        darkBackground = 0xFF19191D.toInt(),
    );

    fun background(dark: Boolean): Int = if (dark) darkBackground else lightBackground
}

/**
 * Converts Markdown to a self-contained HTML page.
 *
 * Raw HTML in the source is escaped (rather than passed through), and links are
 * sanitized by commonmark-java. That keeps a pasted document from injecting a
 * script into the preview WebView. Code highlighting and task-list classes are
 * added by scripts embedded in this page; highlight.js itself is bundled under
 * `assets/markdown/` and does not touch the network.
 */
object MarkdownRenderer {
    private val assetCache = HashMap<String, String>()

    fun render(
        context: Context,
        markdown: String,
        style: MarkdownStyle,
        dark: Boolean,
        viewportWidthCss: Int? = null,
    ): String {
        val body = if (markdown.isBlank()) {
            ""
        } else {
            val extensions: List<Extension> = listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
            )
            val parser = Parser.builder().extensions(extensions).build()
            val renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .build()
            val normalized = normalizeTables(markdown)
            val (mathPrepared, math) = protectMath(normalized)
            val (prepared, tables) = protectRawTables(mathPrepared)
            var rendered = renderer.render(parser.parse(prepared))
            tables.forEachIndexed { index, table ->
                rendered = rendered.replace("@@TTTABLE$index@@", table)
            }
            restoreMath(rendered, math)
        }
        val css = asset(context, "markdown/markdown.css")
        val highlight = asset(context, "markdown/highlight.min.js")
            .replace("</script", "<\\/script")
        val bodyClass = "theme-${style.id}" + if (dark) " dark" else ""
        val colorScheme = if (dark) "dark" else "light"
        val viewport = if (viewportWidthCss != null && viewportWidthCss > 0) {
            "width=$viewportWidthCss, initial-scale=1.0"
        } else {
            "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
        }

        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="$viewport">
            <meta name="color-scheme" content="$colorScheme">
            <link rel="stylesheet" href="markdown/katex/katex.min.css">
            <style>$css</style>
            </head>
            <body class="$bodyClass">
            <article class="markdown-body">$body</article>
            <script src="markdown/katex/katex.min.js"></script>
            <script>$highlight</script>
            <script>
            (function () {
                function decorate() {
                    try {
                        document.querySelectorAll('[data-tex]').forEach(function (node) {
                            var tex = node.getAttribute('data-tex') || '';
                            try {
                                if (window.katex) {
                                    katex.render(tex, node, {
                                        displayMode: node.classList.contains('math-display'),
                                        throwOnError: false,
                                        strict: false
                                    });
                                } else {
                                    node.textContent = tex;
                                }
                            } catch (e) {
                                node.textContent = tex;
                            }
                        });
                    } catch (e) {}
                    try {
                        if (window.hljs) hljs.highlightAll();
                    } catch (e) {}
                    try {
                        document.querySelectorAll('li > input[type="checkbox"]').forEach(function (box) {
                            if (box.parentElement) {
                                box.parentElement.classList.add('task-list-item');
                                if (box.parentElement.parentElement) {
                                    box.parentElement.parentElement.classList.add('task-list');
                                }
                            }
                        });
                    } catch (e) {}
                }
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', decorate);
                } else {
                    decorate();
                }
            })();
            </script>
            </body>
            </html>
        """.trimIndent().trimStart()
    }


private data class MathSnippet(val tex: String, val display: Boolean)

private const val MATH_TOKEN_PREFIX = "@@TTMATH"

/**
 * CommonMark 会把数学公式里的 `_`、`*` 当成强调语法，所以先把公式替换成占位符，
 * 渲染完成后再换回 KaTeX 的 data-tex 节点。扫描时跳过围栏代码和行内代码。
 */
private fun protectMath(markdown: String): Pair<String, List<MathSnippet>> {
    val snippets = mutableListOf<MathSnippet>()
    val out = StringBuilder(markdown.length)
    var i = 0
    var inFence = false
    var fenceMarker = ""
    while (i < markdown.length) {
        if (i == 0 || markdown[i - 1] == '\n') {
            val lineEnd = markdown.indexOf('\n', i).let { if (it < 0) markdown.length else it }
            val line = markdown.substring(i, lineEnd)
            val trimmed = line.trimStart()
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (inFence) {
                out.append(line)
                if (lineEnd < markdown.length) out.append('\n')
                if (marker == fenceMarker) inFence = false
                i = if (lineEnd < markdown.length) lineEnd + 1 else markdown.length
                continue
            }
            if (marker != null) {
                inFence = true
                fenceMarker = marker
                out.append(line)
                if (lineEnd < markdown.length) out.append('\n')
                i = if (lineEnd < markdown.length) lineEnd + 1 else markdown.length
                continue
            }
        }
        val ch = markdown[i]
        if (ch == '`') {
            var runEnd = i
            while (runEnd < markdown.length && markdown[runEnd] == '`') runEnd++
            val ticks = "`".repeat(runEnd - i)
            val close = markdown.indexOf(ticks, runEnd)
            if (close >= 0) {
                out.append(markdown, i, close + ticks.length)
                i = close + ticks.length
            } else {
                out.append(markdown, i, runEnd)
                i = runEnd
            }
            continue
        }
        if (ch == '\\' && i + 1 < markdown.length) {
            val next = markdown[i + 1]
            if (next == '(' || next == '[') {
                val closeToken = if (next == '(') "\\)" else "\\]"
                val end = findUnescaped(markdown, closeToken, i + 2)
                if (end > i + 2) {
                    snippets += MathSnippet(markdown.substring(i + 2, end), next == '[')
                    out.append(MATH_TOKEN_PREFIX).append(snippets.lastIndex).append("@@")
                    i = end + 2
                    continue
                }
            }
            out.append(ch).append(next)
            i += 2
            continue
        }
        if (ch == '$' && i + 1 < markdown.length) {
            if (markdown[i + 1] == '$') {
                val end = findUnescaped(markdown, "$$", i + 2)
                if (end > i + 2) {
                    snippets += MathSnippet(markdown.substring(i + 2, end), true)
                    out.append(MATH_TOKEN_PREFIX).append(snippets.lastIndex).append("@@")
                    i = end + 2
                    continue
                }
            } else {
                val end = findInlineDollar(markdown, i + 1)
                if (end > i + 1) {
                    snippets += MathSnippet(markdown.substring(i + 1, end), false)
                    out.append(MATH_TOKEN_PREFIX).append(snippets.lastIndex).append("@@")
                    i = end + 1
                    continue
                }
            }
        }
        out.append(ch)
        i++
    }
    return out.toString() to snippets
}


private fun protectRawTables(markdown: String): Pair<String, List<String>> {
    val tables = mutableListOf<String>()
    val out = StringBuilder(markdown.length)
    var i = 0
    var inFence = false
    var fenceMarker = ""
    while (i < markdown.length) {
        if (i == 0 || markdown[i - 1] == '\n') {
            val lineEnd = markdown.indexOf('\n', i).let { if (it < 0) markdown.length else it }
            val line = markdown.substring(i, lineEnd)
            val trimmed = line.trimStart()
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (inFence) {
                out.append(line)
                if (lineEnd < markdown.length) out.append('\n')
                if (marker == fenceMarker) inFence = false
                i = if (lineEnd < markdown.length) lineEnd + 1 else markdown.length
                continue
            }
            if (marker != null) {
                inFence = true
                fenceMarker = marker
                out.append(line)
                if (lineEnd < markdown.length) out.append('\n')
                i = if (lineEnd < markdown.length) lineEnd + 1 else markdown.length
                continue
            }
        }
        if (markdown[i] == '`') {
            var runEnd = i
            while (runEnd < markdown.length && markdown[runEnd] == '`') runEnd++
            val ticks = "`".repeat(runEnd - i)
            val close = markdown.indexOf(ticks, runEnd)
            if (close >= 0) {
                out.append(markdown, i, close + ticks.length)
                i = close + ticks.length
            } else {
                out.append(markdown, i, runEnd)
                i = runEnd
            }
            continue
        }
        val open = markdown.regionMatches(i, "<table", 0, 6, ignoreCase = true)
        val boundary = i + 6 >= markdown.length || markdown[i + 6].isWhitespace() || markdown[i + 6] == '>'
        if (open && boundary) {
            val end = markdown.indexOf("</table>", i, ignoreCase = true)
            if (end >= 0) {
                val raw = markdown.substring(i, end + 8)
                tables += sanitizeRawTable(raw)
                out.append("@@TTTABLE").append(tables.lastIndex).append("@@")
                i = end + 8
                continue
            }
        }
        out.append(markdown[i])
        i++
    }
    return out.toString() to tables
}

/** 只保留表格相关标签并去掉所有属性，避免把原始 HTML 直接注入 WebView。 */
private fun sanitizeRawTable(raw: String): String {
    var value = raw
        .replace(Regex("(?is)<script\\b.*?</script>"), "")
        .replace(Regex("(?is)<style\\b.*?</style>"), "")
        .replace(Regex("(?s)<!--.*?-->"), "")
    val allowed = "table|thead|tbody|tfoot|tr|th|td|caption|br|strong|em|b|i|code"
    value = value.replace(Regex("(?i)<(/?)\\s*($allowed)\\b[^>]*>")) { match ->
        "<${match.groupValues[1]}${match.groupValues[2].lowercase()}>"
    }
    value = value.replace(Regex("(?is)<(?!/?(?:$allowed)\\b)[^>]*>"), "")
    return value
}

private fun findUnescaped(text: String, token: String, start: Int): Int {
    var index = text.indexOf(token, start)
    while (index >= 0) {
        var backslashes = 0
        var cursor = index - 1
        while (cursor >= start && text[cursor] == '\\') {
            backslashes++
            cursor--
        }
        if (backslashes % 2 == 0) return index
        index = text.indexOf(token, index + 1)
    }
    return -1
}

private fun findInlineDollar(text: String, start: Int): Int {
    var index = start
    while (index < text.length) {
        val ch = text[index]
        if (ch == '\n') return -1
        if (ch == '$' && (index == start || text[index - 1] != '\\')) return index
        index++
    }
    return -1
}

private fun restoreMath(html: String, snippets: List<MathSnippet>): String {
    if (snippets.isEmpty()) return html
    var result = html
    snippets.forEachIndexed { index, snippet ->
        val css = if (snippet.display) "math-display" else "math-inline"
        result = result.replace(
            "$MATH_TOKEN_PREFIX$index@@",
            "<span class=\"$css\" data-tex=\"${escapeAttribute(snippet.tex)}\"></span>",
        )
    }
    return result
}

private fun escapeAttribute(value: String): String = buildString(value.length + 16) {
    value.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '\n' -> append("&#10;")
            '\r' -> append("&#13;")
            else -> append(ch)
        }
    }
}

/** GFM 表格要求前面有空行；用户直接接在段落后面时补一个空行。 */
private fun normalizeTables(markdown: String): String {
    val lines = markdown.split('\n')
    if (lines.size < 3) return markdown
    val out = mutableListOf<String>()
    var inFence = false
    for (line in lines) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) inFence = !inFence
        if (!inFence && out.size >= 2 && isTableDivider(line) &&
            isTableRow(out.last()) && out[out.size - 1].contains('|') &&
            out[out.size - 2].isNotBlank() && !isTableRow(out[out.size - 2])
        ) {
            val header = out.removeAt(out.lastIndex)
            out.add("")
            out.add(header)
        }
        out.add(line)
    }
    return out.joinToString("\n")
}

private fun isTableRow(line: String): Boolean = line.contains('|')

private fun isTableDivider(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains('-')) return false
    val cells = trimmed.trim('|').split('|')
    if (cells.isEmpty()) return false
    return cells.all { cell ->
        val value = cell.trim()
        value.matches(Regex(""":?-{3,}:?"""))
    }
}

    private fun asset(context: Context, name: String): String {
        synchronized(assetCache) {
            assetCache[name]?.let { return it }
        }
        val text = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        synchronized(assetCache) {
            assetCache[name] = text
        }
        return text
    }
}
