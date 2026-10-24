package com.mauli.autoparts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            MauliTheme {
                val vm: AppViewModel = viewModel()
                MauliApp(vm)
            }
        }
    }
}

/** Screens that open on top of the main tabs (no bottom menu). */
private fun Screen.isLeaf(): Boolean =
    this is Screen.EditPart || this is Screen.BillView || this == Screen.Settings

@Composable
fun MauliApp(vm: AppViewModel) {
    var splash by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (splash) {
            delay(1200)
            splash = false
        }
    }
    Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Ink) {
        Box(Modifier.fillMaxSize()) {
            AppBody(vm)
            AnimatedVisibility(visible = splash, enter = fadeIn(), exit = fadeOut(tween(450))) {
                Splash(vm.settings.shopName, vm.settings.tagline)
            }
        }
    }
}

@Composable
private fun AppBody(vm: AppViewModel) {
    val screen = vm.stack.lastOrNull() ?: Screen.Home
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val imeOpen by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val openBills by remember { derivedStateOf { vm.drafts.count { it.lines.isNotEmpty() } } }
    val holder = rememberSaveableStateHolder()
    val showBar = !screen.isLeaf() && !imeOpen

    BackHandler(enabled = vm.stack.size > 1 || screen != Screen.Home) { vm.back() }

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (showBar) Modifier else Modifier.navigationBarsPadding())
        ) {
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    if (targetState.isLeaf() && !initialState.isLeaf()) {
                        (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(280))) togetherWith fadeOut(tween(160))
                    } else {
                        fadeIn(tween(220, delayMillis = 40)) togetherWith fadeOut(tween(120))
                    }
                },
                label = "screens"
            ) { s ->
                when (s) {
                    Screen.Home -> holder.SaveableStateProvider("home") { HomeScreen(vm) }
                    Screen.Stock -> holder.SaveableStateProvider("stock") { StockScreen(vm) }
                    Screen.Billing -> holder.SaveableStateProvider("billing") { BillingScreen(vm) }
                    Screen.Bills -> holder.SaveableStateProvider("bills") { BillsScreen(vm) }
                    Screen.Reports -> holder.SaveableStateProvider("reports") { ReportsScreen(vm) }
                    Screen.Settings -> SettingsScreen(vm)
                    is Screen.EditPart -> EditPartScreen(vm, s.id)
                    is Screen.BillView -> BillViewScreen(vm, s.id)
                }
            }
        }
        if (showBar) BottomBar(screen, openBills) { vm.tab(it) }
    }
}

@Composable
private fun BottomBar(current: Screen, openBills: Int, onSelect: (Screen) -> Unit) {
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(20.dp, shape, clip = false, ambientColor = Navy.copy(alpha = 0.25f), spotColor = Navy.copy(alpha = 0.3f))
            .background(Color.White, shape)
            .navigationBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(70.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem(Icons.Rounded.Dashboard, "Home", current == Screen.Home) { onSelect(Screen.Home) }
            NavItem(Icons.Rounded.Inventory2, "Stock", current == Screen.Stock) { onSelect(Screen.Stock) }
            BillButton(current == Screen.Billing, openBills) { onSelect(Screen.Billing) }
            NavItem(Icons.Rounded.Receipt, "Bills", current == Screen.Bills) { onSelect(Screen.Bills) }
            NavItem(Icons.Rounded.Insights, "Reports", current == Screen.Reports) { onSelect(Screen.Reports) }
        }
    }
}

@Composable
private fun RowScope.NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (selected) Navy else Faint, label = "navTint")
    val pill by animateColorAsState(if (selected) NavySoft else NavySoft.copy(alpha = 0f), label = "navPill")
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(pill)
                .padding(horizontal = 16.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label, color = tint, fontSize = 11.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** Big raised orange Billing button in the middle of the bottom menu. */
@Composable
private fun RowScope.BillButton(selected: Boolean, count: Int, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
        label = "billScale"
    )
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.offset(y = (-22).dp).scale(scale)) {
            Box(
                Modifier
                    .size(62.dp)
                    .shadow(14.dp, CircleShape, ambientColor = Orange, spotColor = Orange)
                    .clip(CircleShape)
                    .background(OrangeBrush)
                    .border(4.dp, Color.White, CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PointOfSale, "Billing", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            if (count > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Navy)
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            "Billing",
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            color = if (selected) Orange else Faint,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** 1-second branded opening screen with the shop's name. */
@Composable
private fun Splash(shopName: String, tagline: String) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val logoScale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.5f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "logoScale"
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(700, delayMillis = 150),
        label = "textAlpha"
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(HeaderBrush)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .scale(logoScale)
                    .size(112.dp)
                    .shadow(20.dp, CircleShape, ambientColor = Orange, spotColor = Orange)
                    .clip(CircleShape)
                    .background(OrangeBrush)
                    .border(4.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Build, null, tint = Color.White, modifier = Modifier.size(54.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text(
                shopName.ifBlank { "Mauli Auto Parts" },
                color = Color.White.copy(alpha = textAlpha),
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold
            )
            if (tagline.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    tagline,
                    color = Color.White.copy(alpha = textAlpha * 0.75f),
                    fontSize = 14.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
        Text(
            "Billing  •  Stock  •  Udhaar",
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            color = Color.White.copy(alpha = textAlpha * 0.55f),
            fontSize = 12.sp,
            letterSpacing = 1.sp
        )
    }
}
