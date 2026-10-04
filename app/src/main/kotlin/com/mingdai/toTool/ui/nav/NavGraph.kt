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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 */

/** 转场时长（毫秒）。 */
private const val STACK_MS = 380

/** 被压在下面的页面后退的距离，占屏幕宽度的比例。 */
private const val STACK_PARALLAX = 0.25f

/** 下面那一页被压暗的最大程度（黑色遮罩的不透明度）。 */
private const val STACK_DIM = 0.30f

/** 普通（点击 / 按钮返回）转场的缓动：先快后慢。 */
private val StackEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * 给页面加上“压在上一页上方”的立体感：
 * 页面滑动时，在自己左右两侧（也就是露出来的下一页上）铺一层随进度变化的暗色遮罩和边缘阴影。
 * 只有位于最上层的页面，这两侧才可见；被压在下面的页面，它的遮罩区域被上层页面完全盖住。
 */
@Composable
private fun AnimatedContentScope.StackPage(content: @Composable () -> Unit) {
    // 1 = 页面完全就位（Visible），0 = 页面还没进来 / 已经出去（PreEnter / PostExit）
    val presence by transition.animateFloat(
        transitionSpec = { tween(durationMillis = STACK_MS, easing = LinearEasing) },
        label = "stackPresence",
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val p = presence
                if (p >= 0.999f) return@drawBehind
                val w = size.width
                val h = size.height
                val dim = Color.Black.copy(alpha = STACK_DIM * p)
                // 整块压暗
                drawRect(color = dim, topLeft = Offset(-w, 0f), size = Size(w, h))
                drawRect(color = dim, topLeft = Offset(w, 0f), size = Size(w, h))
                // 紧贴页面边缘的一道渐变阴影
                val edge = 24.dp.toPx()
                val shadow = Color.Black.copy(alpha = 0.18f * p)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, shadow),
                        startX = -edge,
                        endX = 0f,
                    ),
                    topLeft = Offset(-edge, 0f),
                    size = Size(edge, h),
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(shadow, Color.Transparent),
                        startX = w,
                        endX = w + edge,
                    ),
                    topLeft = Offset(w, 0f),
                    size = Size(edge, h),
                )
            },
    ) {
        content()
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
        composable(Routes.HOME) {
            StackPage { HomeScreen(navController = navController) }
        }
        composable(Routes.SETTINGS) {
            StackPage { SettingsScreen(navController = navController) }
        }
        composable(
            route = Routes.TOOL,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            StackPage { ToolRouter(navController = navController, toolId = id) }
        }
    }
}
