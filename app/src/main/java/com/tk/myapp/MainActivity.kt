package com.tk.myapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tk.myapp.feature.phoneintegration.MacRemoteFileCategory
import com.tk.myapp.feature.phoneintegration.PhoneIntegrationController
import com.tk.myapp.ui.screens.ApiKeysScreen
import com.tk.myapp.ui.screens.MacFolderBrowserScreen
import com.tk.myapp.ui.screens.MainScreen
import com.tk.myapp.ui.screens.PermissionsScreen
import com.tk.myapp.ui.screens.PhoneIntegrationScreen
import com.tk.myapp.ui.theme.MyAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        enableEdgeToEdge()
        setContent {
            MyAppTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = "main",
                    enterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(300)
                        )
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 4 },
                            animationSpec = tween(300)
                        )
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -it / 4 },
                            animationSpec = tween(300)
                        )
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(300)
                        )
                    }
                ) {
                    composable("main") {
                        MainScreen(
                            onNavigateToApiKeys = { navController.navigate("api_keys") },
                            onNavigateToPermissions = { navController.navigate("permissions") },
                            onNavigateToPhoneIntegration = { navController.navigate("phone_integration") }
                        )
                    }
                    composable("api_keys") {
                        ApiKeysScreen()
                    }
                    composable("permissions") {
                        PermissionsScreen()
                    }
                    composable("phone_integration") {
                        PhoneIntegrationScreen(
                            onBack = { navController.popBackStack() },
                            onOpenMacFolder = { category, documentUri, title ->
                                navController.navigate(macFolderRoute(category, documentUri, title))
                            }
                        )
                    }
                    composable(
                        route = "mac_folder/{category}?documentUri={documentUri}&title={title}",
                        arguments = listOf(
                            navArgument("category") { type = NavType.StringType },
                            navArgument("documentUri") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument("title") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        )
                    ) { backStackEntry ->
                        val category = MacRemoteFileCategory.fromProtocolValue(
                            backStackEntry.arguments?.getString("category").orEmpty()
                        ) ?: MacRemoteFileCategory.Desktop
                        val documentUri = backStackEntry.arguments?.getString("documentUri")
                            ?.let { Uri.decode(it) }
                        val title = backStackEntry.arguments?.getString("title")
                            ?.let { Uri.decode(it) }
                            ?: category.title
                        MacFolderBrowserScreen(
                            category = category,
                            documentUri = documentUri,
                            title = title,
                            onBack = { navController.popBackStack() },
                            onOpenFolder = { folder ->
                                navController.navigate(
                                    macFolderRoute(folder.category, folder.documentUri, folder.filename)
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PhoneIntegrationController.getInstance(this).onAppForegrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return
        }
        val controller = PhoneIntegrationController.getInstance(this)
        controller.start()
        controller.handleShareIntent(intent)
    }
}

private fun macFolderRoute(category: MacRemoteFileCategory, documentUri: String?, title: String?): String {
    val params = buildList {
        documentUri?.let { add("documentUri=${Uri.encode(it)}") }
        title?.let { add("title=${Uri.encode(it)}") }
    }
    val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
    return "mac_folder/${category.protocolValue}$query"
}
