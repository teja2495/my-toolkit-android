package com.tk.myapp.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tk.myapp.data.SecureStorage
import com.tk.myapp.ui.components.ApiKeyCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen() {
    val context = LocalContext.current
    val secureStorage = remember { SecureStorage(context) }
    
    var geminiKey by remember { mutableStateOf(secureStorage.getApiKey(SecureStorage.KEY_GEMINI_API_KEY)) }
    var openaiKey by remember { mutableStateOf(secureStorage.getApiKey(SecureStorage.KEY_OPENAI_API_KEY)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Keys") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            ApiKeyCard(
                title = "Gemini API Key",
                savedApiKey = geminiKey,
                onSave = { apiKey ->
                    secureStorage.saveApiKey(SecureStorage.KEY_GEMINI_API_KEY, apiKey)
                    geminiKey = apiKey
                },
                onReset = {
                    secureStorage.clearApiKey(SecureStorage.KEY_GEMINI_API_KEY)
                    geminiKey = null
                }
            )

            ApiKeyCard(
                title = "OpenAI API Key",
                savedApiKey = openaiKey,
                onSave = { apiKey ->
                    secureStorage.saveApiKey(SecureStorage.KEY_OPENAI_API_KEY, apiKey)
                    openaiKey = apiKey
                },
                onReset = {
                    secureStorage.clearApiKey(SecureStorage.KEY_OPENAI_API_KEY)
                    openaiKey = null
                }
            )
        }
    }
}
