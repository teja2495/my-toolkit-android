package com.tk.myapp.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import com.tk.myapp.ui.components.common.Segment
import com.tk.myapp.ui.components.common.SegmentRow
import com.tk.myapp.ui.components.common.SegmentedSection
import com.tk.myapp.ui.components.common.ShapeIcon
import com.tk.myapp.ui.components.common.ToolkitScaffold
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tk.myapp.feature.phoneintegration.PhoneBridgeConnectionState
import com.tk.myapp.feature.phoneintegration.PhoneIntegrationController
import com.tk.myapp.feature.phoneintegration.PhoneIntegrationService
import com.tk.myapp.feature.phoneintegration.MacRemoteFileCategory

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PhoneIntegrationScreen(
    onBack: () -> Unit,
    onOpenMacFolder: (category: MacRemoteFileCategory, documentUri: String?, title: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { PhoneIntegrationController.getInstance(context) }
    val uiState by controller.uiState.collectAsState()
    var hasFileAccess by remember { mutableStateOf(hasRequiredFileAccess(context)) }
    var ignoresBatteryOptimizations by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var isTrustedDevicesExpanded by remember { mutableStateOf(false) }
    var isTrustedNetworksExpanded by remember { mutableStateOf(false) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { controller.refreshNetworkState() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasFileAccess = hasRequiredFileAccess(context)
    }

    LaunchedEffect(Unit) {
        hasFileAccess = hasRequiredFileAccess(context)
        ignoresBatteryOptimizations = isIgnoringBatteryOptimizations(context)
        controller.refreshNetworkState()
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasFileAccess = hasRequiredFileAccess(context)
                ignoresBatteryOptimizations = isIgnoringBatteryOptimizations(context)
                controller.refreshNetworkState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    ToolkitScaffold(title = "Mac Integration", onBack = onBack) {
        BridgeStatusCard(
            connectedPeerName = uiState.connectedPeerName,
            deviceName = uiState.deviceName,
            statusMessage = uiState.statusMessage,
            isStopped = uiState.connectionState == PhoneBridgeConnectionState.Stopped,
            onStart = {
                controller.setBridgeEnabled(true)
                startPhoneBridge(context)
            },
            onStop = {
                controller.setBridgeEnabled(false)
                context.stopService(Intent(context, PhoneIntegrationService::class.java))
                controller.stop()
            }
        )

        uiState.pendingPairing?.let { pending ->
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Pair with ${pending.peerName}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Approve only if your Mac shows the same code.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        pending.verificationCode.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { controller.approvePendingPairing(pending.requestId) },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Approve") }
                        OutlinedButton(
                            onClick = { controller.rejectPendingPairing(pending.requestId) },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Reject") }
                    }
                }
            }
        }

        val setupItems = buildList<@Composable (Shape) -> Unit> {
            if (!ignoresBatteryOptimizations) {
                add { shape ->
                    SetupSegment(
                        shape = shape,
                        title = "Background running",
                        description = "Keeps the bridge reachable when the app is closed or the screen is off.",
                        icon = Icons.Outlined.BatteryAlert,
                        actionLabel = "Allow",
                        onAction = { openBatteryOptimizationSettings(context) }
                    )
                }
            }
            if (!hasFileAccess) {
                add { shape ->
                    SetupSegment(
                        shape = shape,
                        title = "File access",
                        description = "Lets your paired Mac browse files on this phone.",
                        icon = Icons.Outlined.FolderOpen,
                        actionLabel = "Grant",
                        onAction = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                openAllFilesAccessSettings(context)
                            } else {
                                permissionLauncher.launch(requiredStoragePermissions())
                            }
                        }
                    )
                }
            }
        }
        if (setupItems.isNotEmpty()) {
            SegmentedSection(title = "Finish setup", items = setupItems)
        }

        SegmentedSection(
            title = "Mac files",
            items = if (uiState.connectedPeerName == null) {
                listOf<@Composable (Shape) -> Unit>({ shape ->
                    Segment(shape = shape) {
                        SegmentRow(
                            headline = "Not connected",
                            supporting = "Connect Toolkit on your Mac to browse Desktop and Downloads."
                        )
                    }
                })
            } else {
                listOf(MacRemoteFileCategory.Desktop, MacRemoteFileCategory.Downloads).map<MacRemoteFileCategory, @Composable (Shape) -> Unit> { category ->
                    val item: @Composable (Shape) -> Unit = { shape: Shape ->
                        Segment(
                            shape = shape,
                            onClick = { onOpenMacFolder(category, null, category.title) }
                        ) {
                            SegmentRow(
                                headline = category.title,
                                leading = {
                                    Icon(
                                        Icons.Outlined.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailing = {
                                    Icon(
                                        Icons.Filled.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                        }
                    }
                    item
                }
            }
        )

        SegmentedSection(
            title = "Trust",
            items = listOf(
                { shape ->
                    ExpandableSegment(
                        shape = shape,
                        title = "Trusted devices",
                        summary = when (uiState.trustedPeers.size) {
                            0 -> "No Macs paired yet"
                            1 -> "1 Mac"
                            else -> "${uiState.trustedPeers.size} Macs"
                        },
                        icon = Icons.Outlined.LaptopMac,
                        expanded = isTrustedDevicesExpanded,
                        onToggle = { isTrustedDevicesExpanded = !isTrustedDevicesExpanded }
                    ) {
                        uiState.trustedPeers.forEach { peer ->
                            DetailRow(
                                title = peer.name,
                                subtitle = peer.id,
                                trailing = {
                                    IconButton(onClick = { controller.removeTrustedPeer(peer.id) }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Remove ${peer.name}")
                                    }
                                }
                            )
                        }
                    }
                },
                { shape ->
                    TrustedNetworksSegment(
                        shape = shape,
                        currentNetwork = uiState.currentWifiNetwork,
                        trustedNetworks = uiState.trustedNetworks,
                        isExpanded = isTrustedNetworksExpanded,
                        onExpandToggle = { isTrustedNetworksExpanded = !isTrustedNetworksExpanded },
                        canRequestLocationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED,
                        onRequestLocationPermission = {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                        onTrustCurrentNetwork = controller::trustCurrentWifiNetwork,
                        onRemoveNetwork = controller::removeTrustedNetwork
                    )
                }
            )
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BridgeStatusCard(
    connectedPeerName: String?,
    deviceName: String,
    statusMessage: String,
    isStopped: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val connected = connectedPeerName != null
    val container by animateColorAsState(
        targetValue = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "statusContainer"
    )
    val content = if (connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Surface(
        shape = MaterialTheme.shapes.extraLargeIncreased,
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ShapeIcon(
                    icon = if (connected) Icons.Outlined.LaptopMac else Icons.Outlined.PhoneAndroid,
                    polygon = if (connected) MaterialShapes.Cookie12Sided else MaterialShapes.Cookie6Sided,
                    container = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    content = if (connected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    size = 56.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (connected) "Connected" else deviceName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        if (connected) connectedPeerName.orEmpty() else statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (connected) content else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isStopped) {
                Button(onClick = onStart, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                    Text("Start bridge")
                }
            } else {
                OutlinedButton(
                    onClick = onStop,
                    shapes = ButtonDefaults.shapes(),
                    border = BorderStroke(1.dp, content.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = content),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop bridge")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SetupSegment(
    shape: Shape,
    title: String,
    description: String,
    icon: ImageVector,
    actionLabel: String,
    onAction: () -> Unit
) {
    Segment(shape = shape) {
        SegmentRow(
            headline = title,
            supporting = description,
            leading = {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            },
            trailing = {
                FilledTonalButton(onClick = onAction, shapes = ButtonDefaults.shapes()) {
                    Text(actionLabel)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpandableSegment(
    shape: Shape,
    title: String,
    summary: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "chevron"
    )
    Segment(shape = shape) {
        Column {
            SegmentRow(
                headline = title,
                supporting = summary,
                modifier = Modifier.clickable(onClick = onToggle),
                leading = {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailing = {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(rotation)
                    )
                }
            )
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(start = 56.dp, end = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TrustedNetworksSegment(
    shape: Shape,
    currentNetwork: String?,
    trustedNetworks: List<String>,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    canRequestLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    onTrustCurrentNetwork: () -> Boolean,
    onRemoveNetwork: (String) -> Unit
) {
    ExpandableSegment(
        shape = shape,
        title = "Trusted networks",
        summary = when (trustedNetworks.size) {
            0 -> "None yet"
            1 -> "1 network"
            else -> "${trustedNetworks.size} networks"
        },
        icon = Icons.Outlined.Wifi,
        expanded = isExpanded,
        onToggle = onExpandToggle
    ) {
        if (currentNetwork == null) {
            Text(
                "Connect to Wi-Fi and allow location access to identify the current network.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (canRequestLocationPermission) {
                FilledTonalButton(onClick = onRequestLocationPermission, shapes = ButtonDefaults.shapes()) {
                    Text("Allow location access")
                }
            }
        } else {
            val isTrusted = currentNetwork in trustedNetworks
            DetailRow(
                title = currentNetwork,
                subtitle = if (isTrusted) "Current network, trusted" else "Current network",
                leading = if (isTrusted) {
                    {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                } else null,
                trailing = {
                    if (isTrusted) {
                        IconButton(onClick = { onRemoveNetwork(currentNetwork) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove $currentNetwork")
                        }
                    } else {
                        FilledTonalButton(onClick = { onTrustCurrentNetwork() }, shapes = ButtonDefaults.shapes()) {
                            Text("Trust")
                        }
                    }
                }
            )
        }
        trustedNetworks.filterNot { it == currentNetwork }.forEach { networkName ->
            DetailRow(
                title = networkName,
                trailing = {
                    IconButton(onClick = { onRemoveNetwork(networkName) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove $networkName")
                    }
                }
            )
        }
    }
}

private fun startPhoneBridge(context: Context) {
    val intent = Intent(context, PhoneIntegrationService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun requiredStoragePermissions(): Array<String> {
    return when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun hasRequiredStoragePermissions(context: Context): Boolean {
    return requiredStoragePermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun hasRequiredFileAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        hasRequiredStoragePermissions(context)
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openAllFilesAccessSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    runCatching {
        context.startActivity(intent)
    }.getOrElse {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    runCatching {
        context.startActivity(intent)
    }.getOrElse {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
