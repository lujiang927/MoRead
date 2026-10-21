package com.mozhi.reader.feature.bookshelf

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density
import com.mozhi.reader.ui.MoReadLayoutPolicy
import com.mozhi.reader.ui.MoReadWindowWidth
import kotlin.math.ceil

internal fun shelfGridHorizontalPaddingDp(width: MoReadWindowWidth): Float = when (width) {
    MoReadWindowWidth.COMPACT -> 20f
    MoReadWindowWidth.MEDIUM -> 28f
    MoReadWindowWidth.EXPANDED -> 48f
}

/** Grid width excludes the page gutters. Phones retain their existing three covers. */
internal fun shelfGridColumnCount(availableWidthDp: Float, spacingDp: Float = 14f): Int {
    if (availableWidthDp < 560f) return 3
    return ((availableWidthDp + spacingDp) / (128f + spacingDp)).toInt().coerceIn(3, 6)
}

/**
 * 导航条窗口（600–839dp）一律 3 列；侧栏窗口按 132dp 最小格排 3–6 列。
 * 4 列门槛取导航条窗口的最大可用宽：直接套公式，839dp 有 4 列，840dp 换成更宽的侧栏后反而掉回 3 列。
 */
internal fun tabletShelfGridColumnCount(availableWidthDp: Float, spacingDp: Float = 24f): Int {
    val railContentWidthLimit = MoReadLayoutPolicy.ExpandedWidthDp -
        MoReadLayoutPolicy.NavigationRailWidthDp - 2 * shelfGridHorizontalPaddingDp(MoReadWindowWidth.MEDIUM)
    if (availableWidthDp < railContentWidthLimit) return 3
    return ((availableWidthDp + spacingDp) / (132f + spacingDp)).toInt().coerceIn(3, 6)
}

/** 手动「每行本数」：0 为自动；指定值在窄容器里逐列让步，封面不窄于 56dp。 */
internal fun shelfGridColumnsWithOverride(
    availableWidthDp: Float,
    spacingDp: Float,
    automaticColumns: Int,
    columnsOverride: Int,
    minimumCellWidthDp: Float = 56f
): Int {
    if (columnsOverride == 0) return automaticColumns
    var columns = columnsOverride.coerceIn(3, 6)
    while (columns > 1 && (availableWidthDp - spacingDp * (columns - 1)) / columns < minimumCellWidthDp) {
        columns--
    }
    return columns
}

/** Uses the grid's actual constraints, including bounded pages and modal sheet widths. */
internal data class ShelfGridCells(val columnsOverride: Int = 0) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val columns = shelfGridColumnsWithOverride(
            availableSize / density,
            spacing / density,
            shelfGridColumnCount(availableSize / density, spacing / density),
            columnsOverride,
            minimumCellWidthDp = ceil(56f * density) / density
        )
        return shelfGridCellSizes(availableSize, spacing, columns)
    }
}

/** Larger covers on a tablet are an intentional reading-library presentation, not scaled phone cells. */
internal data class TabletShelfGridCells(val columnsOverride: Int = 0) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val columns = shelfGridColumnsWithOverride(
            availableSize / density,
            spacing / density,
            tabletShelfGridColumnCount(availableSize / density, spacing / density),
            columnsOverride,
            minimumCellWidthDp = ceil(56f * density) / density
        )
        return shelfGridCellSizes(availableSize, spacing, columns)
    }
}

private fun shelfGridCellSizes(availableSize: Int, spacing: Int, columns: Int): List<Int> {
    val contentSize = (availableSize - spacing * (columns - 1)).coerceAtLeast(0)
    val cellSize = contentSize / columns
    val remainder = contentSize % columns
    return List(columns) { cellSize + if (it < remainder) 1 else 0 }
}
