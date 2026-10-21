package com.mozhi.reader.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.ui.components.BlurredGlassSurface
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.accentColor
import com.mozhi.reader.ui.theme.isDarkTheme
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.floor

/**
 * 手机悬浮导航舱（按设计稿：62dp 高的玻璃胶囊，图标在上、字在下，选中项是一块淡墨底）。
 * 页签宽度由可用宽度算出：书架页右侧还要放一枚搜索钮，舱和钮无论屏幕多窄都不会叠在一起；
 * 右侧没有钮的页面舱舒展拉满整行。
 * 收起时只剩当前页一枚（不带字）；书架搜索展开时舱收起、搜索胶囊占满其余宽度。
 *
 * 选中底是一整块会滑动的强调色底，不是各页签各自变色。手指一按下就把它「拿起来」成一枚
 * 玻璃透镜（不等长按，交互模型参照 iOS 26 标签栏与 Kyant0/AndroidLiquidGlass 的 LiquidBottomTabs），
 * 跟手滑过各页签、逐格轻震、快滑时被拉长，松手落到哪页就切到哪页；收起态点一下先展开。
 */
@Composable
internal fun FloatingRootDock(
    hazeState: HazeState,
    selectedRoute: String?,
    dockState: RootDockState?,
    trailing: DockAction?,
    onSelect: (RootDestination) -> Unit,
    modifier: Modifier = Modifier,
    /** 允许随内容滚动收成一粒；关闭时舱的形状始终不变。 */
    collapsible: Boolean = false
) {
    val search = trailing as? DockAction.Search
    val searchOpen = search != null && dockState?.searchOpen == true
    val collapsed = (collapsible && dockState?.collapsed == true) || searchOpen
    val shadowDark = isDarkTheme()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val entries = RootDestination.entries
    val selectedIndex = entries.indexOfFirst { it.route == selectedRoute }
    // 点按/松手后先让墨底滑过去，不等导航回传选中页；回传不来（被拦下）就在时限后作废。
    var pendingIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(selectedIndex, pendingIndex) {
        if (pendingIndex < 0) return@LaunchedEffect
        if (pendingIndex != selectedIndex) delay(PENDING_SELECTION_TIMEOUT_MS)
        pendingIndex = -1
    }
    val activeIndex = if (pendingIndex >= 0) pendingIndex else selectedIndex
    // 手指按在舱上期间为真：透镜浮起，墨底位置由手势直接驱动。
    var holding by remember { mutableStateOf(false) }
    var hoverIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val tabCount = entries.size
        // 右侧有动作钮时给它让位；没有（统计、设置）时舱舒展拉满整行，切页时页签宽度随之过渡。
        val tabWidth = if (trailing != null) {
            dockTabWidth(maxWidth, RootDockHeight + RootDockGap, tabCount)
        } else {
            dockFullTabWidth(maxWidth, tabCount)
        }
        val tabWidths = entries.mapIndexed { index, _ ->
            val visible = index == activeIndex || !collapsed
            animateDpAsState(
                targetValue = when {
                    !visible -> 0.dp
                    collapsed -> DockCompactTabWidth
                    else -> tabWidth
                },
                animationSpec = tween(DOCK_MOTION_MS),
                label = "nav-dock-tab-width"
            ).value
        }
        val padPx = with(density) { DockPadding.toPx() }
        val geometry = DockTabGeometry(padPx, with(density) { tabWidth.toPx() }, tabCount)
        val overshootPx = with(density) { DockLensOvershoot.toPx() }
        // 墨底在「页签序号」空间里动画，画的时候才按页签当前（可能正在过渡的）宽度换算成像素：
        // 切页时舱同时在伸缩，宽度每帧都变，也不会打断墨底的滑动。
        val track = DockTrack(padPx, FloatArray(tabCount) { with(density) { tabWidths[it].toPx() } })

        val indicator = remember { Animatable(activeIndex.coerceAtLeast(0).toFloat(), 0.001f) }
        val currentActive by rememberUpdatedState(activeIndex)
        LaunchedEffect(indicator) {
            // collect + launch 而不是 collectLatest：新动画直接接管旧动画，保留速度与帧时间，不会顿一下。
            snapshotFlow { holding to currentActive }.collect { (hold, index) ->
                // 按住期间由手势直接驱动，这里不插手。
                if (!hold && index >= 0) launch { indicator.animateTo(index.toFloat(), IndicatorSpring) }
            }
        }
        val restAlpha by animateFloatAsState(
            targetValue = if (activeIndex >= 0 && !collapsed && !holding) 1f else 0f,
            animationSpec = tween(if (holding) 90 else 200),
            label = "nav-dock-indicator-alpha"
        )
        val lens = remember { Animatable(0f) }
        // 透镜随速度横向拉长、纵向压扁（0..1），松手回弹。
        val stretch = remember { Animatable(0f) }
        val tabWidthPx = geometry.tabWidth
        LaunchedEffect(holding) {
            if (holding) {
                lens.animateTo(1f, LensSpring)
            } else {
                // 等墨底差不多落到目标页再收起透镜，落点看得清。
                withTimeoutOrNull(LENS_SETTLE_WAIT_MS) {
                    snapshotFlow { abs(indicator.value - currentActive) < 0.2f }.first { it }
                }
                lens.animateTo(0f, LensSpring)
            }
        }

        // 选中底与选中页签取用户的强调色（默认灰阶方案下它本就是墨色）。
        val accent = accentColor()
        val indicatorAlpha = if (shadowDark) 0.22f else 0.14f
        val currentGeometry by rememberUpdatedState(geometry)
        val currentCollapsed by rememberUpdatedState(collapsed)
        val currentSearchOpen by rememberUpdatedState(searchOpen)
        val currentOnSelect by rememberUpdatedState(onSelect)
        val tapTab: (Int) -> Unit = { index ->
            when {
                searchOpen -> {
                    search?.onQueryChange?.invoke("")
                    dockState?.closeSearch()
                }
                collapsed -> dockState?.collapsed = false
                else -> {
                    if (index != activeIndex) pendingIndex = index
                    onSelect(entries[index])
                }
            }
        }
        val currentTap by rememberUpdatedState(tapTab)

        BlurredGlassSurface(
            hazeState = hazeState,
            shape = MoReadTokens.CapsuleShape,
            tint = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .floatingGlassShadow(shadowDark)
                .selectableGroup()
        ) {
            Row(
                modifier = Modifier
                    .height(RootDockHeight)
                    .drawBehind {
                        if (restAlpha <= 0f) return@drawBehind
                        val position = indicator.value
                        val w = track.widthAt(position)
                        if (w <= 0f) return@drawBehind
                        val h = size.height - padPx * 2
                        drawRoundRect(
                            color = accent.copy(alpha = indicatorAlpha * restAlpha),
                            topLeft = Offset(track.centerAt(position) - w / 2f, padPx),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(h / 2f)
                        )
                    }
                    .pointerInput(Unit) {
                        val velocity = VelocityTracker()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (currentSearchOpen || currentCollapsed) {
                                // 收起态 / 搜索态的舱只是一枚按钮：抬手即响应，不进拖拽。
                                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                                up.consume()
                                currentTap(currentGeometry.indexAt(down.position.x))
                                return@awaitEachGesture
                            }
                            // 按下即拿起（不等长按）：透镜立刻浮起并跳到指下，之后每一帧贴着手指走。
                            val startIndex = currentGeometry.indexAt(down.position.x)
                            velocity.resetTracking()
                            velocity.addPosition(down.uptimeMillis, down.position)
                            hoverIndex = startIndex
                            holding = true
                            fun follow(x: Float) {
                                val target = currentGeometry.positionAt(currentGeometry.lensCenter(x, overshootPx))
                                scope.launch { indicator.animateTo(target, FollowSpring) }
                            }
                            follow(down.position.x)
                            var moved = false
                            var released = false
                            var lastX = down.position.x
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUpIgnoreConsumed()) {
                                        change.consume()
                                        released = true
                                        break
                                    }
                                    if (!moved && abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) moved = true
                                    if (!moved) continue
                                    change.consume()
                                    lastX = change.position.x
                                    velocity.addPosition(change.uptimeMillis, change.position)
                                    follow(lastX)
                                    val speed = abs(velocity.calculateVelocity().x) / STRETCH_FULL_VELOCITY
                                    scope.launch { stretch.animateTo(speed.coerceAtMost(1f), StretchSpring) }
                                    val index = currentGeometry.indexAt(lastX)
                                    if (index != hoverIndex) {
                                        hoverIndex = index
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    }
                                }
                            } finally {
                                val target = if (moved) currentGeometry.indexAt(lastX) else startIndex
                                scope.launch { stretch.animateTo(0f, StretchSpring) }
                                if (released && target != currentActive) {
                                    pendingIndex = target
                                    currentOnSelect(entries[target])
                                }
                                hoverIndex = -1
                                holding = false
                            }
                        }
                    }
                    .padding(DockPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                entries.forEachIndexed { index, item ->
                    val visible = index == activeIndex || !collapsed
                    val alpha by animateFloatAsState(
                        targetValue = if (visible) 1f else 0f,
                        animationSpec = tween(if (visible) 220 else 140),
                        label = "nav-dock-tab-alpha"
                    )
                    val width = tabWidths[index]
                    if (width > 0.5.dp) {
                        DockTab(
                            item = item,
                            selected = index == activeIndex,
                            emphasized = if (holding) index == hoverIndex else index == activeIndex,
                            magnified = holding && index == hoverIndex,
                            compact = collapsed,
                            modifier = Modifier
                                .width(width)
                                .fillMaxHeight()
                                .graphicsLayer { this.alpha = alpha },
                            onTap = { tapTab(index) }
                        )
                    }
                }
            }
        }
        val lensWidth = tabWidth + DockLensGrow * 2
        val lensHeight = RootDockHeight + DockLensGrow
        val lensWidthPx = with(density) { lensWidth.toPx() }
        val lensHeightPx = with(density) { lensHeight.toPx() }
        val restHeightPx = with(density) { (RootDockHeight - DockPadding * 2).toPx() }
        // 透镜常驻组合树、静止时透明：按下那一帧只改图层参数，不临时建节点。
        DockLens(
            dark = shadowDark,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(lensWidth, lensHeight)
                .graphicsLayer {
                    // 进度 0 时与静止墨底同尺寸同位置，拿起时胀大浮起，弹簧带一点回弹；快速滑动时被拉长。
                    val p = lens.value
                    val s = stretch.value * p.coerceIn(0f, 1f)
                    translationX = track.centerAt(indicator.value) - lensWidthPx / 2f
                    scaleX = lerp(tabWidthPx / lensWidthPx, 1f, p) * (1f + 0.24f * s)
                    scaleY = lerp(restHeightPx / lensHeightPx, 1f, p) * (1f - 0.12f * s)
                    alpha = p.coerceIn(0f, 1f)
                }
        )
        androidx.compose.animation.AnimatedContent(
            targetState = trailing?.kind,
            modifier = Modifier.align(Alignment.CenterEnd),
            transitionSpec = {
                // 从「无钮」切来时舱正在收窄让位：钮晚一拍出现，不和舱尾叠在一起。
                val delay = if (initialState == null) TRAILING_ENTER_DELAY_MS else 0
                (androidx.compose.animation.fadeIn(tween(200, delayMillis = delay)) +
                    androidx.compose.animation.scaleIn(tween(260, delayMillis = delay), initialScale = 0.8f))
                    .togetherWith(androidx.compose.animation.fadeOut(tween(120)))
            },
            contentAlignment = Alignment.CenterEnd,
            label = "dock-trailing"
        ) { kind ->
            when (kind) {
                DockAction.Kind.SEARCH -> if (search != null) {
                    val openWidth = (maxWidth - RootDockHeight - RootDockGap).coerceAtLeast(RootDockHeight)
                    val width by animateDpAsState(
                        targetValue = if (searchOpen) openWidth else RootDockHeight,
                        animationSpec = tween(DOCK_MOTION_MS),
                        label = "shelf-search-width"
                    )
                    ShelfDockSearch(
                        hazeState = hazeState,
                        open = searchOpen,
                        query = search.query,
                        width = width,
                        onQueryChange = search.onQueryChange,
                        onOpen = { dockState?.openSearch() },
                        onClose = {
                            search.onQueryChange("")
                            dockState?.closeSearch()
                        },
                        modifier = Modifier.floatingGlassShadow(shadowDark)
                    )
                }
                DockAction.Kind.BUTTON -> (trailing as? DockAction.Button)?.let { action ->
                    DockRoundButton(hazeState, action, Modifier.floatingGlassShadow(shadowDark))
                }
                null -> Box(Modifier)
            }
        }
    }
}

/** 舱右侧的上下文动作：书架是可展开的搜索，伴读页是「新的书库伴读」，其余页留空。 */
internal sealed interface DockAction {
    val kind: Kind

    enum class Kind { SEARCH, BUTTON }

    data class Search(val query: String, val onQueryChange: (String) -> Unit) : DockAction {
        override val kind = Kind.SEARCH
    }

    data class Button(
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val label: String,
        val onClick: () -> Unit
    ) : DockAction {
        override val kind = Kind.BUTTON
    }
}

/**
 * 展开态舱内页签的横向几何（像素，原点在舱左缘）：手势命中与透镜位置共用。
 * 收起态拿起时舱正在展开，按展开后的几何算，手指落点与最终页签位置一致。
 */
internal class DockTabGeometry(val start: Float, val tabWidth: Float, val count: Int) {
    fun centerOf(index: Int): Float = start + tabWidth * (index + 0.5f)

    fun indexAt(x: Float): Int = floor((x - start) / tabWidth).toInt().coerceIn(0, count - 1)

    /** 像素位置换成连续的页签序号（页签中心为整数），墨底动画就在这个空间里跑。 */
    fun positionAt(x: Float): Float = (x - start) / tabWidth - 0.5f

    /** 透镜中心跟手，拖出两端后按橡皮筋阻尼：越拉越沉，最多再走 [maxOvershoot]。 */
    fun lensCenter(x: Float, maxOvershoot: Float): Float {
        val min = centerOf(0)
        val max = centerOf(count - 1)
        return when {
            x < min -> min - rubberBand(min - x, maxOvershoot)
            x > max -> max + rubberBand(x - max, maxOvershoot)
            else -> x
        }
    }

    private fun rubberBand(distance: Float, limit: Float): Float =
        if (limit <= 0f) 0f else (1f - 1f / (distance * 0.55f / limit + 1f)) * limit
}

/**
 * 舱内页签按「当前帧」实际宽度排出的轨道（像素，原点在舱左缘）：把墨底的连续序号换成中心与宽度。
 * 收起态隐藏的页签宽度为 0，中心与相邻页签重合；序号越出两端时按端头页签宽度线性外推（橡皮筋拉出的部分）。
 */
internal class DockTrack(private val start: Float, private val widths: FloatArray) {
    private val centers = FloatArray(widths.size).also { centers ->
        var x = start
        widths.forEachIndexed { i, w ->
            centers[i] = x + w / 2f
            x += w
        }
    }

    fun centerAt(position: Float): Float {
        if (widths.isEmpty()) return start
        val last = widths.lastIndex
        return when {
            position <= 0f -> centers[0] + position * widths[0]
            position >= last -> centers[last] + (position - last) * widths[last]
            else -> {
                val i = floor(position).toInt()
                lerp(centers[i], centers[i + 1], position - i)
            }
        }
    }

    fun widthAt(position: Float): Float {
        if (widths.isEmpty()) return 0f
        val clamped = position.coerceIn(0f, widths.lastIndex.toFloat())
        val i = floor(clamped).toInt().coerceAtMost(widths.lastIndex - 1).coerceAtLeast(0)
        return if (widths.size == 1) widths[0] else lerp(widths[i], widths[i + 1], clamped - i)
    }
}

/** 拿起后的玻璃透镜：近乎透明，靠高光描边和外沿投影显出「浮在舱上」，底下页签照样看得清。 */
@Composable
private fun DockLens(dark: Boolean, modifier: Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            .floatingGlassShadow(dark)
            .clip(MoReadTokens.CapsuleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        surface.copy(alpha = if (dark) 0.22f else 0.42f),
                        surface.copy(alpha = if (dark) 0.08f else 0.14f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (dark) 0.38f else 0.95f),
                        Color.White.copy(alpha = if (dark) 0.06f else 0.3f)
                    )
                ),
                shape = MoReadTokens.CapsuleShape
            )
    )
}

@Composable
private fun DockRoundButton(hazeState: HazeState, action: DockAction.Button, modifier: Modifier) {
    BlurredGlassSurface(
        hazeState = hazeState,
        shape = MoReadTokens.CapsuleShape,
        tint = MaterialTheme.colorScheme.surface,
        modifier = modifier.size(RootDockHeight)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(MoReadTokens.CapsuleShape)
                .clickable(onClickLabel = action.label, onClick = action.onClick)
                .semantics { contentDescription = action.label },
            contentAlignment = Alignment.Center
        ) {
            Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }
    }
}

/** 页签宽度：先扣掉搜索钮与舱内边距，再四等分，最宽 68dp（设计稿值）、最窄 48dp。 */
internal fun dockTabWidth(
    available: androidx.compose.ui.unit.Dp,
    reserved: androidx.compose.ui.unit.Dp,
    tabCount: Int
): androidx.compose.ui.unit.Dp =
    ((available - reserved - DockPadding * 2) / tabCount.coerceAtLeast(1)).coerceIn(DockTabMinWidth, DockTabMaxWidth)

/** 右侧没有动作钮时舱拉满整行：页签平分全宽，不再受 68dp 上限约束。 */
internal fun dockFullTabWidth(
    available: androidx.compose.ui.unit.Dp,
    tabCount: Int
): androidx.compose.ui.unit.Dp =
    ((available - DockPadding * 2) / tabCount.coerceAtLeast(1)).coerceAtLeast(DockTabMinWidth)

/**
 * 页签本身不收手势（舱统一处理点按与拖拽），只留无障碍语义；
 * [emphasized] 决定强调色（按住时跟着透镜走），[magnified] 是透镜底下的放大。
 */
@Composable
private fun DockTab(
    item: RootDestination,
    selected: Boolean,
    emphasized: Boolean,
    magnified: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onTap: () -> Unit
) {
    val accent = accentColor()
    val content by animateColorAsState(
        targetValue = if (emphasized) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(160),
        label = "nav-dock-tab-fg"
    )
    val scale by animateFloatAsState(
        targetValue = if (magnified) 1.14f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "nav-dock-tab-scale"
    )
    val label = item.label
    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
                onClick(label = label) { onTap(); true }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = if (compact) item.label else null,
            tint = content,
            modifier = Modifier.size(22.dp)
        )
        if (!compact) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.sp),
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                color = content,
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/**
 * 悬浮玻璃件的投影：只画在形状外沿（clipOutPath），不会从半透明玻璃底下透出来把它染脏；
 * Android 9 以下 BlurMaskFilter 不走硬件加速，直接不画。
 */
internal fun Modifier.floatingGlassShadow(dark: Boolean): Modifier = drawWithCache {
    val radius = size.height / 2f
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = if (dark) android.graphics.Color.argb(150, 0, 0, 0) else android.graphics.Color.argb(60, 46, 34, 16)
        maskFilter = android.graphics.BlurMaskFilter(16.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    val outline = android.graphics.Path().apply {
        addRoundRect(0f, 0f, size.width, size.height, radius, radius, android.graphics.Path.Direction.CW)
    }
    val drop = 10.dp.toPx()
    val inset = 4.dp.toPx()
    onDrawBehind {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return@onDrawBehind
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.clipOutPath(outline)
            native.drawRoundRect(inset, drop, size.width - inset, size.height + drop * 0.6f, radius, radius, paint)
            native.restore()
        }
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

private val DockPadding = 5.dp
private val DockTabMaxWidth = 68.dp
private val DockTabMinWidth = 48.dp
private val DockCompactTabWidth = 52.dp
/** 透镜比页签宽出的半边、比舱高出的总量：拿起时略溢出舱沿，像浮在上面。 */
private val DockLensGrow = 6.dp
private val DockLensOvershoot = 18.dp
private const val DOCK_MOTION_MS = 450
private const val TRAILING_ENTER_DELAY_MS = 220
/** 松手后最多等这么久让墨底落位，再收起透镜。 */
private const val LENS_SETTLE_WAIT_MS = 260L
/** 横向速度达到此值（px/s）时透镜拉伸到最大。 */
private const val STRETCH_FULL_VELOCITY = 4000f
private const val PENDING_SELECTION_TIMEOUT_MS = 800L
private val IndicatorSpring = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
private val LensSpring = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium)
/** 按住期间追手指：临界阻尼、极硬，滞后约 1~2 帧，只滤掉触点抖动，不拖尾。 */
private val FollowSpring = spring<Float>(dampingRatio = 1f, stiffness = 5000f)
private val StretchSpring = spring<Float>(dampingRatio = 0.5f, stiffness = 300f)
