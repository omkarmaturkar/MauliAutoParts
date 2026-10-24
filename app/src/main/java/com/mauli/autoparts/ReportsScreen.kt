package com.mauli.autoparts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material.icons.rounded.Payment
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val PeriodKeys = listOf("Today", "7 Days", "This Month", "Last Month", "This Year")
private val ChartDays = setOf("1", "5", "10", "15", "20", "25", "30")

private class Period(val title: String, val from: Long, val to: Long)
private class Bar(val label: String, val title: String, val amount: Double)

private fun yearStart(yearsAgo: Int = 0): Long = Calendar.getInstance().run {
    set(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    add(Calendar.YEAR, -yearsAgo)
    timeInMillis
}

private fun periodFor(key: String): Period {
    val month = SimpleDateFormat("MMMM yyyy", Locale.US)
    return when (key) {
        "Today" -> Period("Today, ${fmtDate(now())}", dayStart(), dayStart(-1))
        "7 Days" -> Period("Last 7 days", dayStart(6), dayStart(-1))
        "Last Month" -> Period(month.format(Date(monthStart(1))), monthStart(1), monthStart())
        "This Year" -> Period("Year ${Calendar.getInstance().get(Calendar.YEAR)}", yearStart(), yearStart(-1))
        else -> Period(month.format(Date(monthStart())), monthStart(), monthStart(-1))
    }
}

/** Bars for the chart: days (or months for "This Year"). "Today" shows the last 7 days for comparison. */
private fun chartBars(key: String, p: Period, sales: List<Bill>): List<Bar> {
    val bars = mutableListOf<Bar>()
    val cal = Calendar.getInstance()
    if (key == "This Year") {
        val short = SimpleDateFormat("MMM", Locale.US)
        val full = SimpleDateFormat("MMMM yyyy", Locale.US)
        cal.timeInMillis = p.from
        for (m in 0 until 12) {
            val from = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            val to = cal.timeInMillis
            bars.add(Bar(short.format(Date(from)), full.format(Date(from)), sales.filter { it.date >= from && it.date < to }.sumOf { it.total }))
        }
    } else {
        val weekly = key == "Today" || key == "7 Days"
        val short = SimpleDateFormat(if (weekly) "EEE" else "d", Locale.US)
        val full = SimpleDateFormat("EEE, dd MMM", Locale.US)
        cal.timeInMillis = if (key == "Today") dayStart(6) else p.from
        while (cal.timeInMillis < p.to) {
            val from = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val to = cal.timeInMillis
            bars.add(Bar(short.format(Date(from)), full.format(Date(from)), sales.filter { it.date >= from && it.date < to }.sumOf { it.total }))
        }
    }
    return bars
}

@Composable
fun ReportsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bills by vm.bills.collectAsState()
    val parts by vm.parts.collectAsState()
    var key by rememberSaveable { mutableStateOf("This Month") }
    var showProfit by rememberSaveable { mutableStateOf(true) }
    val p = remember(key, bills) { periodFor(key) }
    val allSales = remember(bills) { bills.filter { !it.isEstimate } }
    val sales = remember(allSales, p) { allSales.filter { it.date >= p.from && it.date < p.to } }
    val bars = remember(allSales, p) { chartBars(key, p, allSales) }
    val top by remember(p.from, p.to) { vm.dao.topSellers(p.from, p.to) }.collectAsState(initial = emptyList())

    val total = sales.sumOf { it.total }
    val profit = sales.sumOf { it.profit }
    val count = sales.size
    val avg = if (count > 0) total / count else 0.0
    val discount = sales.sumOf { it.discount }
    val due = sales.sumOf { it.due }
    val margin = if (total > 0) String.format(Locale.US, "%.1f", profit / total * 100) else "0"
    val modes = sales.groupBy { it.payMode }.map { (m, list) -> m to list.sumOf { it.total } }.sortedByDescending { it.second }

    fun reportText(): String = buildString {
        append("*").append(vm.settings.shopName).append(" – Sales report*\n")
        append(p.title).append("\n\n")
        append("Total sale: ").append(rs(total)).append(" (").append(count).append(" bills)\n")
        if (showProfit) append("Profit: ").append(rs(profit)).append(" (").append(margin).append("%)\n")
        append("Average bill: ").append(rs(avg)).append("\n")
        if (discount > 0) append("Discount given: ").append(rs(discount)).append("\n")
        append("Udhaar pending: ").append(rs(due)).append("\n")
        if (modes.isNotEmpty()) {
            append("\n")
            modes.forEach { (m, a) -> append(m).append(": ").append(rs(a)).append("\n") }
        }
        if (top.isNotEmpty()) {
            append("\nTop parts:\n")
            top.take(5).forEachIndexed { i, t ->
                append(i + 1).append(". ").append(t.name).append(" – ").append(t.qty).append(" sold\n")
            }
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val sb = StringBuilder("\uFEFF")
                    sb.append(
                        csvLine(listOf("Bill No", "Date", "Customer", "Mobile", "Vehicle", "Sub Total", "Discount", "Total", "Paid", "Due", "Payment", "Profit"))
                    ).append("\r\n")
                    sales.sortedBy { it.date }.forEach { b ->
                        sb.append(
                            csvLine(
                                listOf(
                                    billCode(b), fmtDateTime(b.date), b.customer, b.phone, vehicleLabel(b.vehicleNo),
                                    num(b.subTotal), num(b.discount), num(b.total), num(b.paid), num(b.due), b.payMode, num(b.profit)
                                )
                            )
                        ).append("\r\n")
                    }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(sb.toString().toByteArray()) }
                    }
                    vm.toast("Sales exported ✓ Open it in Excel")
                } catch (e: Exception) {
                    vm.toast("Export failed")
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Reports",
            subtitle = p.title,
            actions = {
                HeaderAction(if (showProfit) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, "Show or hide profit") {
                    showProfit = !showProfit
                }
                HeaderAction(Icons.Rounded.Share, "Share report") { Printer.shareText(context, reportText()) }
                HeaderAction(Icons.Rounded.FileDownload, "Export to Excel") {
                    exporter.launch("Mauli_Sales_" + SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date()) + ".csv")
                }
            },
            bottom = { ChipRow(PeriodKeys, key, { key = it }, onDark = true) }
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 36.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Total sale", rs(total), Icons.Rounded.Receipt, Navy, Modifier.weight(1f))
                StatTile(
                    if (showProfit) "Profit  •  $margin% margin" else "Profit",
                    if (showProfit) rs(profit) else "₹ ••••",
                    Icons.Rounded.MonetizationOn, Green, Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Bills  •  avg ${rs(avg)}", "$count", Icons.Rounded.Assessment, Purple, Modifier.weight(1f))
                StatTile("Udhaar pending", rs(due), Icons.Rounded.AccountBalanceWallet, Red, Modifier.weight(1f)) {
                    vm.billFilter = "Udhaar"
                    vm.tab(Screen.Bills)
                }
            }
            if (discount > 0) {
                Spacer(Modifier.height(8.dp))
                Text("Discount given: ${rs(discount)}", Modifier.padding(start = 4.dp), color = Muted, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(14.dp))
            SalesChart(bars)
            Spacer(Modifier.height(14.dp))
            ModesCard(modes, total)
            Spacer(Modifier.height(14.dp))
            TopCard(top)
            Spacer(Modifier.height(14.dp))
            StockCard(parts, showProfit)
        }
    }
}

@Composable
private fun ReportCard(title: String, icon: ImageVector, tint: Color, content: @Composable ColumnScope.() -> Unit) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint, 34.dp)
            Spacer(Modifier.width(10.dp))
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun MeterBar(frac: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(frac.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

/** Bar chart. Tap a bar to see its amount. */
@Composable
private fun SalesChart(bars: List<Bar>) {
    var selected by remember(bars) {
        mutableIntStateOf(bars.indexOfLast { it.amount > 0 }.takeIf { it >= 0 } ?: bars.lastIndex)
    }
    val max = bars.maxOfOrNull { it.amount }?.takeIf { it > 0 } ?: 1.0
    val many = bars.size > 14
    val sel = bars.getOrNull(selected)
    ReportCard("Sales chart", Icons.Rounded.BarChart, Navy) {
        if (sel != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(rs(sel.amount), color = Navy, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(8.dp))
                Text(sel.title, Modifier.padding(bottom = 3.dp), color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(if (many) 2.dp else 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            bars.forEachIndexed { i, bar ->
                val on = i == selected
                val frac = (bar.amount / max).toFloat().coerceIn(0.02f, 1f)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { selected = i },
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(frac * 0.82f)
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .background(if (on) Orange else Navy.copy(alpha = 0.22f))
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (!many || bar.label in ChartDays) bar.label else "",
                        Modifier.wrapContentWidth(unbounded = true),
                        color = if (on) Orange else Muted,
                        fontSize = 9.5.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Tap a bar to see that day's sale", color = Faint, fontSize = 11.5.sp)
    }
}

@Composable
private fun ModesCard(modes: List<Pair<String, Double>>, total: Double) {
    ReportCard("Payment modes", Icons.Rounded.Payment, Purple) {
        if (modes.isEmpty()) Text("No sales in this period", color = Muted, fontSize = 13.5.sp)
        modes.forEach { (mode, amount) ->
            val color = when (mode) {
                "Cash" -> Green
                "UPI" -> Navy
                "Card" -> Purple
                "Udhaar" -> Red
                else -> Teal
            }
            val frac = if (total > 0) (amount / total).toFloat() else 0f
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(mode, Modifier.weight(1f), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(rs(amount), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("  ${Math.round(frac * 100)}%", color = Muted, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(6.dp))
            MeterBar(frac, color)
        }
    }
}

@Composable
private fun TopCard(top: List<TopSeller>) {
    ReportCard("Top selling parts", Icons.Rounded.Star, Orange) {
        if (top.isEmpty()) {
            Text("No parts sold in this period", color = Muted, fontSize = 13.5.sp)
        } else {
            val max = top.maxOf { it.qty }.coerceAtLeast(1)
            top.forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (i < 3) Orange else NavySoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${i + 1}", color = if (i < 3) Color.White else Navy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        MeterBar(t.qty / max.toFloat(), if (i < 3) Orange else Navy)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${t.qty} sold", color = Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        Text(rs(t.amount), color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StockCard(parts: List<Part>, showProfit: Boolean) {
    val inStock = parts.filter { it.qty > 0 }
    val buyValue = inStock.sumOf { it.qty * it.buyPrice }
    val sellValue = inStock.sumOf { it.qty * it.sellPrice }
    val low = parts.count { it.qty in 1..it.minQty }
    val out = parts.count { it.qty <= 0 }
    val cats = inStock.groupBy { it.category.ifBlank { "General" } }
        .map { (cat, list) -> cat to list.sumOf { it.qty * it.buyPrice } }
        .sortedByDescending { it.second }
        .take(6)
    ReportCard("Stock summary", Icons.Rounded.Store, Teal) {
        InfoRow("Stock value (buy price)", rs(buyValue), bold = true)
        InfoRow("Stock value (sell price)", rs(sellValue))
        if (showProfit) InfoRow("Profit waiting in stock", rs(sellValue - buyValue), color = Green)
        InfoRow("Parts running low", "$low", color = Amber)
        InfoRow("Parts out of stock", "$out", color = Red)
        if (cats.isNotEmpty() && buyValue > 0) {
            Spacer(Modifier.height(12.dp))
            Text("Stock value by category", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            cats.forEach { (cat, value) ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cat, Modifier.weight(1f), color = Ink, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(rs(value), color = Ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                MeterBar((value / buyValue).toFloat(), tagColor(cat))
            }
        }
    }
}
