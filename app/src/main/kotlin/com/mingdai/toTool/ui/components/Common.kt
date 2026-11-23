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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddToHomeScreen
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import com.mingdai.toTool.MainActivity
import com.mingdai.toTool.R
import com.mingdai.toTool.data.LocalFavorites
import com.mingdai.toTool.data.Tool
import com.mingdai.toTool.data.ToolRegistry
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

/**
 * Copy [text] to the system clipboard and show a confirmation toast.
 * 传入 [what]（例如条目名称）时，提示会写成“已复制「型号」”，比泛泛的“已复制”更清楚。
 */
fun copyText(context: Context, text: String, what: String? = null) {
    if (text.length > CLIPBOARD_MAX_CHARS) {
        Toast.makeText(context, "内容太长（超过 ${CLIPBOARD_MAX_CHARS / 10_000} 万字符），剪贴板放不下", Toast.LENGTH_LONG).show()
        return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    try {
        clipboard.setPrimaryClip(ClipData.newPlainText("toTool", text))
        val msg = if (what.isNullOrBlank()) context.getString(R.string.toast_copied) else "已复制「$what」"
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    } catch (e: RuntimeException) {
        Toast.makeText(context, "复制失败：内容太大", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Request the launcher to pin a shortcut to this tool on the home screen.
 * Tapping the shortcut reopens [MainActivity] and jumps straight to the tool.
 */
fun pinToolShortcut(context: Context, tool: Tool) {
    val intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        // 应用已经在后台时复用现有的 MainActivity（走 onNewIntent），不再叠一个新的
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(MainActivity.EXTRA_TOOL_ID, tool.id)
    val shortcut = ShortcutInfoCompat.Builder(context, "tool_${tool.id}")
        .setShortLabel(tool.name)
        .setLongLabel(tool.name)
        // 每个工具用自己的图标（分类底色 + 工具图标），桌面上才分得清；再次添加同一个工具会顺便更新旧图标
        .setIcon(toolShortcutIcon(context, tool))
        .setIntent(intent)
        .build()
    requestPinWithFeedback(context, shortcut)
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
    val context = LocalContext.current
    val pinnable = ToolRegistry.byId[ToolRegistry.resolve(favoriteId)]
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
                    if (pinnable != null) {
                        IconButton(onClick = { pinToolShortcut(context, pinnable) }) {
                            Icon(
                                imageVector = Icons.Filled.AddToHomeScreen,
                                contentDescription = stringResource(R.string.action_pin_home),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
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

/**
 * Scrollable column with consistent padding — the body of most tool screens.
 * 需要从外面控制滚动位置（比如搜索词变了要回到顶部）时，传入自己的 [scrollState]。
 */
@Composable
fun ToolBody(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
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
 * the home screen. Favorite cards use a warm yellow container; in [compact]
 * mode the card shows only the icon and name for a denser layout.
 */
@Composable
fun ToolCard(
    tool: Tool,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val favorites = LocalFavorites.current
    val isFavorite = favorites.isFavorite(tool.id)
    var menuOpen by remember { mutableStateOf(false) }
    val favoriteLabel = if (isFavorite) stringResource(R.string.action_unfavorite)
                        else stringResource(R.string.action_favorite)
    val cardColor = if (isFavorite) {
        favoriteContainerColor(MiuixTheme.colorScheme.surfaceContainer)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }

    Box(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(
                color = cardColor,
                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            ),
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
                        // 已收藏的卡片用黄底标识；只在不曾收藏时保留“添加收藏”的星标入口。
                        if (!isFavorite) {
                            IconButton(
                                onClick = { favorites.toggle(tool.id) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.StarBorder,
                                    contentDescription = favoriteLabel,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
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
        ToolActionMenu(show = menuOpen, tool = tool, onDismiss = { menuOpen = false })
    }
}

/**
 * 长按工具弹出的菜单：收藏 / 取消收藏、添加到主屏幕。
 * 标准模式的卡片和紧凑模式的药丸共用，必须放在要弹出菜单的那个控件的同一个父 Box 里。
 */
@Composable
fun ToolActionMenu(
    show: Boolean,
    tool: Tool,
    onDismiss: () -> Unit,
) {
    val favorites = LocalFavorites.current
    val context = LocalContext.current
    val favoriteLabel = if (favorites.isFavorite(tool.id)) stringResource(R.string.action_unfavorite)
                        else stringResource(R.string.action_favorite)
    val pinLabel = stringResource(R.string.action_pin_home)

    OverlayListPopup(
        show = show,
        alignment = PopupPositionProvider.Align.Start,
        onDismissRequest = onDismiss,
    ) {
        ListPopupColumn {
            DropdownImpl(
                text = favoriteLabel,
                optionSize = 2,
                isSelected = false,
                index = 0,
                onSelectedIndexChange = { favorites.toggle(tool.id); onDismiss() },
            )
            DropdownImpl(
                text = pinLabel,
                optionSize = 2,
                isSelected = false,
                index = 1,
                onSelectedIndexChange = { pinToolShortcut(context, tool); onDismiss() },
            )
        }
    }
}

/** 结果框最多排版这么多字符；整段几 MB 的文本交给 Text 排版会把内存和主线程拖垮。 */
private const val RESULT_DISPLAY_CHARS = 20_000

private fun clipForDisplay(text: String): String =
    if (text.length <= RESULT_DISPLAY_CHARS) text
    else text.substring(0, RESULT_DISPLAY_CHARS) +
        "\n…（共 ${text.length} 个字符，这里只显示前 $RESULT_DISPLAY_CHARS 个）"

/**
 * A boxed output row with a copy button. Used by text/conversion tools.
 *
 * 配色说明：Miuix 亮色里 onSecondaryContainer 是很浅的灰（#A9A9A9），
 * 原来标题和正文都用它，落在浅灰底上又糊又分不出层次。
 * 现在底色用白色卡片，标题用主题色小字，正文用 onSurface（黑 / 白），层次一眼就能看清。
 *
 * @param minHeight 输出区域的最小高度。文本类工具传一个较大的值，让输出框和输入框一样宽敞。
 */
@Composable
fun ResultBox(
    text: String,
    label: String? = null,
    monospace: Boolean = false,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp,
    maxLines: Int? = null,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 14.dp),
        colors = CardDefaults.defaultColors(
            color = colors.surfaceContainer,
            contentColor = colors.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.heightIn(min = minHeight),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                if (label != null) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (text.isEmpty()) {
                    Text(
                        text = context.getString(R.string.placeholder_empty),
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceVariantActions,
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = clipForDisplay(text),
                            style = MiuixTheme.textStyles.body2,
                            color = colors.onSurface,
                            fontFamily = if (monospace) FontFamily.Monospace else null,
                            maxLines = maxLines ?: Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (text.isNotEmpty()) {
                IconButton(onClick = { copyText(context, text, label) }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = context.getString(R.string.action_copy),
                        tint = colors.primary,
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
