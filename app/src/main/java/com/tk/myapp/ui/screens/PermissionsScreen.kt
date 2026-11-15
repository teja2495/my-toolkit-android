package com.tk.myapp.ui.screens

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import com.tk.myapp.ui.components.common.Segment
import com.tk.myapp.ui.components.common.SegmentRow
import com.tk.myapp.ui.components.common.SegmentedSection
import com.tk.myapp.ui.components.common.ShapeIcon
import com.tk.myapp.ui.components.common.ToolkitScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        context.startActivity(intent)
    }

    ToolkitScaffold(title = "Permissions", onBack = onBack) {
        SegmentedSection(
            items = listOf(
                { shape ->
                    Segment(
                        shape = shape,
                        onClick = if (isAccessibilityEnabled) null else ::openAccessibilitySettings
                    ) {
                        SegmentRow(
                            headline = "Accessibility",
                            supporting = if (isAccessibilityEnabled) {
                                "On. Rewritely can work in your chosen apps."
                            } else {
                                "Off. Turn it on so Rewritely can work in other apps."
                            },
                            leading = {
                                ShapeIcon(
                                    icon = if (isAccessibilityEnabled) Icons.Filled.Check else Icons.Outlined.Shield,
                                    polygon = MaterialShapes.Clover4Leaf,
                                    container = if (isAccessibilityEnabled) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer
                                    },
                                    content = if (isAccessibilityEnabled) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    }
                                )
                            }
                        )
                    }
                }
            )
        )

        if (!isAccessibilityEnabled) {
            Button(
                onClick = { openAccessibilitySettings() },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight)
            ) {
                Text("Open accessibility settings", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
        }
    }
}
