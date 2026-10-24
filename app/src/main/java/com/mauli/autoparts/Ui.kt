package com.mauli.autoparts

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Calendar

// ================= Brand colours =================

val Navy = Color(0xFF1E3A8A)
val NavyDeep = Color(0xFF0F1E4D)
val NavySoft = Color(0xFFE8EDFB)
val Orange = Color(0xFFF97316)
val OrangeSoft = Color(0xFFFFEDD5)
val Green = Color(0xFF16A34A)
val Red = Color(0xFFDC2626)
val Amber = Color(0xFFD97706)
val Purple = Color(0xFF7C3AED)
val Teal = Color(0xFF0D9488)
val Pink = Color(0xFFDB2777)
val Sky = Color(0xFF0284C7)
val Ink = Color(0xFF0F172A)
val Muted = Color(0xFF64748B)
val Faint = Color(0xFF94A3B8)
val Line = Color(0xFFE2E8F0)
val Bg = Color(0xFFF3F5FA)

val HeaderBrush = Brush.linearGradient(listOf(Color(0xFF2B4FC4), Navy, NavyDeep))
val OrangeBrush = Brush.linearGradient(listOf(Color(0xFFFB923C), Orange, Color(0xFFEA580C)))

private val TagColors = listOf(Navy, Orange, Green, Purple, Teal, Pink, Sky, Amber)

// ================= Small helpers used by many screens =================

/** Same text always gets the same colour (used for categories). */
fun tagColor(text: String): Color = TagColors[text.trim().lowercase().hashCode().mod(TagColors.size)]

/** Green = OK, Amber = running low, Red = finished. */
fun stockColor(p: Part): Color = when {
    p.qty <= 0 -> Red
    p.qty <= p.minQty -> Amber
    else -> Green
}

/** Balance still to be received on a bill. */
val Bill.due: Double get() = if (isEstimate) 0.0 else (total - paid).coerceAtLeast(0.0)

/** Start of today (or N days ago) in milliseconds. */
fun dayStart(daysAgo: Int = 0): Long = Calendar.getInstance().run {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, -daysAgo)
    timeInMillis
}

/** Start of this month (or N months ago) in milliseconds. */
fun monthStart(monthsAgo: Int = 0): Long = Calendar.getInstance().run {
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    add(Calendar.MONTH, -monthsAgo)
    timeInMillis
}

// ================= Theme =================

private val AppColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = NavySoft,
    onPrimaryContainer = Navy,
    secondary = Orange,
    onSecondary = Color.White,
    secondaryContainer = OrangeSoft,
    onSecondaryContainer = Color(0xFF9A3412),
    tertiary = Green,
    onTertiary = Color.White,
    background = Bg,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Muted,
    surfaceTint = Color.White,
    outline = Color(0xFFCBD5E1),
    outlineVariant = Line,
    error = Red,
    onError = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF1F5F9)
)

private val AppType = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MauliTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = AppType, shapes = AppShapes, content = content)
}

// ================= Header =================

/** Blue gradient header used at the top of every screen. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))
            .background(HeaderBrush)
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.06f), radius = size.height * 0.85f, center = Offset(size.width * 0.95f, 0f))
                drawCircle(Orange.copy(alpha = 0.16f), radius = size.height * 0.5f, center = Offset(size.width * 1.02f, size.height))
            }
            .statusBarsPadding()
            .padding(top = 4.dp, bottom = 16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .padding(start = if (onBack != null) 4.dp else 18.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            actions()
        }
        if (bottom != null) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), content = bottom)
        }
    }
}

/** Round white icon button for the header (with optional count badge). */
@Composable
fun HeaderAction(icon: ImageVector, desc: String, badge: Int = 0, onClick: () -> Unit) {
    Box(Modifier.padding(start = 8.dp)) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, desc, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Orange),
                contentAlignment = Alignment.Center
            ) {
                Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ================= Cards & labels =================

/** White rounded card with a soft shadow. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = Color.White,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .shadow(3.dp, shape, clip = false, ambientColor = Navy.copy(alpha = 0.10f), spotColor = Navy.copy(alpha = 0.14f))
            .clip(shape)
            .background(color)
            .border(1.dp, Line.copy(alpha = 0.8f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content
    )
}

/** Small dashboard box: icon, big number, label. */
@Composable
fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    AppCard(modifier, onClick = onClick) {
        IconBadge(icon, tint)
        Spacer(Modifier.height(10.dp))
        Text(value, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(label, color = Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Icon inside a soft coloured rounded square. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 40.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** Small coloured label like "Low stock" or "Paid". */
@Composable
fun Pill(text: String, color: Color, filled: Boolean = false) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        color = if (filled) Color.White else color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1
    )
}

/** Round initials badge for customers. */
@Composable
fun Avatar(name: String, color: Color = Navy, size: Dp = 42.dp) {
    val initials = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, color = color, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
    }
}

/** "Label ........ Value" row, used for bill totals. */
@Composable
fun InfoRow(label: String, value: String, bold: Boolean = false, color: Color = Ink, size: Int = 14) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f),
            color = if (bold) Ink else Muted,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
        Text(value, color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold)
    }
}

/** Section heading with an optional orange action on the right. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (action != null && onAction != null) {
            Text(
                action,
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Orange,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Friendly message when a list is empty. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(NavySoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Navy, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            MainButton(action, icon = Icons.Rounded.Add, color = Orange, onClick = onAction)
        }
    }
}

// ================= Inputs =================

/** Rounded search bar with a clear (X) button. */
@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = Faint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Navy) },
        trailingIcon = if (value.isNotEmpty()) {
            { IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear", tint = Muted) } }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedBorderColor = Orange,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = Navy,
            focusedTextColor = Ink,
            unfocusedTextColor = Ink
        )
    )
}

/** Standard text box used in all forms. */
@Composable
fun AppField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    type: KeyboardType = KeyboardType.Text,
    caps: KeyboardCapitalization = KeyboardCapitalization.Words,
    icon: ImageVector? = null,
    prefix: String? = null,
    suffix: String? = null,
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Next
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (icon != null) { { Icon(icon, null, tint = Muted) } } else null,
        prefix = if (prefix != null) { { Text(prefix, color = Muted) } } else null,
        suffix = if (suffix != null) { { Text(suffix, color = Muted) } } else null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            capitalization = if (type == KeyboardType.Text) caps else KeyboardCapitalization.None,
            keyboardType = type,
            imeAction = if (singleLine) imeAction else ImeAction.Default
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedBorderColor = Navy,
            unfocusedBorderColor = Line,
            focusedLabelColor = Navy,
            unfocusedLabelColor = Muted,
            cursorColor = Navy,
            focusedTextColor = Ink,
            unfocusedTextColor = Ink
        )
    )
}

/** Horizontally scrolling filter chips (All, Low stock, Udhaar...). */
@Composable
fun ChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
    sidePadding: Dp = 0.dp,
    label: (String) -> String = { it }
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = sidePadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val on = option == selected
            val bg = when {
                on && onDark -> Color.White
                on -> Navy
                onDark -> Color.White.copy(alpha = 0.14f)
                else -> Color.White
            }
            val fg = when {
                on && onDark -> Navy
                on -> Color.White
                onDark -> Color.White
                else -> Ink
            }
            Text(
                label(option),
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .border(1.dp, if (on || onDark) Color.Transparent else Line, RoundedCornerShape(50))
                    .clickable { onSelect(option) }
                    .padding(horizontal = 15.dp, vertical = 8.dp),
                color = fg,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/** iPhone-style segmented switch (Cash | UPI | Card | Udhaar). */
@Composable
fun Segmented(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Navy
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFE9EEF6))
            .padding(4.dp)
    ) {
        options.forEach { option ->
            val on = option == selected
            val bg by animateColorAsState(if (on) Color.White else Color.White.copy(alpha = 0f), label = "segment")
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(bg)
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    color = if (on) accent else Muted,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/** − qty + control. Tap the number to type a quantity. */
@Composable
fun QtyStepper(qty: Int, onMinus: () -> Unit, onPlus: () -> Unit, onTap: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NavySoft),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepButton(Icons.Rounded.Remove, onMinus)
        Text(
            "$qty",
            Modifier
                .widthIn(min = 28.dp)
                .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
                .padding(horizontal = 2.dp, vertical = 6.dp),
            color = Navy,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        StepButton(Icons.Rounded.Add, onPlus)
    }
}

@Composable
private fun StepButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Navy, modifier = Modifier.size(20.dp))
    }
}

/** Setting row with an ON/OFF switch. */
@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.5.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Navy,
                uncheckedThumbColor = Faint,
                uncheckedTrackColor = Color(0xFFEFF3F8),
                uncheckedBorderColor = Faint
            )
        )
    }
}

// ================= Buttons & menus =================

/** Big solid button. */
@Composable
fun MainButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Navy,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Light outlined button. */
@Composable
fun LightButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Navy,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = color.copy(alpha = 0.06f), contentColor = color),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Square icon + label button (Print, PDF, WhatsApp...). */
@Composable
fun ActionTile(icon: ImageVector, label: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Menu row with icon, title, subtitle and arrow (used in Settings). */
@Composable
fun MenuRow(icon: ImageVector, tint: Color, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon, tint, 42.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = Muted, fontSize = 12.5.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Faint)
    }
}

// ================= Dialogs =================

/** "Are you sure?" box. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String = "Yes",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = Ink, fontWeight = FontWeight.Bold) },
        text = { Text(text, color = Muted, fontSize = 15.sp) },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (danger) Red else Navy)
            ) { Text(confirm, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}

/** Box with one input (amount, quantity...). The keyboard opens automatically. */
@Composable
fun InputDialog(
    title: String,
    label: String,
    initial: String = "",
    type: KeyboardType = KeyboardType.Decimal,
    message: String? = null,
    confirm: String = "Save",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(200)
        try {
            focus.requestFocus()
        } catch (e: Exception) {
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (message != null) {
                    Text(message, color = Muted, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                }
                AppField(text, { text = it }, label, Modifier.focusRequester(focus), type = type, imeAction = ImeAction.Done)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()); onDismiss() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy)
            ) { Text(confirm, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
