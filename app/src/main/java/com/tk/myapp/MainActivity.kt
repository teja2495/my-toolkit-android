package com.tk.myapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tk.myapp.ui.screens.ApiKeysScreen
import com.tk.myapp.ui.screens.MainScreen
import com.tk.myapp.ui.theme.MyAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyAppTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            onNavigateToApiKeys = { navController.navigate("api_keys") }
                        )
                    }
                    composable("api_keys") {
                        ApiKeysScreen()
                    }
                }
            }
        }
    }
}
