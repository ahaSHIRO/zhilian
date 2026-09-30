package com.baiyin.zhilian.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.BottomBarStyle
import com.baiyin.zhilian.ui.navigation.TopLevelDestination
import com.baiyin.zhilian.ui.theme.LocalZhilianDarkTheme
import com.baiyin.zhilian.ui.theme.ZhilianSpacing
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.roundToInt

/** 底栏高度（悬浮胶囊形态，与官方示例一致） */
private val BAR_HEIGHT = 64.dp

/** 整条 bar 按下时的外扩量（官方为 16dp，这里取等比近似值） */
private val BAR_PRESS_EXPAND = 10.dp

/** 选中胶囊按下时的膨胀上限（官方 `pressedScale = 78/56`） */
private const val CAPSULE_PRESS_SCALE = 1.36f

/**
 * 选中胶囊的极淡强调底色（ADR-0010 修订）。
 *
 * 选中态本身是全透玻璃、靠高光与内阴影塑形，但背景花哨时「当前在哪个 tab」
 * 会不好认，故留这一层几乎不损通透的底色兜底。
 */
private const val GLASS_TINT = 0.05f

/** 磨砂档选中胶囊的基准底色（无着色器，靠底色 + 亮描边模拟玻璃边缘） */
private const val FROSTED_TINT = 0.16f

/** 玻璃折射带基准厚度：未按下时的「玻璃厚度感」，按下时在此基础上略增 */
private val GLASS_REFRACTION_HEIGHT = 10.dp

/** 玻璃折射基准位移量，同样在按下时略增 */
private val GLASS_REFRACTION_AMOUNT = 14.dp

/** 底栏本体的内缘压暗半径（比胶囊更收敛：底栏面积大，半径过大会显脏） */
private val BAR_INNER_SHADOW = 10.dp

/**
 * 知练底部导航栏（ADR-0010，含 2026-09-29 修订：支持按住拖动切换）。
 *
 * 三档共用同一套「悬浮胶囊」版式（左右留边、全圆角、[BAR_HEIGHT]），切换只换材质不重排版式：
 * - [BottomBarStyle.STANDARD] 不透明明快表面，选中态胶囊底色 + M3 原生涟漪
 * - [BottomBarStyle.LIQUID_GLASS] 折射 [backdrop]；按压缩放 + 镜面高光 + 内阴影 + 外投影 + 边缘色散
 * - [BottomBarStyle.FROSTED] 半透明磨砂面，同一套按压缩放但无着色器（低版本可用）
 *
 * 交互（三档一致）：
 * - **点击** tab → 直接切换；
 * - **水平拖动** → 胶囊跟手滑动、拖快时拉伸，松手吸附到最近 tab 并切换页面；
 * - **纵向滑动** → 不接管（原地弹回），把事件留给下层，不堵住未来的纵向手势。
 *
 * 底栏是**浮层**：调用方必须让页面内容延伸到屏幕底部（不给底部避让），否则玻璃背后没有
 * 可折射的内容、效果会退化成一块半透明色块。各屏用 [rememberBottomBarContentPadding] 留白。
 */
@Composable
fun ZhilianBottomBar(
    style: BottomBarStyle,
    destinations: List<TopLevelDestination>,
    selectedRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val interactive = style != BottomBarStyle.STANDARD
    val selectedIndex = destinations.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val dragState = remember(scope, destinations.size) {
        BottomBarDragState(scope, destinations.size, selectedIndex)
    }

    // 外部选中项变化（点击 tab / 路由返回）时同步胶囊；拖动中由 dragState 自己管
    LaunchedEffect(selectedIndex) { dragState.syncTo(selectedIndex) }

    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            // 底栏自身避开手势条与键盘：内容穿到底栏背后，但底栏不能压在小白条上
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = ZhilianSpacing.screenEdge, vertical = ZhilianSpacing.sm),
    ) {
        val slotWidth = maxWidth / destinations.size
        val progress = dragState.pressProgress

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .graphicsLayer {
                    val w = size.width
                    if (w > 0f && interactive) {
                        val s = lerp(1f, 1f + BAR_PRESS_EXPAND.toPx() / w, progress)
                        scaleX = s
                        scaleY = s
                    }
                }
                .then(barMaterial(style, backdrop))
                // 拖动层挂在材质之上、item 之下：整条 bar 可起拖，且不阻断子项点击
                .dragToSwitch(
                    state = dragState,
                    tabCount = destinations.size,
                    interactive = interactive,
                    slotWidth = slotWidth,
                    onCrossTab = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    onSwitch = { index ->
                        destinations.getOrNull(index)?.let(onSelect)
                    },
                ),
        ) {
            CapsuleIndicator(
                style = style,
                backdrop = backdrop,
                progress = progress,
                slotWidth = slotWidth,
                position = dragState.value,
                velocity = dragState.velocity,
            )

            Row(
                modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                destinations.forEach { destination ->
                    val selected = destination.route == selectedRoute
                    BottomBarItem(
                        destination = destination,
                        selected = selected,
                        interactive = interactive,
                        onClick = { onSelect(destination) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 水平拖动切换 tab 的手势层。
 *
 * 三个关键取舍（ADR-0010 修订）：
 * - **整条 bar 可起拖**，不限定选中胶囊——官方只在胶囊上挂手势，按偏了就拖不动，
 *   而用户无从知道这个限制；
 * - **只接管水平**：横向位移先过 [DRAG_TOUCH_SLOP] 才判为拖动，纵向占优则放手
 *   （`cancelDrag` 原地弹回），不抢下层的纵向手势；
 * - **位移未过阈值**则整段当点击处理——按下时的按压反馈随即撤掉，点击由子项自己响应。
 *
 * 拖动期间不消费「未成拖动」的事件：`awaitTouchSlopOrCancellation` 只在真正跨过
 * 阈值后才消费，故点击与纵向滚动都能正常落到别处。
 */
private fun Modifier.dragToSwitch(
    state: BottomBarDragState,
    tabCount: Int,
    interactive: Boolean,
    slotWidth: Dp,
    onCrossTab: () -> Unit,
    onSwitch: (Int) -> Unit,
): Modifier = this.pointerInput(tabCount, interactive) {
    if (!interactive) return@pointerInput
    val slotPx = slotWidth.toPx()
    if (slotPx <= 0f) return@pointerInput

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        state.press()

        // 未跨过触摸阈值就松手 → 当作点击：撤掉按压态，事件本就没被消费
        val slopChange = awaitTouchSlopOrCancellation(down.id) { change, over ->
            // 只认水平占优的拖动；纵向占优则交给下层（此处不消费即可）
            if (abs(over.x) <= abs(over.y)) return@awaitTouchSlopOrCancellation
            change.consume()
        }

        if (slopChange == null) {
            state.releasePress()
            return@awaitEachGesture
        }

        state.beginDrag()
        val startX = slopChange.position.x
        val startValue = state.value
        var lastCrossed = state.nearestIndex()

        // 逐帧跟手：位移折算成 tab 序号，超出两端时阻尼收敛（拖到头有阻力感而非硬停）
        horizontalDrag(slopChange.id) { change ->
            val dx = change.position.x - startX
            change.consume()
            state.drag(BottomBarDragMath.indexForDrag(startValue, dx, slotPx, tabCount))
            val crossed = state.nearestIndex()
            if (crossed != lastCrossed) {
                lastCrossed = crossed
                onCrossTab()
            }
        }

        state.settle(onSwitch)
    }
}

/** 判定拖动成立的触摸阈值（水平位移超过它才算拖） */
private val DRAG_TOUCH_SLOP = 12.dp

/**
 * 页面内容为底栏预留的底部空间（底栏高度 + 其上下留白 + 系统手势条）。
 *
 * - `LazyColumn` 用 `contentPadding = PaddingValues(bottom = …)`
 * - `verticalScroll` 的 Column 在**内容末尾**加一个该高度的 `Spacer`
 *   （不能用外层 padding，否则内容无法滚到底栏背后、失去穿透效果）
 */
@Composable
fun rememberBottomBarContentPadding(): Dp {
    val bars = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return BAR_HEIGHT + ZhilianSpacing.sm * 2 + bars
}

/* ------------------------------------------------------------------ 材质层 */

@Composable
private fun barMaterial(style: BottomBarStyle, backdrop: Backdrop): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val dark = LocalZhilianDarkTheme.current
    return when (style) {
        BottomBarStyle.STANDARD -> Modifier
            .background(surface, Capsule())
            .border(1.dp, outline.copy(alpha = 0.5f), Capsule())

        BottomBarStyle.FROSTED -> Modifier
            .background(
                Brush.verticalGradient(
                    listOf(surface.copy(alpha = 0.94f), surface.copy(alpha = 0.86f))
                ),
                Capsule(),
            )
            .border(1.dp, outline.copy(alpha = 0.6f), Capsule())

        // 与选中胶囊同一套配方（色散 + 高光 + 内阴影 + 外投影）。
        // 底栏是四个 tab **常驻**的那一层：只有胶囊带高级材质时，观感会变成
        // 「按住选中项才漂亮、平时和别的 tab 一样普通」。底栏本体补齐后，
        // 无论停在哪个 tab，材质语言都一致。
        BottomBarStyle.LIQUID_GLASS -> Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { Capsule() },
            effects = {
                vibrancy()
                blur(8.dp.toPx())
                lens(
                    24.dp.toPx(),
                    24.dp.toPx(),
                    chromaticAberration = true,
                )
            },
            highlight = { Highlight.Default },
            shadow = { Shadow() },
            innerShadow = { InnerShadow(radius = BAR_INNER_SHADOW) },
            // 半透明面层：玻璃必须用一点实色换可读性（官方教程步骤 4）
            onDrawSurface = { drawRect(surface.copy(alpha = if (dark) 0.50f else 0.58f)) },
        )
    }
}

/* ------------------------------------------------------------------ 选中胶囊 */

/**
 * 选中胶囊。位置由 [position]（浮点 tab 序号）驱动——点击时由外部同步动画过来，
 * 拖动时逐帧跟手；[velocity] 驱动拉伸：拖快时沿运动方向拉长、垂直方向收窄，
 * 做出液滴被「甩」出去的感觉（官方 `layerBlock` 里的速度项）。
 *
 * **玻璃塑形是常驻的**（ADR-0010 修订）：高光 / 内阴影 / 外投影 / 边缘色散不随
 * [progress] 从零开起——选中胶囊本身就是一块玻璃，否则松手后玻璃感整套消失、
 * 退回一块静态淡色块（曾如此，与长按态明显不一致）。
 * [progress] 如今只负责两件事：**缩放膨胀**，以及在此基础上**轻微加强**（鼓起的
 * 液滴理应折射更多，故折射量是「基准 + 增量」而非「从零满上」）。
 */
@Composable
private fun CapsuleIndicator(
    style: BottomBarStyle,
    backdrop: Backdrop,
    progress: Float,
    slotWidth: Dp,
    position: Float,
    velocity: Float,
) {
    val accent = MaterialTheme.colorScheme.primary
    val selectedSurface = MaterialTheme.colorScheme.secondaryContainer

    Box(
        modifier = Modifier
            .width(slotWidth)
            .fillMaxHeight()
            // 位置用 layout 级 offset（不用 graphicsLayer.translationX）：
            // drawBackdrop 用 LayoutCoordinates 决定从背景的哪个位置采样，
            // graphicsLayer 的 translationX 是绘制期变换、不改变 layout 坐标
            // → 位置≠0 时背景采样始终指向 slot 0 → 只在练习位有效（曾如此，见 pitfalls 2.12）
            // 位置用 layout 级 offset（不用 graphicsLayer.translationX）：
            // drawBackdrop 依 LayoutCoordinates 采样背景，graphicsLayer 是绘制期变换、
            // 不改布局坐标——虽然实测在 backdrop 2.0.1 上两者像素无差异（见 pitfalls 2.12），
            // 但 offset 才是语义正确的一侧，且不依赖库实现细节。
            .offset { IntOffset((slotWidth.toPx() * position).roundToInt(), 0) }
            .padding(horizontal = ZhilianSpacing.xs, vertical = ZhilianSpacing.sm)
            .then(
                when (style) {
                    BottomBarStyle.STANDARD -> Modifier
                        .graphicsLayer {
                            val bulge = lerp(1f, CAPSULE_PRESS_SCALE, progress)
                            scaleX = bulge * BottomBarDragMath.stretchX(velocity)
                            scaleY = bulge * BottomBarDragMath.stretchY(velocity)
                        }
                        .background(selectedSurface, Capsule())

                    // 磨砂无着色器，用「稍实的强调底 + 一层亮描边」模拟玻璃边缘。
                    // graphicsLayer 必须在 background/border 之前，否则缩放作用不到它们上
                    BottomBarStyle.FROSTED -> Modifier
                        .graphicsLayer {
                            val bulge = lerp(1f, CAPSULE_PRESS_SCALE, progress)
                            scaleX = bulge * BottomBarDragMath.stretchX(velocity)
                            scaleY = bulge * BottomBarDragMath.stretchY(velocity)
                        }
                        .background(
                            accent.copy(alpha = FROSTED_TINT + 0.10f * progress),
                            Capsule(),
                        )
                        .border(1.dp, accent.copy(alpha = 0.45f), Capsule())

                    // 官方配方：边缘色散折射 + 45° 镜面高光 + 内缘压暗 + 外投影。
                    // 四者常驻，`progress` 只在基准之上叠加一个小的增量。
                    // 缩放进 layerBlock（官方做法）：不能放外部 graphicsLayer，
                    // 否则会连带缩放背景采样
                    BottomBarStyle.LIQUID_GLASS -> Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            lens(
                                GLASS_REFRACTION_HEIGHT.toPx() * (1f + 0.4f * progress),
                                GLASS_REFRACTION_AMOUNT.toPx() * (1f + 0.4f * progress),
                                chromaticAberration = true,
                            )
                        },
                        layerBlock = {
                            val bulge = lerp(1f, CAPSULE_PRESS_SCALE, progress)
                            scaleX = bulge * BottomBarDragMath.stretchX(velocity)
                            scaleY = bulge * BottomBarDragMath.stretchY(velocity)
                        },
                        highlight = { Highlight.Default },
                        shadow = { Shadow() },
                        innerShadow = {
                            InnerShadow(radius = 8.dp * (1f + 0.25f * progress))
                        },
                        // 极淡强调底色：几乎不影响通透，但保证花哨背景上一眼认得出选中位置
                        onDrawSurface = {
                            drawRect(accent.copy(alpha = GLASS_TINT))
                        },
                    )
                }
            ),
    )
}

/* ------------------------------------------------------------------ 单个 tab */

/**
 * 单个 tab。**不处理按压**：整条 bar 的按压/拖动由父层 `dragToSwitch` 统一接管，
 * 子项只保留点击（标准档带 M3 涟漪）。
 *
 * 曾经这里也挂了一个 `pointerInput` 做「按下鼓起、松手撤回」，结果在拖动时被父层的
 * `change.consume()` 打断——`waitForUpOrCancellation()` 返回 null（取消）后它仍无条件
 * 撤回按压，把父层刚建立的按压态拆掉，胶囊于是全程不膨胀。见 pitfalls 2.12。
 */
@Composable
private fun BottomBarItem(
    destination: TopLevelDestination,
    selected: Boolean,
    interactive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(Capsule())
            .then(
                // 标准档保留 M3 原生涟漪；玻璃/磨砂档不要涟漪（缩放反馈已足够，涟漪会脏）
                if (interactive) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(
                if (selected) destination.selectedIconRes else destination.unselectedIconRes
            ),
            contentDescription = stringResource(destination.labelRes),
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = stringResource(destination.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            textAlign = TextAlign.Center,
        )
    }
}

/** 供设置页/其他处展示三档名称 */
@Composable
fun BottomBarStyle.label(): String = stringResource(
    when (this) {
        BottomBarStyle.STANDARD -> R.string.bottom_bar_standard
        BottomBarStyle.LIQUID_GLASS -> R.string.bottom_bar_liquid_glass
        BottomBarStyle.FROSTED -> R.string.bottom_bar_frosted
    }
)
