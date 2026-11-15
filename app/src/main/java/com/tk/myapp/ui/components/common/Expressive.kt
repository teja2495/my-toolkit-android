package com.tk.myapp.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon

/** Horizontal gutter shared by all screens. */
val ScreenGutter = 16.dp

/** Gap between items inside a segmented group (Android 16 settings style). */
private val SegmentGap = 2.dp
private val SegmentOuterRadius = 24.dp
private val SegmentInnerRadius = 6.dp

/**
 * Screen scaffold with a Material 3 Expressive flexible top app bar that collapses on scroll.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolkitScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.headlineMedium)
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        val base = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        Column(
            modifier = if (scrollable) base.verticalScroll(rememberScrollState()) else base,
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    content = content
                )
            }
        )
    }
}

/** Corner shape for the item at [index] of a segmented group of [count] items. */
fun segmentShape(index: Int, count: Int): Shape {
    val top = if (index == 0) SegmentOuterRadius else SegmentInnerRadius
    val bottom = if (index == count - 1) SegmentOuterRadius else SegmentInnerRadius
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * A titled section whose children are drawn as one segmented group. Each call to `item`
 * receives its shape so first/last segments get the large outer corners.
 */
@Composable
fun SegmentedSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    items: List<@Composable (Shape) -> Unit>
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 10.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
            items.forEachIndexed { index, item -> item(segmentShape(index, items.size)) }
        }
    }
}

/** A single segment surface. Clickable when [onClick] is provided. */
@Composable
fun Segment(
    shape: Shape,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, modifier = modifier.fillMaxWidth(), content = content)
    } else {
        Surface(shape = shape, color = color, modifier = modifier.fillMaxWidth(), content = content)
    }
}

/** Standard row layout inside a [Segment]: leading, headline/supporting text, trailing. */
@Composable
fun SegmentRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 16.dp)
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.titleMedium)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

/** Icon set inside an Expressive polygon (cookie, clover, sunny...). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapeIcon(
    icon: ImageVector,
    polygon: RoundedPolygon,
    container: Color,
    content: Color,
    size: Dp = 44.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(polygon.toShape())
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}
