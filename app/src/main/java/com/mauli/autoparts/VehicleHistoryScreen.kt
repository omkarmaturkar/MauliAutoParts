package com.mauli.autoparts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Motorcycle
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The Vehicle History list: every vehicle that was billed. */
data object VehiclesPage : Screen(), LeafScreen

/** One vehicle with all its visits. [key] is the vehicle number without spaces, like MH35AB1234. */
data class VehiclePage(val key: String) : Screen(), LeafScreen

/** One vehicle with all its saved bills (newest first). Estimates are not counted as visits. */
private class VehicleRow(val key: String, val visits: List<Bill>) {
    private fun firstText(pick: (Bill) -> String): String = visits.map(pick).firstOrNull { it.isNotBlank() }.orEmpty()

    val customer: String = firstText { it.customer }
    val phone: String = firstText { it.phone }
    val model: String = firstText { it.model }
    val km: String = firstText { it.km }
    val last: Bill = visits.first()
    val total: Double = visits.sumOf { it.total }
    val due: Double = visits.sumOf { it.due }
}

private fun vehicleRows(bills: List<Bill>): List<VehicleRow> =
    bills.filter { !it.isEstimate && it.vehicleNo.isNotBlank() }
        .groupBy { normVehicle(it.vehicleNo) }
        .map { (key, list) -> VehicleRow(key, list.sortedByDescending { it.date }) }
        .sortedByDescending { it.last.date }

private fun filterVehicles(rows: List<VehicleRow>, query: String): List<VehicleRow> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return rows
    return rows.filter { v ->
        val hay = "${v.key} ${vehicleLabel(v.key)} ${v.customer} ${v.phone} ${v.model}".lowercase()
        words.all { it in hay }
    }
}

/** Opens Billing with this vehicle's number, customer, mobile and model already filled in. */
private fun startBillFor(vm: AppViewModel, v: VehicleRow) {
    val blank = vm.drafts.indexOfFirst {
        it.editId == 0L && it.lines.isEmpty() && it.customer.isBlank() && it.vehicleNo.isBlank() && it.phone.isBlank()
    }
    if (blank >= 0) vm.current = blank else vm.newDraft()
    vm.editDraft { it.copy(vehicleNo = v.key, customer = v.customer, phone = v.phone, model = v.model) }
    vm.tab(Screen.Billing)
}

private fun itemsText(items: List<BillItem>): String =
    items.joinToString(", ") { if (it.qty > 1) "${it.name} ×${it.qty}" else it.name }

private fun reminderText(shop: String, v: VehicleRow): String = buildString {
    append("Namaskar")
    if (v.customer.isNotBlank()) append(" ").append(v.customer)
    append(",\nThis is a reminder from *").append(shop).append("* for your ")
    if (v.model.isNotBlank()) append(v.model).append(" ")
    append(vehicleLabel(v.key)).append(".\n")
    append("Last visit: ").append(fmtDate(v.last.date))
    if (v.last.km.isNotBlank()) append(" at ").append(v.last.km).append(" km")
    append(".\nIt may be time for the next service. Please visit us.\nThank you 🙏")
}

// ================= Vehicle list =================

@Composable
fun VehicleListScreen(vm: AppViewModel) {
    val bills by vm.bills.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(bills) { vehicleRows(bills) }
    val shown = remember(rows, query) { filterVehicles(rows, query) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Vehicle History",
            subtitle = "${rows.size} vehicle${if (rows.size == 1) "" else "s"} billed",
            onBack = { vm.back() },
            bottom = { SearchBox(query, { query = it }, "Search vehicle no, name, mobile…") }
        )
        if (shown.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                EmptyState(
                    Icons.Rounded.Motorcycle,
                    if (rows.isEmpty()) "No vehicles yet" else "No vehicle found",
                    if (rows.isEmpty()) "Add the vehicle number when you make a bill. Its full history will show here."
                    else "Try another number, name or mobile."
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.key }) { v ->
                    VehicleCard(v) { vm.open(VehiclePage(v.key)) }
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(v: VehicleRow, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Motorcycle, if (v.due > 0.009) Red else Purple, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(vehicleLabel(v.key), color = Ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    listOf(v.customer, v.model).filter { it.isNotBlank() }.joinToString("  •  ").ifBlank { "Customer not saved" },
                    color = Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text("Last visit: " + whenText(v.last.date), color = Faint, fontSize = 11.5.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(rs(v.total), color = Ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                Text("${v.visits.size} visit${if (v.visits.size == 1) "" else "s"}", color = Muted, fontSize = 12.sp)
                if (v.due > 0.009) {
                    Spacer(Modifier.height(4.dp))
                    Pill("Due ${rs(v.due)}", Red)
                }
            }
        }
    }
}

// ================= One vehicle =================

@Composable
fun VehicleDetailScreen(vm: AppViewModel, key: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bills by vm.bills.collectAsState()
    val visits = remember(bills, key) {
        bills.filter { !it.isEstimate && normVehicle(it.vehicleNo) == key }.sortedByDescending { it.date }
    }
    val v = remember(visits) { if (visits.isEmpty()) null else VehicleRow(key, visits) }
    var itemsByBill by remember { mutableStateOf<Map<Long, List<BillItem>>>(emptyMap()) }

    LaunchedEffect(visits.map { it.id }) {
        val map = HashMap<Long, List<BillItem>>()
        visits.forEach { map[it.id] = vm.dao.items(it.id) }
        itemsByBill = map
    }

    if (v == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = vehicleLabel(key), subtitle = "No visits found", onBack = { vm.back() })
            EmptyState(Icons.Rounded.Motorcycle, "No visits found", "This vehicle has no saved bills.")
        }
        return
    }

    val shop = vm.settings.shopName.ifBlank { "Mauli Auto Parts" }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = vehicleLabel(key),
            subtitle = listOf(v.customer, v.model).filter { it.isNotBlank() }.joinToString("  •  ").ifBlank { "Vehicle history" },
            onBack = { vm.back() }
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "summary") {
                AppCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Motorcycle, Purple, 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                v.customer.ifBlank { "Customer not saved" }, color = Ink, fontSize = 16.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            val line = listOf(
                                v.model,
                                if (v.phone.isNotBlank()) "Mob ${v.phone}" else "",
                                if (v.km.isNotBlank()) "${v.km} km" else ""
                            ).filter { it.isNotBlank() }.joinToString("  •  ")
                            if (line.isNotBlank()) Text(line, color = Muted, fontSize = 12.5.sp, maxLines = 2)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Stat("Visits", "${v.visits.size}", Modifier.weight(1f))
                        Stat("Total billed", rs(v.total), Modifier.weight(1f))
                        Stat("Pending", rs(v.due), Modifier.weight(1f), if (v.due > 0.009) Red else Green)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MainButton("New bill", Modifier.weight(1f), icon = Icons.Rounded.PointOfSale, color = Orange) {
                            startBillFor(vm, v)
                        }
                        LightButton("Repeat last", Modifier.weight(1f), icon = Icons.Rounded.History, color = Navy) {
                            startBillFor(vm, v)
                            scope.launch { Catalog.addLines(vm, vm.dao.items(v.last.id), "Last visit") }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LightButton("Call", Modifier.weight(1f), icon = Icons.Rounded.Call, color = Green) {
                            if (v.phone.isBlank()) vm.toast("No mobile number saved") else Printer.dial(context, v.phone)
                        }
                        LightButton("WhatsApp", Modifier.weight(1f), icon = Icons.Rounded.Forum, color = Teal) {
                            Printer.whatsappText(context, v.phone, reminderText(shop, v))
                        }
                    }
                }
            }
            item(key = "title") { SectionTitle("Service history  •  ${v.visits.size}") }
            items(v.visits, key = { it.id }) { b ->
                VisitCard(b, itemsByBill[b.id]) { vm.open(Screen.BillView(b.id)) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color = Ink) {
    Column(modifier) {
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun VisitCard(b: Bill, items: List<BillItem>?, onClick: () -> Unit) {
    val due = b.due
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(whenText(b.date), color = Ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(billCode(b), if (b.km.isNotBlank()) "${b.km} km" else "").filter { it.isNotBlank() }.joinToString("  •  "),
                    color = Muted, fontSize = 12.5.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(rs(b.total), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (due > 0.009) Pill("Due ${rs(due)}", Red) else Pill(if (b.payMode == "Udhaar") "Paid" else b.payMode, Green)
            }
        }
        if (!items.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(itemsText(items), color = Muted, fontSize = 12.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
