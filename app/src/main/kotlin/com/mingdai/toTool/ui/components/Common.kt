package com.mingdai.toTool.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.mingdai.toTool.MainActivity
import com.mingdai.toTool.R
import com.mingdai.toTool.data.LocalFavorites
import com.mingdai.toTool.data.Tool
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 剪贴板走 Binder，单次传输上限约 1 MB；内容再大会抛 TransactionTooLargeException 直接闪退。
 * 超过 [CLIPBOARD_MAX_CHARS] 个字符就不复制，改为提示。
 */
private const val CLIPBOARD_MAX_CHARS = 300_000

/** Copy [text] to the system clipboard and show a confirmation toast. */
fun copyText(context: Context, text: String) {
    if (text.length > CLIPBOARD_MAX_CHARS) {
        Toast.makeText(context, "内容太长（超过 ${CLIPBOARD_MAX_CHARS / 10_000} 万字符），剪贴板放不下", Toast.LENGTH_LONG).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    try {
        clipboard.setPrimaryClip(ClipData.newPlainText("toTool", text))
        Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    } catch (e: RuntimeException) {
        Toast.makeText(context, "复制失败：内容太大", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Request the launcher to pin a shortcut to this tool on the home screen.
 * Tapping the shortcut reopens [MainActivity] and jumps straight to the tool.
 */
fun pinToolShortcut(context: Context, tool: Tool) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        Toast.makeText(context, context.getString(R.string.action_pin_unsupported), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(MainActivity.EXTRA_TOOL_ID, tool.id)
    val shortcut = ShortcutInfoCompat.Builder(context, "tool_${tool.id}")
        .setShortLabel(tool.name)
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
}

/** Standard scaffold for a single tool screen: back + title + favorite toggle. */
@Composable
fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    favoriteId: String,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val favorites = LocalFavorites.current
    val isFavorite = favorites.isFavorite(favoriteId)
    Scaffold(
        modifier = modifier,
        topBar = {
            SmallTopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { favorites.toggle(favoriteId) }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = stringResource(R.string.action_favorite),
                            tint = if (isFavorite) FavoriteStarColor
                                   else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                },
            )
        },
    ) { padding -> content(padding) }
}

/** Scrollable column with consistent padding — the body of most tool screens. */
@Composable
fun ToolBody(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

/**
 * A grid card for one tool.
 *
 * Long-press opens a context menu to favorite / un-favorite or pin the tool to
 * the home screen. In [compact] mode the card shows only the icon and name
 * (no favorite star, no description) for a denser layout.
 */
@Composable
fun ToolCard(
    tool: Tool,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val favorites = LocalFavorites.current
    val context = LocalContext.current
    val isFavorite = favorites.isFavorite(tool.id)
    var menuOpen by remember { mutableStateOf(false) }
    val favoriteLabel = if (isFavorite) stringResource(R.string.action_unfavorite)
                        else stringResource(R.string.action_favorite)
    val pinLabel = stringResource(R.string.action_pin_home)

    Box(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true,
            onClick = onClick,
            onLongPress = { menuOpen = true },
        ) {
            if (compact) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tool.name,
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = tool.icon,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = tool.name,
                            style = MiuixTheme.textStyles.headline2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { favorites.toggle(tool.id) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = favoriteLabel,
                                tint = if (isFavorite) FavoriteStarColor
                                       else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tool.desc,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        OverlayListPopup(
            show = menuOpen,
            alignment = PopupPositionProvider.Align.Start,
            onDismissRequest = { menuOpen = false },
        ) {
            ListPopupColumn {
                DropdownImpl(
                    text = favoriteLabel,
                    optionSize = 2,
                    isSelected = false,
                    index = 0,
                    onSelectedIndexChange = { favorites.toggle(tool.id); menuOpen = false },
                )
                DropdownImpl(
                    text = pinLabel,
                    optionSize = 2,
                    isSelected = false,
                    index = 1,
                    onSelectedIndexChange = { pinToolShortcut(context, tool); menuOpen = false },
                )
            }
        }
    }
}

/** 结果框最多排版这么多字符；整段几 MB 的文本交给 Text 排版会把内存和主线程拖垮。 */
private const val RESULT_DISPLAY_CHARS = 20_000

private fun clipForDisplay(text: String): String =
    if (text.length <= RESULT_DISPLAY_CHARS) text
    else text.substring(0, RESULT_DISPLAY_CHARS) +
        "\n…（共 ${text.length} 个字符，这里只显示前 $RESULT_DISPLAY_CHARS 个）"

/** A boxed output row with a copy button. Used by text/conversion tools. */
@Composable
fun ResultBox(
    text: String,
    label: String? = null,
    monospace: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(12.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.secondaryContainer,
            contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = if (text.isEmpty()) context.getString(R.string.placeholder_empty) else clipForDisplay(text),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSecondaryContainer,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                modifier = Modifier.weight(1f),
            )
            if (text.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { copyText(context, text) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = context.getString(R.string.action_copy),
                        tint = MiuixTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Small section title used inside tool screens. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.primary,
    )
}
