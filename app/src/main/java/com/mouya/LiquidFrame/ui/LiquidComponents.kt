package com.mouya.LiquidFrame.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import java.util.Locale

object LiquidColors {
    val blue = Color(0xFF007AFF)
    val lightBg = Color(0xFFFFFFFF)
    val darkBg = Color(0xFF0B0B0D)
    val lightSurface = Color(0xFFFFFFFF)
    val darkSurface = Color(0xFF1C1C1E)
    val lightTextPrimary = Color(0xFF111113)
    val darkTextPrimary = Color(0xFFF5F5F7)
    val lightTextSecondary = Color(0xFF6E6E73)
    val darkTextSecondary = Color(0xFF98989F)
    val lightSeparator = Color(0xFFE5E5EA)
    val darkSeparator = Color(0xFF2C2C2E)
    val success = Color(0xFF34C759)
    val warning = Color(0xFFFF9F0A)
    val error = Color(0xFFFF3B30)
}

object LiquidShapes {
    val capsule: Shape = Capsule()
    val card: Shape = ContinuousRoundedRectangle(22.dp)
    val compact: Shape = ContinuousRoundedRectangle(16.dp)
}

@Composable
fun isDark(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()

@Composable
fun bgPrimary(): Color = if (isDark()) LiquidColors.darkBg else LiquidColors.lightBg

@Composable
fun textPrimary(): Color = if (isDark()) LiquidColors.darkTextPrimary else LiquidColors.lightTextPrimary

@Composable
fun textSecondary(): Color = if (isDark()) LiquidColors.darkTextSecondary else LiquidColors.lightTextSecondary

@Composable
fun separatorColor(): Color = if (isDark()) LiquidColors.darkSeparator else LiquidColors.lightSeparator

@Composable
fun surfaceColor(): Color = if (isDark()) LiquidColors.darkSurface else LiquidColors.lightSurface

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassCard(shape = LiquidShapes.card, cornerRadius = 22.dp, glass = glassContainer())
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun SettingSection(
    title: String,
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    surfaceAlpha: Float? = null,
    softBlur: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    val visible = !collapsible || expanded

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val headerScale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "sectionHeaderScale",
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (visible) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "sectionChevron",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = headerScale
                    scaleY = headerScale
                }
                .then(
                    if (collapsible) Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { expanded = !expanded } else Modifier
                )
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title.uppercase(Locale.ROOT),
                color = textSecondary(),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                modifier = Modifier.weight(1f),
            )
            if (collapsible) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = textSecondary(),
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                )
            }
        }
        AnimatedVisibility(
            visible = visible,

            enter = expandVertically(expandFrom = Alignment.Top) +
                    fadeIn(animationSpec = tween(durationMillis = 170, delayMillis = 55)),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) +
                    fadeOut(animationSpec = tween(durationMillis = 110)),
        ) {
            val shape = LiquidShapes.compact
            val surface = glassContainer(surfaceAlpha)
            val cardSurface = if (softBlur) {
                Modifier
                    .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
                    .liquidBackdropBlur(shape = shape, blurRadius = 22.dp, surfaceColor = surface)
                    .border(0.5.dp, separatorColor().copy(alpha = 0.55f), shape)
            } else {
                Modifier.liquidGlassCard(shape = shape, cornerRadius = 16.dp, glass = surface)
            }
            Column(
                modifier = Modifier.fillMaxWidth().then(cardSurface),
                content = content,
            )
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && onClick != null) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 1100f),
        label = "settingRowPress",
    )
    val mod = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        }
        .then(
            if (onClick != null) Modifier.clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            ) else Modifier,
        )
        .padding(horizontal = 16.dp, vertical = 13.dp)

    Row(
        modifier = mod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                title,
                color = if (enabled) textPrimary() else textSecondary(),
                fontSize = 16.sp,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    color = textSecondary(),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        trailing()
    }
}

@Composable
fun SettingDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color = separatorColor(),
    )
}

@Composable
fun LiquidPageTransition(
    page: LiquidPage,
    modifier: Modifier = Modifier,
    content: @Composable (LiquidPage) -> Unit,
) {
    AnimatedContent(
        targetState = page,
        modifier = modifier,
        transitionSpec = {
            val forward = targetState.ordinal >= initialState.ordinal
            val shift = 18
            (slideInHorizontally(
                animationSpec = spring(dampingRatio = 0.88f, stiffness = 620f),
                initialOffsetX = { if (forward) shift else -shift },
            ) + fadeIn(animationSpec = tween(durationMillis = 180))) togetherWith
                (slideOutHorizontally(
                    animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
                    targetOffsetX = { if (forward) -shift else shift },
                ) + fadeOut(animationSpec = tween(durationMillis = 110)))
        },
        label = "liquidPage",
    ) { current ->

        content(current)
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = 260),
        label = "statusPillColor",
    )
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(animatedColor.copy(alpha = 0.14f))
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {

        Crossfade(
            targetState = text,
            animationSpec = tween(durationMillis = 200),
            label = "statusPillText",
        ) { label: String ->
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = animatedColor)
        }
    }
}

@Composable
fun ValuePill(
    text: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val caretRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 550f),
        label = "valuePillCaret",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (isDark()) Color(0xFF2A2A2E) else Color(0xFFF2F2F7))
            .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = LiquidColors.blue,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.width(3.dp))
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起菜单" else "展开菜单",
            tint = if (expanded) LiquidColors.blue else textSecondary(),
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = caretRotation },
        )
    }
}

@Composable
fun LiquidMenuPanel(
    options: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .shadow(20.dp, ContinuousRoundedRectangle(18.dp), ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.22f))
            .liquidBackdropBlur(
                shape = ContinuousRoundedRectangle(18.dp),
                blurRadius = 24.dp,
                surfaceColor = surfaceColor().copy(alpha = if (isDark()) 0.84f else 0.78f),
            )
            .clip(ContinuousRoundedRectangle(18.dp))
            .border(0.5.dp, separatorColor().copy(alpha = 0.6f), ContinuousRoundedRectangle(18.dp))
            .widthIn(min = 190.dp)
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 5.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val rowInteraction = remember { MutableInteractionSource() }
            val rowPressed by rowInteraction.collectIsPressedAsState()
            val rowScale by animateFloatAsState(
                targetValue = if (rowPressed) 0.97f else 1f,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 1200f),
                label = "menuItemPress",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedRectangle(12.dp))
                    .background(
                        if (active) LiquidColors.blue.copy(alpha = if (isDark()) 0.18f else 0.10f)
                        else Color.Transparent,
                    )
                    .graphicsLayer {
                        scaleX = rowScale
                        scaleY = rowScale
                    }
                    .clickable(
                        indication = null,
                        interactionSource = rowInteraction,
                    ) {
                        onDismiss()
                        onSelected(value)
                    }
                    .padding(start = 11.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = if (active) "已选中" else null,
                    tint = if (active) LiquidColors.blue else Color.Transparent,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    fontSize = 13.sp,
                    color = if (active) textPrimary() else textSecondary(),
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SettingDropdownRow(
    title: String,
    subtitle: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember(title) { mutableStateOf(false) }
    Box {
        SettingRow(
            title = title,
            subtitle = subtitle,
            trailing = { ValuePill(selected, expanded) },
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {

                val entrance = remember {
                    MutableTransitionState(false).apply { targetState = true }
                }
                AnimatedVisibility(
                    visibleState = entrance,
                    enter = fadeIn(tween(110)) + scaleIn(
                        initialScale = 0.9f,
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = 620f),
                    ),
                    exit = fadeOut(tween(80)),
                ) {
                    LiquidMenuPanel(
                        options = options,
                        selected = selected,
                        onSelected = onSelected,
                        onDismiss = { expanded = false },
                        modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                    )
                }
            }
        }
    }
}
