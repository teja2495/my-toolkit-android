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

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mac Integration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (uiState.connectedPeerName != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Outlined.LaptopMac,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32)
                            )
                            Text(
                                "Connected to ${uiState.connectedPeerName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null)
                            Column {
                                Text(uiState.deviceName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    uiState.statusMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (uiState.connectionState == PhoneBridgeConnectionState.Stopped) {
                            Button(
                                onClick = {
                                    startPhoneBridge(context)
                                }
                            ) {
                                Text("Start Bridge")
                            }
                        } else {
                            OutlinedButton(onClick = {
                                context.stopService(Intent(context, PhoneIntegrationService::class.java))
                                controller.stop()
                            }) {
                                Text("Stop Bridge")
                            }
                        }
                    }
                }
            }

            if (!ignoresBatteryOptimizations) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Background Reliability", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Disable battery optimization for Toolkit if you want the phone bridge to stay reachable while the app is backgrounded or the screen is off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { openBatteryOptimizationSettings(context) }) {
                            Text("Allow Background Running")
                        }
                    }
                }
            }

            if (!hasFileAccess) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("File Access", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Grant file access so your paired Mac can browse files stored on this Android device."
                            ,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            OutlinedButton(onClick = { openAllFilesAccessSettings(context) }) {
                                Text("Grant File Access")
                            }
                        } else {
                            OutlinedButton(onClick = { permissionLauncher.launch(requiredStoragePermissions()) }) {
                                Text("Grant File Access")
                            }
                        }
                    }
                }
            }

            uiState.pendingPairing?.let { pending ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Pair with ${pending.peerName}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Confirm this code is shown on your Mac before approving.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            pending.verificationCode.chunked(3).joinToString(" "),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { controller.approvePendingPairing(pending.requestId) }) {
                                Text("Approve")
                            }
                            OutlinedButton(onClick = { controller.rejectPendingPairing(pending.requestId) }) {
                                Text("Reject")
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Trusted Devices",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { isTrustedDevicesExpanded = !isTrustedDevicesExpanded }) {
                            Icon(
                                imageVector = if (isTrustedDevicesExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (isTrustedDevicesExpanded) "Collapse trusted devices" else "Expand trusted devices"
                            )
                        }
                    }
                    if (isTrustedDevicesExpanded) {
                        if (uiState.trustedPeers.isEmpty()) {
                            Text(
                                "No Macs are paired yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            uiState.trustedPeers.forEach { peer ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(peer.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            peer.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { controller.removeTrustedPeer(peer.id) }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Remove paired device")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            TrustedNetworksCard(
                currentNetwork = uiState.currentWifiNetwork,
                trustedNetworks = uiState.trustedNetworks,
                canRequestLocationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED,
                onRequestLocationPermission = {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                },
                onTrustCurrentNetwork = controller::trustCurrentWifiNetwork,
                onRemoveNetwork = controller::removeTrustedNetwork
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "Mac Files",
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (uiState.connectedPeerName == null) {
                        Text(
                            "Connect Toolkit on your Mac to browse Desktop and Downloads.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        MacFolderNavigationCard(
                            title = MacRemoteFileCategory.Desktop.title,
                            onClick = {
                                onOpenMacFolder(MacRemoteFileCategory.Desktop, null, MacRemoteFileCategory.Desktop.title)
                            }
                        )
                        MacFolderNavigationCard(
                            title = MacRemoteFileCategory.Downloads.title,
                            onClick = {
                                onOpenMacFolder(MacRemoteFileCategory.Downloads, null, MacRemoteFileCategory.Downloads.title)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrustedNetworksCard(
    currentNetwork: String?,
    trustedNetworks: List<String>,
    canRequestLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    onTrustCurrentNetwork: () -> Boolean,
    onRemoveNetwork: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Trusted Networks", style = MaterialTheme.typography.titleMedium)
            if (currentNetwork == null) {
                Text(
                    "Connect to Wi-Fi and allow location access to identify the current network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (canRequestLocationPermission) {
                    OutlinedButton(onClick = onRequestLocationPermission) { Text("Allow Location Access") }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (currentNetwork in trustedNetworks) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Trusted network",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(currentNetwork, style = MaterialTheme.typography.bodyMedium)
                        if (currentNetwork !in trustedNetworks) {
                            Text(
                                "Current Wi-Fi network",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (currentNetwork !in trustedNetworks) {
                        OutlinedButton(onClick = { onTrustCurrentNetwork() }) { Text("Trust") }
                    }
                }
            }
            trustedNetworks.filterNot { it == currentNetwork }.forEach { networkName ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(networkName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemoveNetwork(networkName) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove trusted network")
                    }
                }
            }
        }
    }
}

@Composable
private fun MacFolderNavigationCard(
    title: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
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
