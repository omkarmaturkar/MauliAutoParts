package com.mauli.autoparts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Motorcycle
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Marks a screen that opens on top of the main tabs: no bottom menu, back arrow in the header. */
interface LeafScreen

/** The Menu tab: every section of the app on one page. */
data object MenuPage : Screen()

private class MenuEntry(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val subtitle: String,
    val badge: String? = null,
    val badgeColor: Color = Orange,
    val onClick: () -> Unit
)

private class TileSpec(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
    val badge: Int = 0,
    val badgeColor: Color = Orange,
    val onClick: () -> Unit
)

// ================= Menu tab =================

@Composable
fun MenuScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val parts by vm.parts.collectAsState()
    val bills by vm.bills.collectAsState()
    val purchases by vm.purchases.collectAsState()
    val low = parts.count { it.qty <= it.minQty }
    val udhaar = bills.sumOf { it.due }
    val payable = purchases.sumOf { it.due }

    val work = listOf(
        MenuEntry(Icons.Rounded.PointOfSale, Orange, "New bill", "Start billing a customer") { startNewBill(vm) },
        MenuEntry(Icons.Rounded.Receipt, Navy, "All bills", "${bills.size} saved bills and estimates") {
            vm.billFilter = "All"
            vm.tab(Screen.Bills)
        },
        MenuEntry(
            Icons.Rounded.AccountBalanceWallet, Red, "Udhaar", "Money to collect from customers",
            badge = if (udhaar > 0.009) rs(udhaar) else null, badgeColor = Red
        ) {
            vm.billFilter = "Udhaar"
            vm.tab(Screen.Bills)
        },
        MenuEntry(Icons.Rounded.Motorcycle, Purple, "Vehicle history", "Every visit of every vehicle") {
            vm.open(VehiclesPage)
        }
    )

    val stock = listOf(
        MenuEntry(Icons.Rounded.Inventory2, Navy, "Stock", "${parts.size} parts in your shop") {
            vm.stockFilter = "All"
            vm.tab(Screen.Stock)
        },
        MenuEntry(
            Icons.Rounded.Warning, Amber, "Low stock parts",
            if (low == 0) "Nothing is running low" else "Running low or finished",
            badge = if (low > 0) "$low" else null, badgeColor = Amber
        ) {
            vm.stockFilter = "Low"
            vm.tab(Screen.Stock)
        },
        MenuEntry(Icons.Rounded.AddBox, Green, "Add new part", "Save a new part in stock") {
            vm.open(Screen.EditPart(0))
        },
        MenuEntry(
            Icons.Rounded.LocalShipping, Teal, "Purchases", "Stock in from your vendors",
            badge = if (payable > 0.009) "Pay " + rs(payable) else null, badgeColor = Red
        ) { vm.open(Screen.Purchases) },
        MenuEntry(Icons.Rounded.Share, Purple, "Send order list", "Share the low stock list on WhatsApp") {
            if (low == 0) vm.toast("No parts are running low 👍") else Printer.shareText(context, vm.orderListText())
        }
    )

    val shop = listOf(
        MenuEntry(Icons.Rounded.Insights, Green, "Reports", "Sales, profit, top parts and stock value") {
            vm.tab(Screen.Reports)
        },
        MenuEntry(Icons.Rounded.Settings, Muted, "Settings", "Shop details, printer and backup") {
            vm.open(Screen.Settings)
        }
    )

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Menu",
            subtitle = vm.settings.shopName.ifBlank { "Mauli Auto Parts" } + "  •  everything in one place"
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 28.dp)
        ) {
            MenuGroup("Daily work", work)
            MenuGroup("Stock & purchases", stock)
            MenuGroup("Reports & settings", shop)
        }
    }
}

@Composable
private fun MenuGroup(title: String, entries: List<MenuEntry>) {
    SectionTitle(title)
    AppCard(Modifier.fillMaxWidth(), padding = 6.dp) {
        entries.forEachIndexed { i, e ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = Line)
            MenuItemRow(e)
        }
    }
}

@Composable
private fun MenuItemRow(e: MenuEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = e.onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(e.icon, e.tint, 42.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(e.subtitle, color = Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        e.badge?.let {
            Pill(it, e.badgeColor)
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Faint)
    }
}

// ================= Home shortcuts =================

/** Big shortcut buttons for the Home screen (the same sections as the Menu tab). */
@Composable
fun QuickMenu(vm: AppViewModel, lowCount: Int, modifier: Modifier = Modifier) {
    val tiles = listOf(
        TileSpec(Icons.Rounded.Inventory2, "Stock", Navy) {
            vm.stockFilter = "All"
            vm.tab(Screen.Stock)
        },
        TileSpec(Icons.Rounded.LocalShipping, "Purchases", Teal) { vm.open(Screen.Purchases) },
        TileSpec(Icons.Rounded.Warning, "Low stock", Amber, badge = lowCount, badgeColor = Red) {
            vm.stockFilter = "Low"
            vm.tab(Screen.Stock)
        },
        TileSpec(Icons.Rounded.Motorcycle, "Vehicles", Purple) { vm.open(VehiclesPage) },
        TileSpec(Icons.Rounded.Receipt, "Bills", Sky) {
            vm.billFilter = "All"
            vm.tab(Screen.Bills)
        },
        TileSpec(Icons.Rounded.Insights, "Reports", Green) { vm.tab(Screen.Reports) },
        TileSpec(Icons.Rounded.Settings, "Settings", Muted) { vm.open(Screen.Settings) },
        TileSpec(Icons.Rounded.Menu, "Menu", Orange) { vm.tab(MenuPage) }
    )
    Column(modifier) {
        SectionTitle("Menu", action = "Open full menu") { vm.tab(MenuPage) }
        AppCard(Modifier.fillMaxWidth(), padding = 8.dp) {
            tiles.chunked(4).forEachIndexed { r, row ->
                if (r > 0) Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { t -> MenuTile(t, Modifier.weight(1f)) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MenuTile(t: TileSpec, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = t.onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(t.tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(t.icon, t.label, tint = t.tint, modifier = Modifier.size(26.dp))
            }
            if (t.badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(t.badgeColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (t.badge > 99) "99+" else "${t.badge}",
                        color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            t.label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}
