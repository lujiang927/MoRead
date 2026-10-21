package com.mozhi.reader.feature.bookshelf

import androidx.compose.ui.unit.Density
import com.mozhi.reader.ui.MoReadLayoutPolicy
import com.mozhi.reader.ui.MoReadWindowWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfGridLayoutTest {
    @Test
    fun phonesKeepThreeColumnsAndWideContentScalesToSix() {
        // Input excludes the existing 20dp gutters on either side.
        assertEquals(3, shelfGridColumnCount(320f))
        assertEquals(3, shelfGridColumnCount(559f))
        assertEquals(4, shelfGridColumnCount(560f))
        assertEquals(5, shelfGridColumnCount(744f))
        assertEquals(6, shelfGridColumnCount(1000f))
        assertEquals(6, shelfGridColumnCount(2000f))
    }

    @Test
    fun actualCellSizesFillAvailableSpaceIncludingRoundingAndDensity() {
        listOf(1f, 1.5f, 2f, 3f).forEach { density ->
            val available = (744f * density).toInt()
            val spacing = (14f * density).toInt()
            val cells = with(ShelfGridCells()) {
                with(Density(density)) { calculateCrossAxisCellSizes(available, spacing) }
            }
            assertEquals(5, cells.size)
            assertEquals(available, cells.sum() + spacing * (cells.size - 1))
            assertTrue(cells.max() - cells.min() <= 1)
        }
    }

    @Test
    fun tabletCoversStayReadableAfterSidebarAndGuttersAreRemoved() {
        val cases = listOf(448 to 3, 520 to 3, 612 to 3, 688 to 4, 1112 to 6, 1536 to 6)
        cases.forEach { (width, expectedColumns) ->
            val cells = with(TabletShelfGridCells()) {
                with(Density(1f)) { calculateCrossAxisCellSizes(width, 24) }
            }
            assertEquals(expectedColumns, cells.size)
            assertEquals(width, cells.sum() + 24 * (cells.size - 1))
            assertTrue(cells.min() >= 132)
        }
    }

    @Test
    fun horizontalPaddingFollowsWindowWidth() {
        assertEquals(20f, shelfGridHorizontalPaddingDp(MoReadWindowWidth.COMPACT), 0f)
        assertEquals(28f, shelfGridHorizontalPaddingDp(MoReadWindowWidth.MEDIUM), 0f)
        assertEquals(48f, shelfGridHorizontalPaddingDp(MoReadWindowWidth.EXPANDED), 0f)
    }

    @Test
    fun automaticColumnsNeverDecreaseAsWindowWidthIncreases() {
        var previousColumns = columnsForWindow(320f)
        for (windowWidth in 321..1600) {
            val columns = columnsForWindow(windowWidth.toFloat())
            assertTrue("$windowWidth dp: $previousColumns -> $columns", columns >= previousColumns)
            assertTrue(columns in 3..6)
            previousColumns = columns
        }
        assertTrue(columnsForWindow(700f) >= 3)
        assertEquals(columnsForWindow(599f), columnsForWindow(600f))
        assertEquals(columnsForWindow(839f), columnsForWindow(840f))
    }

    @Test
    fun zeroOverrideKeepsAutomaticColumnsAndThreeToSixOverrideBothPaths() {
        listOf(280f, 612f, 744f, 1000f).forEach { width ->
            listOf(14f, 24f).forEach { spacing ->
                listOf(shelfGridColumnCount(width, spacing), tabletShelfGridColumnCount(width, spacing))
                    .forEach { automaticColumns ->
                        assertEquals(
                            automaticColumns,
                            shelfGridColumnsWithOverride(width, spacing, automaticColumns, 0)
                        )
                        for (columnsOverride in 3..6) {
                            assertEquals(
                                columnsOverride,
                                shelfGridColumnsWithOverride(1000f, spacing, automaticColumns, columnsOverride)
                            )
                        }
                    }
            }
        }
        assertEquals(3, shelfGridColumnsWithOverride(0f, 14f, 3, 0))
    }

    @Test
    fun narrowOverridesReduceUntilCoversReachFiftySixDpAndKeepAtLeastOneColumn() {
        for (columnsOverride in 3..6) {
            assertEquals(2, shelfGridColumnsWithOverride(126f, 14f, 3, columnsOverride))
            assertEquals(1, shelfGridColumnsWithOverride(125f, 14f, 3, columnsOverride))
            assertEquals(2, shelfGridColumnsWithOverride(136f, 24f, 3, columnsOverride))
            assertEquals(1, shelfGridColumnsWithOverride(135f, 24f, 3, columnsOverride))
            assertEquals(1, shelfGridColumnsWithOverride(56f, 24f, 3, columnsOverride))
            assertEquals(1, shelfGridColumnsWithOverride(0f, 24f, 3, columnsOverride))
        }
    }

    @Test
    fun overriddenCellSizesFillAvailableSpaceWithBothPathsAndPixelRounding() {
        listOf(0.75f, 1f, 1.1f, 1.3f, 1.5f, 2f, 3f).forEach { density ->
            listOf(0, 55, 56, 125, 126, 135, 136, 280, 406, 407, 448, 612, 744, 1112).forEach { width ->
                for (columnsOverride in 3..6) {
                    listOf(ShelfGridCells(columnsOverride) to 14, TabletShelfGridCells(columnsOverride) to 24)
                        .forEach { (grid, spacingDp) ->
                            val available = (width * density).toInt()
                            val spacing = (spacingDp * density).toInt()
                            val cells = with(grid) {
                                with(Density(density)) { calculateCrossAxisCellSizes(available, spacing) }
                            }
                            assertEquals(available, cells.sum() + spacing * (cells.size - 1))
                            assertTrue(cells.max() - cells.min() <= 1)
                            assertTrue(cells.size in 1..columnsOverride)
                            if (cells.size > 1) assertTrue(cells.min() / density >= 56f)
                            if (width == 1112) assertEquals(columnsOverride, cells.size)
                        }
                }
            }
        }
    }

    @Test
    fun gridCellEqualityIncludesTheOverride() {
        assertEquals(ShelfGridCells(), ShelfGridCells(0))
        assertEquals(ShelfGridCells(4), ShelfGridCells(4))
        assertTrue(ShelfGridCells(4) != ShelfGridCells(5))
        assertEquals(TabletShelfGridCells(), TabletShelfGridCells(0))
        assertEquals(TabletShelfGridCells(4), TabletShelfGridCells(4))
        assertTrue(TabletShelfGridCells(4) != TabletShelfGridCells(5))
    }

    private fun columnsForWindow(windowWidthDp: Float): Int = when (MoReadLayoutPolicy.windowWidth(windowWidthDp)) {
        MoReadWindowWidth.COMPACT -> shelfGridColumnCount(windowWidthDp - 2 * 20f)
        MoReadWindowWidth.MEDIUM -> tabletShelfGridColumnCount(
            windowWidthDp - MoReadLayoutPolicy.NavigationRailWidthDp - 2 * 28f
        )
        MoReadWindowWidth.EXPANDED -> tabletShelfGridColumnCount(
            windowWidthDp - MoReadLayoutPolicy.TabletSidebarWidthDp - 2 * 48f
        )
    }
}
