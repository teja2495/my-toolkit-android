package com.tk.myapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tk.myapp.data.common.Storage
import com.tk.myapp.ui.components.common.ApiKeyCard
import com.tk.myapp.ui.components.common.SegmentedSection
import com.tk.myapp.ui.components.common.ToolkitScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val secureStorage = remember { Storage(context) }
    
    var geminiKey by remember { mutableStateOf(secureStorage.getApiKey(Storage.KEY_GEMINI_API_KEY)) }
    var openaiKey by remember { mutableStateOf(secureStorage.getApiKey(Storage.KEY_OPENAI_API_KEY)) }

    ToolkitScaffold(title = "API keys", onBack = onBack) {
        SegmentedSection(
            items = listOf(
                { shape ->
                    ApiKeyCard(
                        shape = shape,
                        title = "Gemini API Key",
                        savedApiKey = geminiKey,
                        onSave = { apiKey ->
                            secureStorage.saveApiKey(Storage.KEY_GEMINI_API_KEY, apiKey)
                            geminiKey = apiKey
                        },
                        onReset = {
                            secureStorage.clearApiKey(Storage.KEY_GEMINI_API_KEY)
                            geminiKey = null
                        }
                    )
                },
                { shape ->
                    ApiKeyCard(
                        shape = shape,
                        title = "OpenAI API Key",
                        savedApiKey = openaiKey,
                        onSave = { apiKey ->
                            secureStorage.saveApiKey(Storage.KEY_OPENAI_API_KEY, apiKey)
                            openaiKey = apiKey
                        },
                        onReset = {
                            secureStorage.clearApiKey(Storage.KEY_OPENAI_API_KEY)
                            openaiKey = null
                        }
                    )
                }
            )
        )
    }
}
