package com.mingdai.toTool.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.mingdai.toTool.R
import com.mingdai.toTool.data.LocalDisplayState
import com.mingdai.toTool.data.LocalFavorites
import com.mingdai.toTool.data.Tool
import com.mingdai.toTool.data.ToolCategory
import com.mingdai.toTool.data.ToolRegistry
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolCard
import com.mingdai.toTool.ui.nav.Routes
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import com.mingdai.toTool.ui.components.CollapsingBarState
import com.mingdai.toTool.ui.components.BackgroundTaskDrawer
import com.mingdai.toTool.ui.components.collapsingBar
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.mingdai.toTool.data.LocalBehaviorState
import com.mingdai.toTool.system.SystemUiDemoLauncher
import com.mingdai.toTool.ui.components.favoriteContainerColor
import com.mingdai.toTool.ui.components.ToolActionMenu
import com.mingdai.toTool.ui.components.rememberEdgeHaptics
import com.mingdai.toTool.ui.components.rememberHaptics

@Composable
fun HomeScreen(navController: NavHostController) {
    var tab by rememberSaveable { mutableStateOf(0) } // 0 = 首页, 1 = 收藏
    var query by rememberSaveable { mutableStateOf("") }
    var categoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    val display = LocalDisplayState.current
    val compact = display.compact
    val favorites = LocalFavorites.current

    val selectedCategory = categoryKey?.let { runCatching { ToolCategory.valueOf(it) }.getOrNull() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val onToolClick: (Tool) -> Unit = { tool ->
        // 从搜索结果进入工具时，搜索框仍可能持有焦点，输入法会跟着进入工具页。
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (!SystemUiDemoLauncher.handle(context, tool.id)) {
            navController.navigate(Routes.tool(tool.id))
        }
    }

    // A stable tag identifying the active view so Crossfade can tween between them.
    val viewTag: String = when {
        tab == 2 -> "format"
        tab == 1 && compact -> "favCompact"
        tab == 1 -> "favStandard"
        compact && query.isEmpty() -> "compactList"
        compact -> "compactSearch"
        else -> "standard"
    }

    val scrollBehavior = MiuixScrollBehavior()

    // 搜索栏跟随列表滚动：往下翻内容时上浮收起，往回滑立刻出现
    val searchBar = remember { CollapsingBarState() }
    SideEffect { searchBar.enabled = tab != 2 } // “格式转换”页没有搜索栏，不能吃掉它的滚动量
    LaunchedEffect(viewTag) { searchBar.reset() } // 切换视图后让搜索栏重新显示

    // 列表被拉过顶部 / 底部一定距离（或用力甩到边缘）时给一次触感反馈。
    // “格式转换”页里是表单，不需要这个效果。
    val edgeHaptics = rememberEdgeHaptics(enabled = tab != 2)

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = when (tab) {
                    0 -> stringResource(R.string.app_name)
                    1 -> stringResource(R.string.label_favorites)
                    else -> stringResource(R.string.label_format)
                },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { display.toggle() }) {
                        Icon(
                            imageVector = Icons.Filled.Apps,
                            contentDescription = stringResource(R.string.action_compact),
                            tint = if (compact) MiuixTheme.colorScheme.primary
                                   else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = Icons.Filled.Home,
                    label = stringResource(R.string.label_home),
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = Icons.Filled.Favorite,
                    label = stringResource(R.string.label_favorites),
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = Icons.Filled.SwapHoriz,
                    label = stringResource(R.string.label_format),
                )
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                // 顺序很重要：写在最前 = 最外层，它拿到的是标题栏折叠、搜索栏收起、列表滚动都用完之后“剩下”的量
                .nestedScroll(edgeHaptics)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .nestedScroll(searchBar.connection),
        ) {
            if (tab != 2) {
                Box(modifier = Modifier.fillMaxWidth().collapsingBar(searchBar)) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "搜索工具…",
                        useLabelAsPlaceholder = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 12.dp).size(20.dp),
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "清除",
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        cornerRadius = 24.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            AnimatedContent(
                targetState = viewTag,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    // Sequential fade-through with vertical slide: old slides up & out
                    // while fading out, then new slides up from below while fading in.
                    // No overlapping double-image; the slide gives unmistakable motion.
                    (fadeIn(
                        animationSpec = tween(durationMillis = 300, delayMillis = 150, easing = FastOutSlowInEasing),
                    ) + slideInVertically(
                        animationSpec = tween(durationMillis = 300, delayMillis = 150, easing = FastOutSlowInEasing),
                        initialOffsetY = { it / 10 },
                    )) togetherWith
                    (fadeOut(
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                    ) + slideOutVertically(
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
                        targetOffsetY = { -it / 10 },
                    ))
                },
                contentAlignment = Alignment.TopCenter,
                label = "homeView",
            ) { tag ->
                when (tag) {
                    "format" -> FormatConversionScreen(modifier = Modifier.fillMaxSize())

                    "favCompact" -> PillGrid(
                        tools = remember(query, favorites.favorites) {
                            ToolRegistry.search(query).filter { favorites.isFavorite(it.id) }
                        },
                        onClick = onToolClick,
                        emptyIcon = Icons.Filled.FavoriteBorder,
                        emptyText = "还没有收藏的工具",
                        emptyHint = stringResource(R.string.label_long_press_hint),
                        modifier = Modifier.fillMaxSize(),
                    )

                    "favStandard" -> ToolGrid(
                        tools = remember(query, favorites.favorites) {
                            ToolRegistry.search(query).filter { favorites.isFavorite(it.id) }
                        },
                        compact = false,
                        onClick = onToolClick,
                        emptyIcon = Icons.Filled.FavoriteBorder,
                        emptyText = "还没有收藏的工具",
                        emptyHint = stringResource(R.string.label_long_press_hint),
                        modifier = Modifier.fillMaxSize(),
                    )

                    "compactList" -> CompactCategoryList(
                        onToolClick = onToolClick,
                        modifier = Modifier.fillMaxSize(),
                    )

                    "compactSearch" -> PillGrid(
                        tools = remember(query) { ToolRegistry.search(query) },
                        onClick = onToolClick,
                        emptyIcon = Icons.Filled.Search,
                        emptyText = "未找到匹配的工具",
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        CategoryChips(
                            selected = selectedCategory,
                            onSelect = { categoryKey = it },
                        )
                        ToolGrid(
                            tools = remember(query, categoryKey) {
                                var list = ToolRegistry.search(query)
                                if (selectedCategory != null) list = list.filter { it.category == selectedCategory }
                                list
                            },
                            compact = false,
                            onClick = onToolClick,
                            emptyIcon = Icons.Filled.Search,
                            emptyText = "未找到匹配的工具",
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
        }
        }
        BackgroundTaskDrawer(
            modifier = Modifier.fillMaxSize(),
            onOpenTool = { toolId ->
                when (toolId) {
                    "format_conversion", "ffmpeg_command" -> tab = 2
                    else -> ToolRegistry.byId[toolId]?.let { tool ->
                        navController.navigate(Routes.tool(tool.id))
                    }
                }
            },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 紧凑模式：主界面即分类分组列表
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CompactCategoryList(
    onToolClick: (Tool) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 默认展开还是收起由“设置”决定；设置改变时按新的默认值重来。
    // 用 rememberSaveable：进入工具再返回时，用户手动展开 / 收起的状态不会丢。
    val behavior = LocalBehaviorState.current
    var expandedKeys by rememberSaveable(behavior.expandCategories) {
        mutableStateOf(if (behavior.expandCategories) ToolCategory.entries.map { it.name } else emptyList())
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToolCategory.entries.forEach { cat ->
            val isExpanded = cat.name in expandedKeys
            CategoryGroup(
                category = cat,
                tools = ToolRegistry.byCategory(cat),
                isExpanded = isExpanded,
                onHeaderClick = {
                    expandedKeys = if (isExpanded) expandedKeys - cat.name else expandedKeys + cat.name
                },
                onToolClick = onToolClick,
            )
        }
    }
}

@Composable
private fun CategoryGroup(
    category: ToolCategory,
    tools: List<Tool>,
    isExpanded: Boolean,
    onHeaderClick: () -> Unit,
    onToolClick: (Tool) -> Unit,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "arrow",
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onHeaderClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.26f),
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    category.title,
                    style = MiuixTheme.textStyles.headline2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    category.subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            // 该分类下的工具数量
            Text(
                text = tools.size.toString(),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 9.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .size(28.dp)
                    .rotate(arrowRotation),
            )
        }

        // Expandable tools — FlowRow lets each pill size to its content (no fixed width,
        // no truncation) and wraps to the next line naturally.
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tools.forEach { tool ->
                    ToolPill(tool = tool, onClick = { onToolClick(tool) })
                }
            }
        }
    }
}

/**
 * Pill-shaped, content-sized button — never fixed width, so names show in full.
 * 长按弹出和卡片一样的菜单（收藏 / 添加到主屏幕）；已收藏的药丸使用暖黄底色。
 */
@Composable
private fun ToolPill(
    tool: Tool,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val isFavorite = LocalFavorites.current.isFavorite(tool.id)
    var menuOpen by remember { mutableStateOf(false) }

    val pillColor = if (isFavorite) {
        favoriteContainerColor()
    } else {
        MiuixTheme.colorScheme.secondaryContainer
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(pillColor)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.confirm()
                        menuOpen = true
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = tool.name,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
        ToolActionMenu(show = menuOpen, tool = tool, onDismiss = { menuOpen = false })
    }
}

/** 空状态：一个淡色图标 + 一行说明（+ 可选的小提示）。 */
@Composable
private fun EmptyState(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.8f),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 药丸流式网格（紧凑模式搜索 / 收藏）：滚动 + 自适应宽度
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PillGrid(
    tools: List<Tool>,
    onClick: (Tool) -> Unit,
    emptyIcon: ImageVector,
    emptyText: String,
    modifier: Modifier = Modifier,
    emptyHint: String? = null,
) {
    if (tools.isEmpty()) {
        EmptyState(icon = emptyIcon, text = emptyText, hint = emptyHint, modifier = modifier)
    } else {
        Column(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 20.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tools.forEach { tool ->
                    ToolPill(tool = tool, onClick = { onClick(tool) })
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 卡片网格（标准模式首页 / 收藏）
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ToolGrid(
    tools: List<Tool>,
    compact: Boolean,
    onClick: (Tool) -> Unit,
    emptyIcon: ImageVector,
    emptyText: String,
    modifier: Modifier = Modifier,
    emptyHint: String? = null,
) {
    if (tools.isEmpty()) {
        EmptyState(icon = emptyIcon, text = emptyText, hint = emptyHint, modifier = modifier)
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (compact) 96.dp else 168.dp),
            modifier = modifier,
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tools) { tool ->
                ToolCard(
                    tool = tool,
                    onClick = { onClick(tool) },
                    compact = compact,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 标准模式：分类 chips
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CategoryChips(
    selected: ToolCategory?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SelectChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = stringResource(R.string.label_all),
        )
        ToolCategory.entries.forEach { cat ->
            SelectChip(
                selected = selected == cat,
                onClick = { onSelect(if (selected == cat) null else cat.name) },
                label = cat.title,
            )
        }
    }
}