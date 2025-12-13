package com.tk.myapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tk.myapp.data.AppInfo
import com.tk.myapp.data.SecureStorage
import com.tk.myapp.data.Shortcut

@Composable
fun RewritelyCard(
    secureStorage: SecureStorage,
    onShortcutAdded: (String, String, Boolean) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var showChooseAppsDialog by remember { mutableStateOf(false) }
    var editingShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var shortcuts by remember { mutableStateOf<List<Shortcut>>(emptyList()) }
    var selectedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        shortcuts = secureStorage.getShortcuts()
        selectedApps = secureStorage.getSelectedApps()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = "Rewritely",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (shortcuts.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    items(shortcuts) { shortcut ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = shortcut.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.clickable { editingShortcut = shortcut }
                                )
                                IconButton(
                                    onClick = {
                                        secureStorage.deleteShortcut(shortcut)
                                        shortcuts = secureStorage.getShortcuts()
                                    },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Delete shortcut",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { showDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Add Shortcut")
            }

            if (selectedApps.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    items(selectedApps) { app ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                IconButton(
                                    onClick = {
                                        val updatedApps = selectedApps.toMutableList()
                                        updatedApps.remove(app)
                                        secureStorage.saveSelectedApps(updatedApps)
                                        selectedApps = secureStorage.getSelectedApps()
                                    },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove app",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { showChooseAppsDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Choose Apps")
            }
        }
    }

    if (showDialog) {
        AddShortcutDialog(
            onDismiss = { showDialog = false },
            onSave = { name, prompt, useChatGPT ->
                onShortcutAdded(name, prompt, useChatGPT)
                shortcuts = secureStorage.getShortcuts()
            }
        )
    }

    editingShortcut?.let { shortcut ->
        EditShortcutDialog(
            shortcut = shortcut,
            onDismiss = { editingShortcut = null },
            onSave = { name, prompt, useChatGPT ->
                val updatedShortcut = Shortcut(
                    name = name,
                    prompt = prompt,
                    useChatGPT = useChatGPT
                )
                secureStorage.updateShortcut(shortcut, updatedShortcut)
                shortcuts = secureStorage.getShortcuts()
                editingShortcut = null
            }
        )
    }

    if (showChooseAppsDialog) {
        ChooseAppsDialog(
            onDismiss = { showChooseAppsDialog = false },
            onSave = { apps ->
                secureStorage.saveSelectedApps(apps)
                selectedApps = secureStorage.getSelectedApps()
            },
            selectedApps = selectedApps
        )
    }
}
