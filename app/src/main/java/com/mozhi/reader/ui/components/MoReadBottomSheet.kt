package com.mozhi.reader.ui.components

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * All bottom sheets share this scroll boundary. Lists keep their ordinary scrolling and fling;
 * excess motion stops at the edge. Dragging the handle/header still moves and dismisses the sheet.
 * Size constraints belong to content, never the outer sheet (Material uses that as window height).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoReadBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(containerColor),
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit
) {
    var handleHeight by remember { mutableIntStateOf(0) }
    MoReadOverlayTheme {
    ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), tonalElevation = 0.dp,
        sheetMaxWidth = sheetMaxWidth, containerColor = containerColor, contentColor = contentColor,
        scrimColor = scrimColor,
        dragHandle = dragHandle?.let { handle ->
            { Box(Modifier.onSizeChanged { handleHeight = it.height }) { handle() } }
        },
        // Material consumes the current sheet offset from its top inset. Letting that change the
        // content constraints also changes the expanded anchor during a drag (or even oscillates
        // at rest for fillMaxSize content). Reserve the top safe area in a stable content cap instead.
        contentWindowInsets = { BottomSheetDefaults.windowInsets.only(WindowInsetsSides.Bottom) },
        sheetGesturesEnabled = true) {
        val density = LocalDensity.current
        val windowHeight = LocalWindowInfo.current.containerSize.height
        val insets = BottomSheetDefaults.windowInsets
        val maxContentHeight = with(density) {
            (windowHeight - insets.getTop(this) - insets.getBottom(this) - if (dragHandle == null) 0 else handleHeight)
                .coerceAtLeast(0).toDp()
        }
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            // Existing pages add 16–24 dp; this shared gutter also keeps full-width controls off the edge.
            Column(Modifier.fillMaxWidth()
                .then(if (windowHeight > 0) Modifier.heightIn(max = maxContentHeight) else Modifier)
                .containSheetScroll().padding(horizontal = 8.dp), content = content)
        }
    }
    }
}
