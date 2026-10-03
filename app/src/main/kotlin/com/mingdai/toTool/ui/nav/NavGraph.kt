package com.mingdai.toTool.ui.nav

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
        enterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideInVertically(animationSpec = tween(450), initialOffsetY = { it / 3 })
        },
        exitTransition = {
            fadeOut(animationSpec = tween(250)) +
                slideOutVertically(animationSpec = tween(400), targetOffsetY = { -it / 3 })
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideInVertically(animationSpec = tween(450), initialOffsetY = { -it / 3 })
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(250)) +
                slideOutVertically(animationSpec = tween(400), targetOffsetY = { it / 3 })
        },
    ) {
        composable(Routes.HOME) {
            HomeScreen(navController = navController)
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(navController = navController)
        }
        composable(
            route = Routes.TOOL,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            ToolRouter(navController = navController, toolId = id)
        }
    }
}
