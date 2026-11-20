package com.mingdai.toTool.gacha

import android.net.Uri
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GachaLinkResult(val url: String, val game: String)

data class GachaWatchState(
    val running: Boolean = false,
    val waiting: Boolean = false,
    val link: String? = null,
    val game: String? = null,
    val error: String? = null,
)

internal object GachaLinkBridge {
    val state = MutableStateFlow(GachaWatchState())

    fun update(transform: (GachaWatchState) -> GachaWatchState) {
        state.value = transform(state.value)
    }
}

internal object GachaLinkParser {
    private val hosts = listOf("mihoyo.com", "hoyoverse.com", "aki-game.net", "aki-game.com")
    private val urlPattern = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)

    fun parse(line: String): GachaLinkResult? {
        if (!line.contains("gacha", ignoreCase = true) ||
            !line.contains("http", ignoreCase = true)
        ) {
            return null
        }
        val url = urlPattern.findAll(line)
            .map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }
            .lastOrNull { candidate ->
                val host = runCatching { Uri.parse(candidate).host }
                    .getOrNull()?.lowercase(Locale.ROOT).orEmpty()
                candidate.contains("gacha", ignoreCase = true) &&
                    hosts.any { host == it || host.endsWith(".$it") }
            } ?: return null
        return GachaLinkResult(url, gameLabel(url))
    }

    private fun gameLabel(url: String): String = when {
        url.contains("hk4e", ignoreCase = true) -> "原神"
        url.contains("hkrpg", ignoreCase = true) -> "崩坏：星穹铁道"
        url.contains("zzz", ignoreCase = true) || url.contains("nap", ignoreCase = true) -> "绝区零"
        url.contains("bh3", ignoreCase = true) -> "崩坏3"
        else -> "米游"
    }
}
