package com.tk.myapp.ui.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.graphics.Shape
import androidx.graphics.shapes.RoundedPolygon
import com.tk.myapp.ui.components.common.Segment
import com.tk.myapp.ui.components.common.SegmentRow
import com.tk.myapp.ui.components.common.SegmentedSection
import com.tk.myapp.ui.components.common.ShapeIcon
import com.tk.myapp.ui.components.common.ToolkitScaffold
import com.tk.myapp.R
import com.tk.myapp.data.common.Storage
import com.tk.myapp.data.rewritely.Shortcut
import com.tk.myapp.ui.components.rewritely.RewritelyCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    onNavigateToApiKeys: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToPhoneIntegration: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val secureStorage = remember { Storage(context) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }

    // Check accessibility permission status
    fun checkAccessibilityPermission(): Boolean {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val packageName = context.packageName

        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    // Update permission status on initial load
    LaunchedEffect(Unit) {
        isAccessibilityEnabled = checkAccessibilityPermission()
    }

    // Check permission status when activity resumes (user returns from settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = checkAccessibilityPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    ToolkitScaffold(title = stringResource(R.string.app_name)) {
        if (!isAccessibilityEnabled) {
            Segment(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.errorContainer,
                onClick = onNavigateToPermissions
            ) {
                SegmentRow(
                    headline = "Accessibility is off",
                    supporting = "Rewritely needs it in other apps",
                    leading = {
                        ShapeIcon(
                            icon = Icons.Outlined.Shield,
                            polygon = MaterialShapes.Clover4Leaf,
                            container = MaterialTheme.colorScheme.error,
                            content = MaterialTheme.colorScheme.onError
                        )
                    },
                    trailing = {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                )
            }
        }

        SegmentedSection(
            title = "Features",
            items = listOf(
                { shape ->
                    NavSegment(
                        shape = shape,
                        title = "Mac Integration",
                        supporting = "Clipboard, files and sharing",
                        icon = Icons.Outlined.LaptopMac,
                        polygon = MaterialShapes.Cookie9Sided,
                        accent = MaterialTheme.colorScheme.primaryContainer,
                        onAccent = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = onNavigateToPhoneIntegration
                    )
                },
                { shape ->
                    RewritelyCard(
                        shape = shape,
                        secureStorage = secureStorage,
                        onShortcutAdded = { name, prompt, useChatGPT ->
                            secureStorage.addShortcut(
                                Shortcut(name = name, prompt = prompt, useChatGPT = useChatGPT)
                            )
                        }
                    )
                }
            )
        )

        SegmentedSection(
            title = "Settings",
            items = listOf(
                { shape ->
                    NavSegment(
                        shape = shape,
                        title = "API keys",
                        supporting = "Gemini and OpenAI",
                        icon = Icons.Outlined.Key,
                        polygon = MaterialShapes.Pill,
                        accent = MaterialTheme.colorScheme.tertiaryContainer,
                        onAccent = MaterialTheme.colorScheme.onTertiaryContainer,
                        onClick = onNavigateToApiKeys
                    )
                }
            )
        )
    }
}

@Composable
private fun NavSegment(
    shape: Shape,
    title: String,
    supporting: String,
    icon: ImageVector,
    polygon: RoundedPolygon,
    accent: Color,
    onAccent: Color,
    onClick: () -> Unit
) {
    Segment(shape = shape, onClick = onClick) {
        SegmentRow(
            headline = title,
            supporting = supporting,
            leading = { ShapeIcon(icon = icon, polygon = polygon, container = accent, content = onAccent) },
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
