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
            renderer.render(parser.parse(markdown))
        }
        val css = asset(context, "markdown/markdown.css")
        val highlight = asset(context, "markdown/highlight.min.js")
            .replace("</script", "<\\/script")
        val bodyClass = "theme-${style.id}" + if (dark) " dark" else ""
        val colorScheme = if (dark) "dark" else "light"

        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <meta name="color-scheme" content="$colorScheme">
            <style>$css</style>
            </head>
            <body class="$bodyClass">
            <article class="markdown-body">$body</article>
            <script>$highlight</script>
            <script>
            (function () {
                function decorate() {
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
