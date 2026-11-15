package com.tk.myapp.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import com.tk.myapp.ui.components.common.Segment
import com.tk.myapp.ui.components.common.SegmentedSection
import com.tk.myapp.ui.components.common.ToolkitScaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tk.myapp.feature.phoneintegration.MacRemoteFileCategory
import com.tk.myapp.feature.phoneintegration.MacRemoteFileItem
import com.tk.myapp.feature.phoneintegration.PhoneIntegrationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MacFolderBrowserScreen(
    category: MacRemoteFileCategory,
    documentUri: String?,
    title: String,
    onBack: () -> Unit,
    onOpenFolder: (MacRemoteFileItem) -> Unit
) {
    val context = LocalContext.current
    val controller = remember { PhoneIntegrationController.getInstance(context) }
    val uiState by controller.uiState.collectAsState()
    val dateFormatter = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }

    LaunchedEffect(category, documentUri) {
        controller.openMacFolder(category, documentUri, title)
    }

    val folders = uiState.currentMacFolderEntries.filter { it.isDirectory }
    val files = uiState.currentMacFolderEntries.filterNot { it.isDirectory }

    ToolkitScaffold(
        title = title,
        onBack = onBack,
        actions = {
            IconButton(onClick = controller::refreshMacFiles) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
    ) {
        when {
            uiState.isLoadingMacFolder -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            folders.isEmpty() && files.isEmpty() -> {
                Text(
                    "This folder is empty",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 96.dp),
                    textAlign = TextAlign.Center
                )
            }

            else -> {
                if (folders.isNotEmpty()) {
                    SegmentedSection(
                        title = "Folders",
                        items = folders.map { folder ->
                            val item: @Composable (Shape) -> Unit = { shape ->
                                Segment(shape = shape) {
                                    MacFolderEntryRow(
                                        filename = folder.filename,
                                        onClick = { onOpenFolder(folder) }
                                    )
                                }
                            }
                            item
                        }
                    )
                }
                if (files.isNotEmpty()) {
                    SegmentedSection(
                        title = "Files",
                        items = files.map { file ->
                            val item: @Composable (Shape) -> Unit = { shape ->
                                Segment(shape = shape) {
                                    MacFileEntryRow(
                                        file = file,
                                        dateFormatter = dateFormatter,
                                        onClick = { controller.openMacFile(file) }
                                    )
                                }
                            }
                            item
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MacFolderEntryRow(
    filename: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            filename,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MacFileEntryRow(
    file: MacRemoteFileItem,
    dateFormatter: DateFormat,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbnailBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = file.thumbnailBase64) {
        value = file.thumbnailBase64?.let { base64 ->
            withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val bitmap = thumbnailBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.medium)
            )
        } else {
            Icon(
                Icons.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(file.filename, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${android.text.format.Formatter.formatShortFileSize(context, file.sizeBytes)} • ${dateFormatter.format(Date(file.modifiedAtMillis))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
