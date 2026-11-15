package com.tk.myapp.ui.components.rewritely

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.tk.myapp.data.common.Storage
import com.tk.myapp.data.rewritely.AppInfo
import com.tk.myapp.data.rewritely.Shortcut
import com.tk.myapp.ui.components.common.Segment
import com.tk.myapp.ui.components.common.SegmentRow
import com.tk.myapp.ui.components.common.ShapeIcon

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RewritelyCard(
    shape: Shape,
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

    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "chevron"
    )

    Segment(shape = shape) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SegmentRow(
                headline = "Rewritely",
                modifier = Modifier.clickable { isExpanded = !isExpanded },
                supporting = "AI rewrite shortcuts",
                leading = {
                    ShapeIcon(
                        icon = Icons.Outlined.AutoAwesome,
                        polygon = MaterialShapes.Sunny,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                },
                trailing = {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(chevronRotation)
                    )
                }
            )

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ChipGroupLabel("Shortcuts")
                    if (shortcuts.isEmpty()) {
                        EmptyHint("Add a shortcut to rewrite selected text with a saved prompt.")
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(shortcuts) { shortcut ->
                                InputChip(
                                    selected = true,
                                    onClick = { editingShortcut = shortcut },
                                    label = { Text(shortcut.name) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Delete ${shortcut.name}",
                                            modifier = Modifier
                                                .size(InputChipDefaults.AvatarSize)
                                                .clickable {
                                                    secureStorage.deleteShortcut(shortcut)
                                                    shortcuts = secureStorage.getShortcuts()
                                                }
                                        )
                                    },
                                    shape = CircleShape
                                )
                            }
                        }
                    }

                    ChipGroupLabel("Apps")
                    if (selectedApps.isEmpty()) {
                        EmptyHint("Choose the apps where Rewritely should appear.")
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(selectedApps) { app ->
                                InputChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text(app.appName) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove ${app.appName}",
                                            modifier = Modifier
                                                .size(InputChipDefaults.AvatarSize)
                                                .clickable {
                                                    secureStorage.saveSelectedApps(selectedApps - app)
                                                    selectedApps = secureStorage.getSelectedApps()
                                                }
                                        )
                                    },
                                    shape = CircleShape
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showDialog = true },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Add shortcut")
                        }
                        FilledTonalButton(
                            onClick = { showChooseAppsDialog = true },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Choose apps")
                        }
                    }
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

@Composable
private fun ChipGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
