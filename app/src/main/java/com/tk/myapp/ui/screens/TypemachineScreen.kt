package com.tk.myapp.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.tk.myapp.feature.typemachine.TypedEntry
import com.tk.myapp.feature.typemachine.TypemachineStore
import com.tk.myapp.ui.components.common.ScreenGutter

private data class AppLabel(val name: String, val icon: ImageBitmap?)

private val CardShape = RoundedCornerShape(24.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypemachineScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TypemachineStore.getInstance(context) }
    val entries by store.entries.collectAsState()
    val excludedApps by store.excludedApps.collectAsState()
    val appLabels = remember { HashMap<String, AppLabel>() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showExcluded by remember { mutableStateOf(false) }

    fun labelFor(packageName: String): AppLabel =
        appLabels.getOrPut(packageName) { loadAppLabel(context, packageName) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Typemachine", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        ExpressiveMenu(
                            expanded = menuOpen,
                            onDismiss = { menuOpen = false },
                            actions = listOf(
                                MenuAction("Clear all", Icons.Outlined.DeleteSweep, enabled = entries.isNotEmpty()) {
                                    confirmClear = true
                                },
                                MenuAction("Excluded apps", Icons.Outlined.Block) { showExcluded = true }
                            )
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Keyboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        "Nothing typed yet",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        "Text you type in other apps shows up here. Accessibility must be on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    TypedEntryCard(
                        entry = entry,
                        label = labelFor(entry.packageName),
                        onClick = { selectedId = entry.id },
                        onCopy = { copyToClipboard(context, entry.text) },
                        onDelete = { store.delete(entry.id) },
                        onExcludeApp = { store.excludeApp(entry.packageName) }
                    )
                }
            }
        }
    }

    val selected = selectedId?.let { id -> entries.firstOrNull { it.id == id } }
    if (selected != null) {
        TypedEntrySheet(
            entry = selected,
            label = labelFor(selected.packageName),
            onDismiss = { selectedId = null },
            onCopy = { copyToClipboard(context, selected.text) },
            onDelete = {
                store.delete(selected.id)
                selectedId = null
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear everything?") },
            text = { Text("All captured text will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    store.clear()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }

    if (showExcluded) {
        ExcludedAppsDialog(
            excludedApps = excludedApps.sortedBy { labelFor(it).name.lowercase() },
            labelFor = ::labelFor,
            onInclude = { store.includeApp(it) },
            onDismiss = { showExcluded = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TypedEntryCard(
    entry: TypedEntry,
    label: AppLabel,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onExcludeApp: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = CardShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppHeader(label = label)
                    Text(
                        entry.text,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
                }
            }
        }
        ExpressiveMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            actions = listOf(
                MenuAction("Delete", Icons.Outlined.Delete, onClick = onDelete),
                MenuAction("Exclude ${label.name}", Icons.Outlined.Block, onClick = onExcludeApp)
            )
        )
    }
}

private class MenuAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/** Material 3 Expressive menu: a single rounded group of shaped items. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>) {
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
            actions.forEachIndexed { index, action ->
                DropdownMenuItem(
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    text = { Text(action.label) },
                    shape = MenuDefaults.itemShape(index, actions.size).shape,
                    enabled = action.enabled,
                    leadingIcon = {
                        Icon(
                            action.icon,
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun AppHeader(label: AppLabel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AppIcon(label)
        Text(
            label.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AppIcon(label: AppLabel) {
    if (label.icon != null) {
        Image(bitmap = label.icon, contentDescription = null, modifier = Modifier.size(28.dp))
    } else {
        Icon(Icons.Outlined.Keyboard, contentDescription = null, modifier = Modifier.size(28.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypedEntrySheet(
    entry: TypedEntry,
    label: AppLabel,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppHeader(label = label)
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(entry.text, style = MaterialTheme.typography.bodyLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Delete", modifier = Modifier.padding(start = 8.dp))
                }
                Button(onClick = onCopy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Copy", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ExcludedAppsDialog(
    excludedApps: List<String>,
    labelFor: (String) -> AppLabel,
    onInclude: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Excluded apps") },
        text = {
            if (excludedApps.isEmpty()) {
                Text("No excluded apps. Long-press a card to exclude its app.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(excludedApps, key = { it }) { packageName ->
                        val label = labelFor(packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AppIcon(label)
                            Text(
                                label.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onInclude(packageName) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Remove from excluded apps")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

private fun loadAppLabel(context: Context, packageName: String): AppLabel {
    val pm = context.packageManager
    return try {
        val info = pm.getApplicationInfo(packageName, 0)
        val icon = runCatching { pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        AppLabel(pm.getApplicationLabel(info).toString(), icon)
    } catch (e: PackageManager.NameNotFoundException) {
        AppLabel(packageName, null)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Typemachine", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
