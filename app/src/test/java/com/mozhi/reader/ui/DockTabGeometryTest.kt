package com.mozhi.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockTabGeometryTest {
    private val geometry = DockTabGeometry(start = 10f, tabWidth = 100f, count = 4)

    @Test fun fingerPositionMapsToTheTabUnderIt() {
        assertEquals(0, geometry.indexAt(10f))
        assertEquals(0, geometry.indexAt(109f))
        assertEquals(1, geometry.indexAt(110f))
        assertEquals(3, geometry.indexAt(409f))
    }

    @Test fun positionsOutsideTheDockClampToTheEdgeTabs() {
        assertEquals(0, geometry.indexAt(-200f))
        assertEquals(0, geometry.indexAt(5f))
        assertEquals(3, geometry.indexAt(900f))
    }

    @Test fun lensFollowsTheFingerBetweenTheOuterTabCenters() {
        assertEquals(60f, geometry.lensCenter(60f, 18f), 0f)
        assertEquals(237f, geometry.lensCenter(237f, 18f), 0f)
        assertEquals(360f, geometry.lensCenter(360f, 18f), 0f)
    }

    @Test fun draggingPastTheEndsIsDampedAndBounded() {
        val slightlyOut = geometry.lensCenter(360f + 10f, 18f)
        assertTrue(slightlyOut > 360f && slightlyOut < 370f)
        val farOut = geometry.lensCenter(360f + 2000f, 18f)
        assertTrue(farOut < 360f + 18f)
        assertTrue(farOut > slightlyOut)
        val leftOut = geometry.lensCenter(60f - 2000f, 18f)
        assertTrue(leftOut > 60f - 18f && leftOut < 60f)
    }

    @Test fun pixelPositionsMapToContinuousTabIndices() {
        assertEquals(0f, geometry.positionAt(60f), 0.0001f)
        assertEquals(2.5f, geometry.positionAt(310f), 0.0001f)
    }

    @Test fun trackInterpolatesCentersAndWidthsBetweenTabs() {
        val track = DockTrack(10f, floatArrayOf(100f, 100f, 100f, 100f))
        assertEquals(60f, track.centerAt(0f), 0.0001f)
        assertEquals(210f, track.centerAt(1.5f), 0.0001f)
        assertEquals(360f, track.centerAt(3f), 0.0001f)
        assertEquals(100f, track.widthAt(2.3f), 0.0001f)
    }

    @Test fun indicatorStaysOnItsTabWhileTheDockIsResizing() {
        // 切页时舱在伸缩：同一个序号在每帧的新宽度下都落在该页签正中，墨底不会被宽度过渡拖住。
        val narrow = DockTrack(5f, floatArrayOf(68f, 68f, 68f, 68f))
        val wide = DockTrack(5f, floatArrayOf(86f, 86f, 86f, 86f))
        assertEquals(5f + 68f * 2.5f, narrow.centerAt(2f), 0.0001f)
        assertEquals(5f + 86f * 2.5f, wide.centerAt(2f), 0.0001f)
        assertEquals(86f, wide.widthAt(2f), 0.0001f)
    }

    @Test fun collapsedTrackKeepsTheOnlyVisibleTabAndExtrapolatesPastTheEnds() {
        val collapsed = DockTrack(5f, floatArrayOf(0f, 52f, 0f, 0f))
        assertEquals(31f, collapsed.centerAt(1f), 0.0001f)
        assertEquals(52f, collapsed.widthAt(1f), 0.0001f)
        val track = DockTrack(10f, floatArrayOf(100f, 100f, 100f, 100f))
        assertEquals(30f, track.centerAt(-0.3f), 0.0001f)
        assertEquals(380f, track.centerAt(3.2f), 0.0001f)
    }
}
