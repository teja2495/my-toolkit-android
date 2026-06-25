package com.tk.myapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tk.myapp.ui.screens.ApiKeysScreen
import com.tk.myapp.ui.screens.MainScreen
import com.tk.myapp.ui.screens.PermissionsScreen
import com.tk.myapp.ui.theme.MyAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            onNavigateToPermissions = { navController.navigate("permissions") }
                        )
                    }
                    composable("api_keys") {
                        ApiKeysScreen()
                    }
                    composable("permissions") {
                        PermissionsScreen()
                    }
                }
            }
        }
    }
}
