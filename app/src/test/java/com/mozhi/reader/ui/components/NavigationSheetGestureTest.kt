package com.mozhi.reader.ui.components

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mozhi.reader.ui.theme.MoReadTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h892dp-mdpi")
@OptIn(ExperimentalMaterial3Api::class)
class NavigationSheetGestureTest {
    @get:Rule val compose = createComposeRule()
    private var viewportHeight = 0
    private var dismissed = 0
    private lateinit var view: View

    private fun show() {
        compose.setContent {
            MoReadTheme {
                NavigationSheet({ dismissed++ }, MaterialTheme.colorScheme.surface,
                    MaterialTheme.colorScheme.onSurface, BottomSheetDefaults.ScrimColor) {
                    val currentView = LocalView.current
                    SideEffect { view = currentView }
                    Column(Modifier.fillMaxSize().onSizeChanged { viewportHeight = it.height }) {
                        Text("目录", Modifier.fillMaxWidth().height(80.dp).testTag("gesture-header"))
                        LazyColumn(state = rememberLazyListState(), modifier = Modifier.weight(1f).testTag("gesture-list")) {
                            items(60) { Text("章节 $it", Modifier.fillMaxWidth().height(56.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 72, 0, 0))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 72, 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 32))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 32))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build())
        }
        compose.waitForIdle()
    }

    @Test fun headerDragKeepsViewportHeightAndMovesOnlyDownBeforeDismissal() {
        show()
        val height = viewportHeight
        val header = compose.onNodeWithTag("gesture-header")
        // Inject through the stationary dialog root; the header itself moves during this gesture.
        val input = compose.onNode(isDialog())
        val start = header.fetchSemanticsNode().boundsInRoot.center - input.fetchSemanticsNode().boundsInRoot.topLeft
        input.performTouchInput { down(start); moveBy(Offset(0f, 30f)) }
        compose.mainClock.advanceTimeByFrame()
        var previous = top()
        repeat(5) {
            input.performTouchInput { moveBy(Offset(0f, 24f), delayMillis = 32) }
            compose.mainClock.advanceTimeByFrame()
            assertEquals("下拉不能重新测量视口、改变停靠点", height, viewportHeight)
            assertTrue("持续下拉时弹层不能逆向跳动", top() >= previous - .5f)
            previous = top()
        }
        input.performTouchInput { moveBy(Offset(0f, 700f), delayMillis = 300); up() }
        assertEquals(height, viewportHeight)
        compose.waitUntil(5000) { dismissed == 1 }
    }

    @Test fun listBoundaryDoesNotMoveTheSheetDuringDirectionChanges() {
        show()
        val initial = top()
        val list = compose.onNodeWithTag("gesture-list")
        list.performTouchInput { down(center) }
        listOf(70f, 70f, -50f, 70f, -120f, 140f).forEach { delta ->
            list.performTouchInput { moveBy(Offset(0f, delta), delayMillis = 32) }
            assertEquals(initial, top(), .5f)
            assertDocked()
        }
        list.performTouchInput { up() }
        assertEquals(0, dismissed)
    }

    @Test fun upwardHeaderDragCannotLiftOrResizeTheSheet() {
        show()
        val initial = top()
        val height = viewportHeight
        val header = compose.onNodeWithTag("gesture-header")
        header.performTouchInput { down(center) }
        repeat(5) {
            header.performTouchInput { moveBy(Offset(0f, -60f), delayMillis = 32) }
            assertEquals("弹层没有上拉停靠点", initial, top(), .5f)
            assertEquals(height, viewportHeight)
            assertDocked()
        }
        header.performTouchInput { up() }
        assertEquals(0, dismissed)
    }

    private fun top() = compose.onNodeWithTag("gesture-header").fetchSemanticsNode().boundsInRoot.top
    private fun assertDocked() {
        val bottom = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot.bottom
        assertEquals("内容和底部安全区应完整贴住窗口底边", view.rootView.height.toFloat(), bottom + 32f, 1f)
    }
}
