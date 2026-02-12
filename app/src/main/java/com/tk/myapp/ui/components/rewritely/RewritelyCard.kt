package com.tk.myapp.ui.components.rewritely

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.tk.myapp.data.common.Storage
import com.tk.myapp.data.rewritely.AppInfo
import com.tk.myapp.data.rewritely.Shortcut

@Composable
fun RewritelyCard(
    secureStorage: Storage,
    onShortcutAdded: (String, String, Boolean) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
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
            .padding(vertical = 3.dp),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Rewritely",
                    style = MaterialTheme.typography.titleMedium
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isExpanded) {
                if (shortcuts.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                    ) {
                        items(shortcuts) { shortcut ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = shortcut.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
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
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
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
                        .padding(top = 24.dp)
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
                            .padding(top = 24.dp)
                    ) {
                        items(selectedApps) { app ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
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
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { showChooseAppsDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                ) {
                    Text("Choose Apps")
                }
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
