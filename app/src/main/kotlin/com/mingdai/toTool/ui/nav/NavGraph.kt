package com.mingdai.toTool.ui.nav

import androidx.activity.BackEventCompat
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mingdai.toTool.ui.screens.HomeScreen
import com.mingdai.toTool.ui.screens.SettingsScreen
import com.mingdai.toTool.ui.screens.ToolRouter

/** Route constants kept in one place to avoid typos. */
object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val TOOL = "tool/{id}"
    fun tool(id: String) = "tool/$id"
}

/* ───────────── 页面转场：沿屏幕短边（横向）堆叠 ─────────────
 *
 * 前进：新页面从右侧滑入，压在当前页上方；当前页只向后退一小段（视差）并被压暗。
 * 返回：与前进相反。
 * 预测返回手势：页面随手指 1:1 移动（线性），露出下面的一页；松手后继续完成或回弹。
 *   手势从屏幕右缘开始时，页面向左移动，方向跟手。
 *
 * 性能要点（上一版返回时“等一下才有动画”、一卡一卡的原因）：
 * 1. 首页常驻在导航层的下面，不放进 NavHost。NavHost 会在页面被盖住后销毁它的组合，
 *    返回时要把首页（几十个图标、卡片、文字）整个重新组合一遍，主线程被占住，动画就迟迟不开始。
 *    现在 NavHost 里的“首页”只是一个透明占位，真正的首页一直在下面，返回时什么都不用重建。
 * 2. 压暗和视差都放在独立的图层上，只改图层属性，不会让页面内容重新绘制；
 *    页面边缘的阴影是两个静态小节点，跟着页面一起平移。
 *    （上一版用 drawBehind 每帧重画，会连带整页内容每帧重新录制。）
 */

/** 转场时长（毫秒）。 */
private const val STACK_MS = 380

/** 被压在下面的页面后退的距离，占屏幕宽度的比例。 */
private const val STACK_PARALLAX = 0.25f

/** 下面那一页被压暗的最大程度（黑色遮罩的不透明度）。 */
private const val STACK_DIM = 0.30f

/** 页面边缘阴影的宽度。 */
private val STACK_EDGE = 24.dp

/** 普通（点击 / 按钮返回）转场的缓动：先快后慢。 */
private val StackEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * 包住每个“压在首页上方”的页面。
 * - 把页面的就位进度（0 = 还没进来 / 已经出去，1 = 完全就位）同步给 [cover]，底层首页据此做视差和压暗；
 * - 在页面左右两侧各放一个静态的渐变阴影节点，页面滑动时露出来，就位后在屏幕外。
 */
@Composable
private fun AnimatedContentScope.StackPage(
    cover: MutableFloatState,
    content: @Composable () -> Unit,
) {
    val presence by transition.animateFloat(
        transitionSpec = { tween(durationMillis = STACK_MS, easing = LinearEasing) },
        label = "stackPresence",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    // 用 snapshotFlow 转发进度：只在值变化时写入共享状态，不会让整个页面每一帧重组
    LaunchedEffect(Unit) {
        snapshotFlow { presence }.collect { cover.floatValue = it }
    }
    // 页面被销毁时一定要把首页恢复原位
    DisposableEffect(Unit) { onDispose { cover.floatValue = 0f } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 拦截触摸：页面上没有可交互控件的空白处也要“吃掉”事件。
            // 否则事件会穿过去落到下面常驻的首页上，点到首页的卡片就误开了另一个工具。
            .pointerInput(Unit) {},
    ) {
        content()
        val shadow = Color.Black.copy(alpha = 0.18f)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = -STACK_EDGE)
                .width(STACK_EDGE)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color.Transparent, shadow))),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = STACK_EDGE)
                .width(STACK_EDGE)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(shadow, Color.Transparent))),
        )
    }
}

/**
 * App navigation graph. [deepLinkToolId] is the tool id delivered by a pinned
 * home-screen shortcut (or null). When set, the graph navigates to that tool
 * once, then calls [onDeepLinkConsumed] so it does not re-trigger on rotation.
 */
@Composable
fun AppNavGraph(
    deepLinkToolId: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    LaunchedEffect(deepLinkToolId) {
        val id = deepLinkToolId
        if (!id.isNullOrEmpty()) {
            navController.navigate(Routes.tool(id))
            onDeepLinkConsumed()
        }
    }
    // 上层页面“盖住”首页的程度：0 = 首页完全可见，1 = 完全被盖住
    val cover = remember { mutableFloatStateOf(0f) }
    // 是否有页面正在 / 已经盖在首页上。派生成布尔值：只在 false ↔ true 切换时才重组，
    // 转场过程中 cover 每一帧都在变，但这里不会跟着每帧重组。
    val covered by remember { derivedStateOf { cover.floatValue > 0f } }

    Box(modifier = Modifier.fillMaxSize()) {
        // 底层：首页常驻，不随导航销毁 / 重建；被上层页面推开时做视差和压暗
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = -size.width * STACK_PARALLAX * cover.floatValue },
        ) {
            HomeScreen(navController = navController)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = STACK_DIM * cover.floatValue }
                .background(Color.Black)
                // 有页面盖在上面时，这一层同时挡住下面的首页：
                // 转场期间页面还没铺满，露出来的那一截首页也不能被点到。
                .then(if (covered) Modifier.pointerInput(Unit) {} else Modifier),
        )

        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            // 前进：新页面从右侧滑入，当前页后退一小段
            enterTransition = {
                slideInHorizontally(
                    animationSpec = tween(STACK_MS, easing = StackEasing),
                    initialOffsetX = { it },
                )
            },
            exitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(STACK_MS, easing = StackEasing),
                    targetOffsetX = { -(it * STACK_PARALLAX).toInt() },
                )
            },
            // 返回（点击返回箭头 / 三键导航）：与前进相反
            popEnterTransition = {
                slideInHorizontally(
                    animationSpec = tween(STACK_MS, easing = StackEasing),
                    initialOffsetX = { -(it * STACK_PARALLAX).toInt() },
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = tween(STACK_MS, easing = StackEasing),
                    targetOffsetX = { it },
                )
            },
            // 预测返回手势：线性缓动让页面 1:1 跟手；swipeEdge 区分从左缘还是右缘开始滑
            predictivePopEnterTransition = { swipeEdge ->
                val fromRight = swipeEdge == BackEventCompat.EDGE_RIGHT
                slideInHorizontally(
                    animationSpec = tween(STACK_MS, easing = LinearEasing),
                    initialOffsetX = {
                        val d = (it * STACK_PARALLAX).toInt()
                        if (fromRight) d else -d
                    },
                )
            },
            predictivePopExitTransition = { swipeEdge ->
                val toLeft = swipeEdge == BackEventCompat.EDGE_RIGHT
                slideOutHorizontally(
                    animationSpec = tween(STACK_MS, easing = LinearEasing),
                    targetOffsetX = { if (toLeft) -it else it },
                )
            },
        ) {
            // 透明占位：真正的首页在下面常驻。不接收触摸，点击会直接落到下面的首页上。
            composable(Routes.HOME) {
                Spacer(modifier = Modifier.fillMaxSize())
            }
            composable(Routes.SETTINGS) {
                StackPage(cover) { SettingsScreen(navController = navController) }
            }
            composable(
                route = Routes.TOOL,
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id").orEmpty()
                StackPage(cover) { ToolRouter(navController = navController, toolId = id) }
            }
        }
    }
}
