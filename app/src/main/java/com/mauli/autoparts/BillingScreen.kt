package com.mauli.autoparts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Motorcycle
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val PayModes = listOf("Cash", "UPI", "Card", "Udhaar")
private val LabourPresets = listOf("Labour", "Service", "Fitting", "Repair", "Washing")

/** Billing search: in-stock parts first, then names starting with the typed word. */
private fun findParts(parts: List<Part>, query: String): List<Part> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val first = words.first()
    return parts.filter { p ->
        val hay = "${p.name} ${p.partNo} ${p.brand} ${p.vehicle} ${p.category} ${p.rack}".lowercase()
        words.all { it in hay }
    }.sortedWith(
        compareBy<Part>(
            { if (it.qty > 0) 0 else 1 },
            { if (it.name.lowercase().startsWith(first) || it.partNo.lowercase() == first) 0 else 1 },
            { it.name.lowercase() }
        )
    ).take(60)
}

@Composable
fun BillingScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val parts by vm.parts.collectAsState()
    val bills by vm.bills.collectAsState()
    val frequentIds by vm.frequent.collectAsState()
    val s = vm.settings
    val d = vm.draft
    var query by rememberSaveable { mutableStateOf("") }
    var editLine by remember { mutableStateOf<BillItem?>(null) }
    var customName by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val imeOpen by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }

    val t = calcTotals(d.lines, d.discount.toNum())
    val count = d.lines.sumOf { it.qty }
    val autoPrint = s.autoPrint && s.printerAddress.isNotBlank()
    val partsById = remember(parts) { parts.associateBy { it.id } }
    val results = remember(parts, query) { findParts(parts, query) }
    val quick = remember(frequentIds, partsById) {
        frequentIds.mapNotNull { partsById[it] }.filter { it.qty > 0 }.take(12)
    }
    val inBill = d.lines.filter { it.partId != null }.groupBy { it.partId }.mapValues { e -> e.value.sumOf { it.qty } }
    val vKey = normVehicle(d.vehicleNo)
    val history = remember(bills, vKey, d.phone) {
        bills.filter {
            !it.isEstimate && ((vKey.length >= 6 && it.vehicleNo == vKey) || (d.phone.length == 10 && it.phone == d.phone))
        }
    }

    fun save(estimate: Boolean) {
        val cur = vm.draft
        if (cur.lines.isEmpty()) {
            vm.toast("Add at least one item")
            return
        }
        if (!estimate && cur.payMode == "Udhaar" && cur.customer.isBlank() && cur.phone.isBlank()) {
            vm.toast("Udhaar bill: please add customer name or mobile")
            return
        }
        vm.saveBill(estimate) { id ->
            query = ""
            if (!estimate && autoPrint) vm.printThermal(context, id)
            vm.open(Screen.BillView(id))
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Billing",
            subtitle = if (vm.drafts.size > 1) "${vm.drafts.size} bills open  •  tap to switch" else "Today  •  ${fmtDate(now())}",
            actions = {
                HeaderAction(Icons.Rounded.Delete, "Discard bill") {
                    if (d.lines.isNotEmpty() || d.customer.isNotBlank() || d.vehicleNo.isNotBlank()) askDiscard = true
                    else if (vm.drafts.size > 1) vm.discardDraft()
                    else vm.toast("This bill is already empty")
                }
            },
            bottom = {
                if (!imeOpen && query.isBlank()) {
                    BillTabs(vm) {
                        query = ""
                        vm.newDraft()
                    }
                    Spacer(Modifier.height(10.dp))
                }
                SearchBox(query, { query = it }, "Search part to add: name, part no, vehicle")
            }
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (query.isNotBlank()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (results.isEmpty()) {
                        item(key = "none") {
                            Text("No part found for \"${query.trim()}\"", Modifier.padding(8.dp), color = Muted, fontSize = 14.sp)
                        }
                    }
                    items(results, key = { it.id }) { p ->
                        ResultRow(p, inBill[p.id] ?: 0) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.addToBill(p, notify = true)
                            query = ""
                        }
                    }
                    item(key = "custom") {
                        CustomRow(query.trim()) { customName = query.trim().replaceFirstChar { it.uppercase() } }
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)
                ) {
                    if (quick.isNotEmpty()) {
                        QuickPicks(quick) { p ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.addToBill(p)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    ItemsCard(
                        d = d,
                        partsById = partsById,
                        onEdit = { editLine = it },
                        onMinus = { vm.changeQty(it, -1) },
                        onPlus = { vm.changeQty(it, 1) },
                        onLabour = { customName = "" }
                    )
                    Spacer(Modifier.height(12.dp))
                    CustomerCard(vm, d, history)
                    if (d.lines.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        PaymentCard(vm, d, t, s) {
                            if (s.upi.isBlank()) vm.toast("Add your UPI ID in Settings first") else showQr = true
                        }
                    }
                }
            }
        }
        if (!imeOpen) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (count == 0) "No items yet" else "$count item${if (count == 1) "" else "s"}  •  Total",
                        color = Muted, fontSize = 12.sp, maxLines = 1
                    )
                    Text(rs(t.total), color = Navy, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
                LightButton("Estimate", color = Purple) { save(true) }
                Spacer(Modifier.width(8.dp))
                MainButton(if (autoPrint) "Save & Print" else "Save Bill", color = Orange) { save(false) }
            }
        }
    }

    editLine?.let { line ->
        EditLineDialog(
            line = line,
            stock = line.partId?.let { partsById[it]?.qty },
            onSave = { q, price -> vm.updateLine(line, q, price) },
            onRemove = { vm.removeLine(line) },
            onDismiss = { editLine = null }
        )
    }
    customName?.let { name ->
        CustomItemDialog(
            initialName = name,
            onAdd = { n, price, q ->
                vm.addCustom(n, price, q)
                query = ""
            },
            onDismiss = { customName = null }
        )
    }
    if (showQr) UpiQrDialog(s, t.total, "Bill payment") { showQr = false }
    if (askDiscard) {
        ConfirmDialog(
            title = "Discard this bill?",
            text = "All items and customer details in this bill will be cleared. Stock is not changed.",
            confirm = "Discard",
            danger = true,
            onConfirm = { vm.discardDraft() },
            onDismiss = { askDiscard = false }
        )
    }
}

/** Open bills as tabs: tap to switch customer, + New bill for the next one. */
@Composable
private fun BillTabs(vm: AppViewModel, onNew: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        vm.drafts.forEachIndexed { i, d ->
            val on = i == vm.current
            val label = d.customer.ifBlank { vehicleLabel(d.vehicleNo) }.ifBlank { "Bill ${i + 1}" }
            Column(
                Modifier
                    .height(48.dp)
                    .widthIn(max = 160.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) Color.White else Color.White.copy(alpha = 0.14f))
                    .clickable { vm.current = i }
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    label, color = if (on) Navy else Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (d.lines.isEmpty()) "Empty" else rs(calcTotals(d.lines, d.discount.toNum()).total),
                    color = if (on) Orange else Color.White.copy(alpha = 0.7f),
                    fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
            }
        }
        Row(
            Modifier
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .clickable(onClick = onNew)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("New bill", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ResultRow(p: Part, inBill: Int, onClick: () -> Unit) {
    val out = p.qty <= 0
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(categoryIcon(p.category), tagColor(p.category), 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val info = listOf(p.partNo, p.brand, p.vehicle).filter { it.isNotBlank() }.joinToString("  •  ")
                if (info.isNotEmpty()) {
                    Text(info, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    (if (out) "Out of stock" else "${p.qty} ${p.unit} in stock") +
                        (if (p.rack.isNotBlank()) "  •  Rack ${p.rack}" else ""),
                    color = stockColor(p), fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(rs(p.sellPrice), color = Ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    if (inBill > 0) "In bill: $inBill" else "+ Add",
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (out) Faint else if (inBill > 0) Navy else Orange)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CustomRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, Teal.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(Icons.Rounded.Build, Teal, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Add \"$text\" as labour / other item", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("For service charges or parts not in stock", color = Muted, fontSize = 12.sp)
        }
    }
}

/** Most-sold parts as big buttons: one tap adds to the bill. */
@Composable
private fun QuickPicks(quick: List<Part>, onPick: (Part) -> Unit) {
    Text(
        "Quick add  •  most sold",
        Modifier.padding(start = 4.dp, bottom = 8.dp),
        color = Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        quick.forEach { p ->
            Column(
                Modifier
                    .width(124.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .border(1.dp, Line, RoundedCornerShape(14.dp))
                    .clickable { onPick(p) }
                    .padding(10.dp)
            ) {
                Text(
                    p.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rs(p.sellPrice), Modifier.weight(1f), color = Navy, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Rounded.AddCircle, null, tint = Orange, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun ItemsCard(
    d: Draft,
    partsById: Map<Long, Part>,
    onEdit: (BillItem) -> Unit,
    onMinus: (BillItem) -> Unit,
    onPlus: (BillItem) -> Unit,
    onLabour: () -> Unit
) {
    AppCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Receipt, Orange, 34.dp)
            Spacer(Modifier.width(10.dp))
            Text("Items", Modifier.weight(1f), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (d.lines.isNotEmpty()) Pill("${d.lines.size} item${if (d.lines.size == 1) "" else "s"}", Navy)
        }
        if (d.lines.isEmpty()) {
            Text(
                "Search a part above or tap a quick part. Stock reduces automatically when you save the bill.",
                Modifier.padding(vertical = 14.dp),
                color = Muted, fontSize = 13.5.sp
            )
        } else {
            Spacer(Modifier.height(4.dp))
            d.lines.forEachIndexed { i, line ->
                if (i > 0) HorizontalDivider(color = Line)
                LineRow(line, line.partId?.let { partsById[it] }, onEdit, onMinus, onPlus)
            }
            Spacer(Modifier.height(6.dp))
        }
        LightButton("Add labour / service charge", Modifier.fillMaxWidth(), icon = Icons.Rounded.Build, color = Teal, onClick = onLabour)
    }
}

@Composable
private fun LineRow(
    line: BillItem,
    part: Part?,
    onEdit: (BillItem) -> Unit,
    onMinus: (BillItem) -> Unit,
    onPlus: (BillItem) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onEdit(line) }
        ) {
            Text(line.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${rs(line.price)} each", color = Muted, fontSize = 12.5.sp)
                when {
                    line.partId == null -> {
                        Spacer(Modifier.width(6.dp))
                        Pill("Labour", Teal)
                    }
                    part != null && part.qty < line.qty -> {
                        Spacer(Modifier.width(6.dp))
                        Pill("Only ${part.qty} in stock", Red)
                    }
                    part != null && part.rack.isNotBlank() -> {
                        Text("  •  Rack ${part.rack}", color = Faint, fontSize = 12.sp)
                    }
                }
            }
            Text(rs(line.qty * line.price), color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        QtyStepper(line.qty, { onMinus(line) }, { onPlus(line) }, onTap = { onEdit(line) })
    }
}

@Composable
private fun CustomerCard(vm: AppViewModel, d: Draft, history: List<Bill>) {
    AppCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Motorcycle, Navy, 34.dp)
            Spacer(Modifier.width(10.dp))
            Text("Customer & vehicle", Modifier.weight(1f), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Optional", color = Faint, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppField(d.vehicleNo, { vm.onVehicle(it) }, "Vehicle no.", Modifier.weight(1f), caps = KeyboardCapitalization.Characters)
            AppField(d.phone, { vm.onPhone(it) }, "Mobile", Modifier.weight(1f), type = KeyboardType.Phone)
        }
        Spacer(Modifier.height(10.dp))
        AppField(d.customer, { v -> vm.editDraft { it.copy(customer = v) } }, "Customer name")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppField(d.model, { v -> vm.editDraft { it.copy(model = v) } }, "Model", Modifier.weight(1.4f))
            AppField(
                d.km, { v -> vm.editDraft { it.copy(km = v.filter { c -> c.isDigit() }.take(7)) } }, "KM reading",
                Modifier.weight(1f), type = KeyboardType.Number
            )
        }
        if (history.isNotEmpty()) {
            val last = history.first()
            val due = history.sumOf { it.due }
            val tint = if (due > 0.009) Red else Green
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.08f))
                    .clickable { vm.open(Screen.BillView(last.id)) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.History, null, tint = tint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Old customer  •  ${history.size} visit${if (history.size == 1) "" else "s"}",
                        color = Ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Last: ${fmtDate(last.date)}  •  ${rs(last.total)}" + (if (last.km.isNotBlank()) "  •  ${last.km} km" else ""),
                        color = Muted, fontSize = 12.sp
                    )
                }
                if (due > 0.009) Pill("Due ${rs(due)}", Red, filled = true)
            }
        }
    }
}

@Composable
private fun PaymentCard(vm: AppViewModel, d: Draft, t: Totals, s: ShopSettings, onQr: () -> Unit) {
    val received = d.received.toNum().coerceIn(0.0, t.total)
    val roundOff = t.total % 10
    AppCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.AccountBalanceWallet, Green, 34.dp)
            Spacer(Modifier.width(10.dp))
            Text("Payment", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Segmented(
            PayModes, d.payMode, { m -> vm.editDraft { it.copy(payMode = m) } },
            accent = if (d.payMode == "Udhaar") Red else Navy
        )
        if (d.payMode == "Udhaar") {
            Spacer(Modifier.height(10.dp))
            AppField(
                d.received, { v -> vm.editDraft { it.copy(received = v.filter { c -> c.isDigit() || c == '.' }.take(10)) } },
                "Received now (if any)", type = KeyboardType.Decimal, prefix = "₹ "
            )
            Spacer(Modifier.height(6.dp))
            Text("${rs(t.total - received)} will be added to udhaar", color = Red, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
        if (d.payMode == "UPI") {
            Spacer(Modifier.height(10.dp))
            LightButton("Show UPI QR to customer", Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCode, color = Navy, onClick = onQr)
        }
        Spacer(Modifier.height(12.dp))
        AppField(
            d.discount, { v -> vm.editDraft { it.copy(discount = v.filter { c -> c.isDigit() || c == '.' }.take(10)) } },
            "Discount", type = KeyboardType.Decimal, prefix = "₹ "
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (roundOff > 0.009) {
                QuickChip("Round off −${rs(roundOff)}") { vm.editDraft { it.copy(discount = num(t.discount + roundOff)) } }
            }
            QuickChip("5% off") { vm.editDraft { it.copy(discount = num(Math.round(t.sub * 0.05).toDouble())) } }
            QuickChip("10% off") { vm.editDraft { it.copy(discount = num(Math.round(t.sub * 0.10).toDouble())) } }
            if (t.discount > 0) QuickChip("No discount") { vm.editDraft { it.copy(discount = "") } }
        }
        Spacer(Modifier.height(12.dp))
        AppField(d.note, { v -> vm.editDraft { it.copy(note = v) } }, "Note on bill (optional)", caps = KeyboardCapitalization.Sentences)
        Spacer(Modifier.height(14.dp))
        InfoRow("Sub total", rs(t.sub))
        if (t.discount > 0) InfoRow("Discount", "- " + rs(t.discount), color = Green)
        if (s.showGst) {
            InfoRow("Taxable value", rs(t.taxable))
            InfoRow("GST (included)", rs(t.tax))
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Line)
        InfoRow("Total", rs(t.total), bold = true, color = Navy, size = 18)
        if (d.payMode == "Udhaar") {
            InfoRow("Received now", rs(received))
            InfoRow("Udhaar (due)", rs(t.total - received), bold = true, color = Red)
        }
    }
}

@Composable
private fun QuickChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(50))
            .background(NavySoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = Navy, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
    )
}

@Composable
private fun EditLineDialog(
    line: BillItem,
    stock: Int?,
    onSave: (Int, Double) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var qty by remember { mutableStateOf(line.qty.toString()) }
    var price by remember { mutableStateOf(num(line.price)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text(line.name, color = Ink, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (stock != null) Text("In stock: $stock", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppField(
                        qty, { qty = it.filter { c -> c.isDigit() }.take(5) }, "Quantity",
                        Modifier.weight(1f), type = KeyboardType.Number
                    )
                    AppField(
                        price, { price = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, "Price each",
                        Modifier.weight(1f), type = KeyboardType.Decimal, prefix = "₹ ", imeAction = ImeAction.Done
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("Amount: ${rs((qty.toIntOrNull() ?: 0) * price.toNum())}", color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(qty.toIntOrNull() ?: 0, price.toNum())
                    onDismiss()
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy)
            ) { Text("Update", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = {
                onRemove()
                onDismiss()
            }) { Text("Remove item", color = Red, fontWeight = FontWeight.SemiBold) }
        }
    )
}

@Composable
private fun CustomItemDialog(initialName: String, onAdd: (String, Double, Int) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName.ifBlank { "Labour" }) }
    var price by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("1") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(200)
        try {
            focus.requestFocus()
        } catch (e: Exception) {
        }
    }
    val amount = price.toNum()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Labour / other item", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LabourPresets.forEach { preset -> QuickChip(preset) { name = preset } }
                }
                Spacer(Modifier.height(12.dp))
                AppField(name, { name = it }, "Item name")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppField(
                        price, { price = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, "Amount",
                        Modifier.weight(1.4f).focusRequester(focus), type = KeyboardType.Decimal, prefix = "₹ "
                    )
                    AppField(
                        qty, { qty = it.filter { c -> c.isDigit() }.take(4) }, "Qty",
                        Modifier.weight(1f), type = KeyboardType.Number, imeAction = ImeAction.Done
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(name.trim().ifBlank { "Labour" }, amount, (qty.toIntOrNull() ?: 1).coerceAtLeast(1))
                    onDismiss()
                },
                enabled = amount > 0,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) { Text("Add to bill", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}

/** Big UPI QR with the exact amount. Customer scans it with GPay / PhonePe / Paytm. */
@Composable
fun UpiQrDialog(s: ShopSettings, amount: Double, note: String, onDismiss: () -> Unit) {
    val link = upiLink(s, amount, note)
    val qr = remember(link) { qrBitmap(link, 600).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text("Scan & Pay", Modifier.fillMaxWidth(), color = Ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(rs(amount), color = Navy, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(10.dp))
                Image(qr, "UPI QR code", Modifier.size(230.dp))
                Spacer(Modifier.height(10.dp))
                Text(s.upi, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("GPay  •  PhonePe  •  Paytm  •  BHIM", color = Faint, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}
