// 移植改编自 Kyant0/AndroidLiquidGlass 官方示例 LiquidBottomTabs（Apache-2.0）。
// 本应用的液态玻璃 Dock 底栏：内容从下方滚过，被实时 vibrancy+模糊+透镜折射。
// v2.9.6：移除可拖拽胶囊与隐形染色层（在部分机型上会残留重叠色块），
// 改为点击切换、弹簧滑动到位的简洁玻璃胶囊，稳定无残影。
package com.diankuang.xiaomirom.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.graphics.vector.ImageVector

val LocalLiquidAccent = staticCompositionLocalOf { Color(0xFF0088FF) }

@Composable
fun RowScope.LiquidTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
fun LiquidDockBar(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    containerColor: Color,
    accentColor: Color,
    isLightTheme: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalLiquidAccent provides accentColor) {
        BoxWithConstraints(
            modifier,
            contentAlignment = Alignment.CenterStart,
        ) {
            val density = LocalDensity.current
            val tabWidth = with(density) {
                (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
            }
            val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
            val scope = rememberCoroutineScope()

            // 选中胶囊位置（页索引，浮点）：点击换页时弹簧滑动，velocity 用于滑移拉丝
            val slide = remember { Animatable(selectedTabIndex().toFloat()) }
            val target = selectedTabIndex()
            LaunchedEffect(target, tabsCount) {
                slide.snapTo(slide.value.coerceIn(0f, (tabsCount - 1).toFloat()))
                slide.animateTo(
                    target.toFloat(),
                    spring(dampingRatio = 0.62f, stiffness = 900f),
                )
            }

            // 玻璃底盘（整条 Dock）
            Row(
                Modifier
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(50) },
                        effects = {
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .height(64f.dp)
                    .fillMaxWidth()
                    .padding(4f.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )

            // 选中项液态玻璃胶囊：跟随滑动，滑动中轻微拉丝回弹
            Box(
                Modifier
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer {
                        translationX =
                            if (isLtr) slide.value * tabWidth
                            else size.width - (slide.value + 1f) * tabWidth
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(50) },
                        effects = {
                            lens(10f.dp.toPx(), 14f.dp.toPx(), chromaticAberration = true)
                        },
                        highlight = { Highlight.Default },
                        shadow = { Shadow(alpha = 0.9f) },
                        innerShadow = { InnerShadow(radius = 6f.dp, alpha = 0.6f) },
                        layerBlock = {
                            val velocity = slide.velocity / 10f
                            scaleX = 1f / (1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f))
                            scaleY = 1f * (1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f))
                        },
                        onDrawSurface = {
                            drawRect(
                                if (isLightTheme) Color.Black.copy(0.06f)
                                else Color.White.copy(0.08f),
                            )
                        },
                    )
                    .height(56f.dp)
                    .fillMaxWidth(1f / tabsCount),
            )
        }
    }
}

// 点击整页区域：先轻按一下（胶囊瞬移到位），再换页
@Composable
fun RowScope.LiquidTabItem(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    selected: Boolean,
) {
    val contentColor = MiuixTheme.colorScheme.onSurface
    val accent = LocalLiquidAccent.current
    LiquidTab(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24f.dp),
            tint = if (selected) accent else contentColor,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = if (selected) accent else contentColor,
        )
    }
}
