package com.mauli.autoparts

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ================= Shared helpers (also used by Billing & Bills screens) =================

/** Opens an empty bill. Re-uses a blank open bill if there is one. */
fun startNewBill(vm: AppViewModel) {
    val blank = vm.drafts.indexOfFirst {
        it.lines.isEmpty() && it.customer.isBlank() && it.vehicleNo.isBlank() && it.phone.isBlank()
    }
    if (blank >= 0) vm.current = blank else vm.newDraft()
    vm.tab(Screen.Billing)
}

/** Opens an empty estimate (budget). Stock does not change until it becomes a bill. */
fun startEstimate(vm: AppViewModel) {
    startNewBill(vm)
    vm.editDraft { it.copy(estimate = true) }
    vm.toast("Estimate • stock will not change")
}

/** "Today, 10:45 AM" / "Yesterday, 06:10 PM" / "21 Oct 2026, 11:00 AM" */
fun whenText(ms: Long): String = when {
    ms >= dayStart() -> "Today, " + fmtTime(ms)
    ms >= dayStart(1) -> "Yesterday, " + fmtTime(ms)
    else -> fmtDateTime(ms)
}

/** One bill in a list: customer, vehicle, amount and a Paid / Due / Estimate label. */
@Composable
fun BillRow(b: Bill, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val due = b.due
    val who = b.customer.ifBlank { "Walk-in customer" }
    val color = when {
        b.isEstimate -> Purple
        due > 0.009 -> Red
        else -> Navy
    }
    AppCard(modifier.fillMaxWidth(), onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(who, color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(who, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(vehicleLabel(b.vehicleNo), billCode(b)).filter { it.isNotBlank() }.joinToString("  •  "),
                    color = Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(whenText(b.date), color = Faint, fontSize = 11.5.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(rs(b.total), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                when {
                    b.isEstimate -> Pill("Estimate", Purple)
                    due > 0.009 -> Pill("Due ${rs(due)}", Red)
                    else -> Pill(if (b.payMode == "Udhaar") "Paid" else b.payMode, Green)
                }
            }
        }
    }
}

// ================= Home dashboard =================

private class HomeStats(
    val todaySale: Double,
    val todayBills: Int,
    val todayProfit: Double,
    val todayDue: Double,
    val week: List<Pair<String, Double>>,
    val stockValue: Double,
    val totalQty: Int,
    val low: List<Part>,
    val udhaar: Double,
    val udhaarBills: Int
)

private fun homeStats(parts: List<Part>, bills: List<Bill>): HomeStats {
    val real = bills.filter { !it.isEstimate }
    val todays = real.filter { it.date >= dayStart() }
    val dayName = SimpleDateFormat("EEE", Locale.US)
    val week = (6 downTo 0).map { d ->
        val from = dayStart(d)
        val to = dayStart(d - 1)
        dayName.format(Date(from)) to real.filter { it.date >= from && it.date < to }.sumOf { it.total }
    }
    return HomeStats(
        todaySale = todays.sumOf { it.total },
        todayBills = todays.size,
        todayProfit = todays.sumOf { it.profit },
        todayDue = todays.sumOf { it.due },
        week = week,
        stockValue = parts.sumOf { it.qty.coerceAtLeast(0) * it.buyPrice },
        totalQty = parts.sumOf { it.qty.coerceAtLeast(0) },
        low = parts.filter { it.qty <= it.minQty }.sortedBy { it.qty },
        udhaar = real.sumOf { it.due },
        udhaarBills = real.count { it.due > 0.009 }
    )
}

private class SetupStep(val title: String, val hint: String, val done: Boolean, val action: () -> Unit)

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val parts by vm.parts.collectAsState()
    val bills by vm.bills.collectAsState()
    val s = vm.settings
    val st = remember(parts, bills) { homeStats(parts, bills) }
    val open = vm.drafts.withIndex().filter { it.value.lines.isNotEmpty() }
    val prefs = remember { context.getSharedPreferences("mauli", Context.MODE_PRIVATE) }
    var hideSetup by remember { mutableStateOf(prefs.getBoolean("hideSetup", false)) }
    var showProfit by rememberSaveable { mutableStateOf(false) }
    var stockFor by remember { mutableStateOf<Part?>(null) }
    val listState = rememberLazyListState()
    val scrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 }
    }

    val steps = listOf(
        SetupStep("Add shop details", "Address, phone & UPI ID print on every bill", s.phone.isNotBlank() && s.address.isNotBlank()) {
            vm.open(Screen.Settings)
        },
        SetupStep("Add your stock", "One by one, or import from Excel", parts.isNotEmpty()) { vm.tab(Screen.Stock) },
        SetupStep("Connect bill printer", "Bluetooth thermal printer, 58mm or 80mm", s.printerAddress.isNotBlank()) {
            vm.open(Screen.Settings)
        },
        SetupStep("Make your first bill", "Stock reduces automatically", bills.any { !it.isEstimate }) { startNewBill(vm) }
    )
    val sendOrder: () -> Unit = {
        if (st.low.isEmpty()) vm.toast("No parts are running low 👍")
        else Printer.shareText(context, vm.orderListText())
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 36.dp)) {
            item(key = "header") {
                ScreenHeader(
                    title = s.shopName.ifBlank { "Mauli Auto Parts" },
                    subtitle = "${greeting()} 🙏  •  ${SimpleDateFormat("EEE, dd MMM", Locale.US).format(Date())}",
                    actions = { HeaderAction(Icons.Rounded.Settings, "Settings") { vm.open(Screen.Settings) } },
                    bottom = {
                        TodayCard(st, showProfit, onToggle = { showProfit = !showProfit }, onClick = { vm.tab(Screen.Reports) })
                    }
                )
            }
            item(key = "actions") {
                AppCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), padding = 6.dp) {
                    Row(Modifier.fillMaxWidth()) {
                        ActionTile(Icons.Rounded.PointOfSale, "New Bill", Orange, Modifier.weight(1f)) { startNewBill(vm) }
                        ActionTile(Icons.Rounded.Description, "Estimate", Purple, Modifier.weight(1f)) { startEstimate(vm) }
                        ActionTile(Icons.Rounded.LocalShipping, "Order List", Teal, Modifier.weight(1f), sendOrder)
                        ActionTile(Icons.Rounded.AccountBalanceWallet, "Udhaar", Red, Modifier.weight(1f)) {
                            vm.billFilter = "Udhaar"
                            vm.tab(Screen.Bills)
                        }
                    }
                }
            }
            item(key = "menu") {
                QuickMenu(vm, st.low.size, Modifier.padding(horizontal = 16.dp))
            }
            if (!hideSetup && steps.any { !it.done }) {
                item(key = "setup") {
                    SetupCard(steps) {
                        hideSetup = true
                        prefs.edit().putBoolean("hideSetup", true).apply()
                    }
                }
            }
            if (open.isNotEmpty()) {
                item(key = "openTitle") {
                    SectionTitle("Open bills (${open.size})", Modifier.padding(horizontal = 16.dp), "+ New bill") { startNewBill(vm) }
                }
                item(key = "openList") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(open, key = { it.value.id }) { (index, d) ->
                            OpenBillCard(index, d) {
                                vm.current = index
                                vm.tab(Screen.Billing)
                            }
                        }
                    }
                }
            }
            item(key = "glance") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionTitle("Shop at a glance")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile("Stock value", rs(st.stockValue), Icons.Rounded.Store, Navy, Modifier.weight(1f)) {
                            vm.stockFilter = "All"
                            vm.tab(Screen.Stock)
                        }
                        StatTile("Parts • ${st.totalQty} pcs", "${parts.size}", Icons.Rounded.Category, Teal, Modifier.weight(1f)) {
                            vm.stockFilter = "All"
                            vm.tab(Screen.Stock)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile("Low stock", "${st.low.size} parts", Icons.Rounded.Warning, Amber, Modifier.weight(1f)) {
                            vm.stockFilter = "Low"
                            vm.tab(Screen.Stock)
                        }
                        StatTile("Udhaar • ${st.udhaarBills} bills", rs(st.udhaar), Icons.Rounded.AccountBalanceWallet, Red, Modifier.weight(1f)) {
                            vm.billFilter = "Udhaar"
                            vm.tab(Screen.Bills)
                        }
                    }
                }
            }
            if (st.low.isNotEmpty()) {
                item(key = "lowTitle") {
                    SectionTitle("Running low", Modifier.padding(horizontal = 16.dp), "Send order", sendOrder)
                }
                item(key = "lowList") {
                    AppCard(Modifier.padding(horizontal = 16.dp), padding = 6.dp) {
                        st.low.take(5).forEachIndexed { i, p ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = Line)
                            LowStockRow(p, onOpen = { vm.open(Screen.EditPart(p.id)) }, onAdd = { stockFor = p })
                        }
                        if (st.low.size > 5) {
                            Text(
                                "See all ${st.low.size} low stock parts",
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        vm.stockFilter = "Low"
                                        vm.tab(Screen.Stock)
                                    }
                                    .padding(12.dp),
                                color = Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            item(key = "recentTitle") {
                SectionTitle("Recent bills", Modifier.padding(horizontal = 16.dp), "See all") {
                    vm.billFilter = "All"
                    vm.tab(Screen.Bills)
                }
            }
            if (bills.isEmpty()) {
                item(key = "noBills") {
                    EmptyState(Icons.Rounded.Receipt, "No bills yet", "Tap the orange Billing button below to make your first bill.")
                }
            } else {
                items(bills.take(6), key = { it.id }) { b ->
                    BillRow(b, Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) { vm.open(Screen.BillView(b.id)) }
                }
            }
        }
        if (scrolled) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(NavyDeep))
        }
    }
    stockFor?.let { p -> AddStockDialog(vm, p) { stockFor = null } }
}

@Composable
private fun TodayCard(st: HomeStats, showProfit: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "TODAY'S SALE",
                Modifier.weight(1f),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (showProfit) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    null, tint = Color.White, modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(if (showProfit) "Hide profit" else "Show profit", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(rs(st.todaySale), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Row {
            MiniStat("Bills today", "${st.todayBills}", Modifier.weight(1f))
            MiniStat("Udhaar today", rs(st.todayDue), Modifier.weight(1f))
            MiniStat("Profit today", if (showProfit) rs(st.todayProfit) else "₹ ••••", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        WeekBars(st.week)
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = Color.White.copy(alpha = 0.65f), fontSize = 11.5.sp, maxLines = 1)
    }
}

/** Last 7 days sale as small bars. Today is orange. */
@Composable
private fun WeekBars(week: List<Pair<String, Double>>) {
    val max = week.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        week.forEachIndexed { i, (day, amount) ->
            val isToday = i == week.lastIndex
            val frac = (amount / max).toFloat().coerceIn(0.05f, 1f)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .width(16.dp)
                        .height(54.dp * frac)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isToday) Orange else Color.White.copy(alpha = 0.35f))
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    if (isToday) "Today" else day,
                    color = Color.White.copy(alpha = if (isToday) 1f else 0.6f),
                    fontSize = 10.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun SetupCard(steps: List<SetupStep>, onHide: () -> Unit) {
    val done = steps.count { it.done }
    AppCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Get started", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("$done of ${steps.size} done", color = Muted, fontSize = 12.5.sp)
            }
            Text(
                "Hide",
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onHide)
                    .padding(8.dp),
                color = Faint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(50))
                .background(NavySoft)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(done / steps.size.toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(Green)
            )
        }
        Spacer(Modifier.height(6.dp))
        steps.forEach { step ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = step.action)
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (step.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    null,
                    tint = if (step.done) Green else Faint,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        step.title,
                        color = if (step.done) Muted else Ink,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (step.done) TextDecoration.LineThrough else null
                    )
                    Text(step.hint, color = Faint, fontSize = 12.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Faint)
            }
        }
    }
}

@Composable
private fun OpenBillCard(index: Int, d: Draft, onClick: () -> Unit) {
    val t = calcTotals(d.lines, d.discount.toNum())
    val who = d.customer.ifBlank { vehicleLabel(d.vehicleNo) }.ifBlank { "Walk-in customer" }
    AppCard(Modifier.width(230.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (d.estimate) Icons.Rounded.Description else Icons.Rounded.Receipt, if (d.estimate) Purple else Orange, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (d.estimate) "Estimate" else "Bill ${index + 1}",
                    color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
                Text(who, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${d.lines.sumOf { it.qty }} items", Modifier.weight(1f), color = Muted, fontSize = 13.sp)
            Text(rs(t.total), color = Navy, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (d.estimate) "Continue estimate →" else "Continue billing →",
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (d.estimate) Purple.copy(alpha = 0.10f) else OrangeSoft)
                .padding(vertical = 8.dp),
            color = if (d.estimate) Purple else Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LowStockRow(p: Part, onOpen: () -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(stockColor(p)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = Ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (p.qty <= 0) "Out of stock" else "Only ${p.qty} ${p.unit} left") +
                    (if (p.rack.isNotBlank()) "  •  Rack ${p.rack}" else ""),
                color = stockColor(p), fontSize = 12.5.sp
            )
        }
        Text(
            "+ Stock",
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(NavySoft)
                .clickable(onClick = onAdd)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            color = Navy, fontSize = 12.5.sp, fontWeight = FontWeight.Bold
        )
    }
}
