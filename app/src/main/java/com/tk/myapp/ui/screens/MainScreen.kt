package com.tk.myapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tk.myapp.data.SecureStorage
import com.tk.myapp.data.Shortcut
import com.tk.myapp.ui.components.RewritelyCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToApiKeys: () -> Unit,
    onNavigateToPermissions: () -> Unit
) {
    val context = LocalContext.current
    val secureStorage = remember { SecureStorage(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My App") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            RewritelyCard(
                secureStorage = secureStorage,
                onShortcutAdded = { name, prompt, useChatGPT ->
                    val shortcut = Shortcut(
                        name = name,
                        prompt = prompt,
                        useChatGPT = useChatGPT
                    )
                    secureStorage.addShortcut(shortcut)
                }
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clickable { onNavigateToPermissions() }
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = "Navigate"
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clickable { onNavigateToApiKeys() }
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Configure API Keys",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = "Navigate"
                    )
                }
            }
        }
    }
}
