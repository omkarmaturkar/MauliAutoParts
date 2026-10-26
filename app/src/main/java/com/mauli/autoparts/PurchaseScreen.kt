package com.mauli.autoparts

import android.app.DatePickerDialog
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Payment
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

private val PurchaseFilters = listOf("All", "This Month", "Payable")
private val VendorPayModes = listOf("Cash", "UPI", "Bank", "Credit")

/** Row of tappable suggestions (vendors etc.). */
@Composable
fun PickChips(values: List<String>, onPick: (String) -> Unit) {
    if (values.isNotEmpty()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            values.forEach { v ->
                Text(
                    v,
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NavySoft)
                        .clickable { onPick(v) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Navy, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1
                )
            }
        }
    }
}

/** Vendors used before (purchases + part suppliers), most used first. */
private fun vendorList(purchases: List<Bill>, parts: List<Part>, typed: String): List<String> {
    val t = typed.trim().lowercase()
    val ranked = (purchases.map { it.customer } + parts.map { it.supplier })
        .map { it.trim() }.filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        .distinctBy { it.lowercase() }
    val exact = ranked.any { it.lowercase() == t }
    return ranked.filter { it.lowercase() != t && (t.isEmpty() || exact || it.lowercase().contains(t)) }.take(10)
}

private fun pickParts(parts: List<Part>, query: String): List<Part> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return parts.filter { p ->
        val hay = "${p.name} ${p.partNo} ${p.brand} ${p.vehicle} ${p.category}".lowercase()
        words.all { it in hay }
    }.take(8)
}

private fun filterPurchases(list: List<Bill>, query: String, filter: String): List<Bill> {
    val base = when (filter) {
        "This Month" -> list.filter { it.date >= monthStart() }
        "Payable" -> list.filter { it.due > 0.009 }
        else -> list
    }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return base
    return base.filter { b ->
        val hay = "${b.customer} ${b.phone} ${b.ref} ${billCode(b)} ${b.note}".lowercase()
        words.all { it in hay }
    }
}

// ================= Purchase list =================

@Composable
fun PurchasesScreen(vm: AppViewModel) {
    val purchases by vm.purchases.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    val shown = remember(purchases, query, filter) { filterPurchases(purchases, query, filter) }
    val month = purchases.filter { it.date >= monthStart() }.sumOf { it.total }
    val payable = purchases.sumOf { it.due }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Purchases",
            subtitle = "This month ${rs(month)}  •  Payable ${rs(payable)}",
            onBack = { vm.back() },
            actions = { HeaderAction(Icons.Rounded.Add, "New purchase") { vm.open(Screen.PurchaseEdit(0)) } },
            bottom = {
                SearchBox(query, { query = it }, "Search vendor, bill no…")
                Spacer(Modifier.height(10.dp))
                ChipRow(PurchaseFilters, filter, { filter = it }, onDark = true)
            }
        )
        if (shown.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                EmptyState(
                    Icons.Rounded.LocalShipping,
                    if (purchases.isEmpty()) "No purchases yet" else "Nothing found",
                    if (purchases.isEmpty()) "Record every purchase from your vendors. Stock goes up automatically and you will know where each part came from."
                    else "Try another vendor name or bill number.",
                    action = if (purchases.isEmpty()) "Record first purchase" else null
                ) { vm.open(Screen.PurchaseEdit(0)) }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.id }) { b ->
                    PurchaseRow(b) { vm.open(Screen.PurchaseEdit(b.id)) }
                }
            }
        }
    }
}

@Composable
private fun PurchaseRow(b: Bill, onClick: () -> Unit) {
    val due = b.due
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.LocalShipping, Teal, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.customer, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(billCode(b), if (b.ref.isNotBlank()) "Bill ${b.ref}" else "").filter { it.isNotBlank() }.joinToString("  •  "),
                    color = Muted, fontSize = 12.5.sp, maxLines = 1
                )
                Text(fmtDate(b.date), color = Faint, fontSize = 11.5.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(rs(b.total), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (due > 0.009) Pill("Payable ${rs(due)}", Red) else Pill("Paid", Green)
            }
        }
    }
}

// ================= Purchase entry =================

@Composable
private fun PCard(title: String, icon: ImageVector, tint: Color, content: @Composable ColumnScope.() -> Unit) {
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
fun PurchaseEditScreen(vm: AppViewModel, id: Long) {
    val context = LocalContext.current
    val parts by vm.parts.collectAsState()
    val purchases by vm.purchases.collectAsState()
    val isNew = id == 0L
    var loaded by remember { mutableStateOf(isNew) }
    var original by remember { mutableStateOf<Bill?>(null) }
    var vendor by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var date by remember { mutableLongStateOf(now()) }
    var lines by remember { mutableStateOf(listOf<BillItem>()) }
    var discount by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Cash") }
    var note by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var editLine by remember { mutableStateOf<BillItem?>(null) }
    var newPartName by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }

    fun addLine(p: Part, rate: Double = p.buyPrice) {
        val ex = lines.find { it.partId == p.id }
        lines = if (ex != null) {
            lines.map { if (it.id == ex.id) it.copy(qty = it.qty + 1) else it }
        } else {
            lines + BillItem(
                id = System.nanoTime(), partId = p.id, name = p.name, partNo = p.partNo,
                qty = 1, price = rate, buyPrice = rate, gst = p.gst
            )
        }
    }

    LaunchedEffect(id) {
        if (isNew) {
            if (pendingPurchasePartId != 0L) {
                vm.dao.part(pendingPurchasePartId)?.let { p ->
                    addLine(p)
                    if (p.supplier.isNotBlank()) vendor = p.supplier
                }
                pendingPurchasePartId = 0L
            }
        } else {
            val b = vm.dao.bill(id)
            if (b != null) {
                original = b
                vendor = b.customer
                phone = b.phone
                ref = b.ref
                date = b.date
                discount = if (b.discount > 0) num(b.discount) else ""
                mode = b.payMode.ifBlank { "Cash" }
                paid = if (mode != "Credit" && b.paid >= b.total - 0.009) "" else num(b.paid)
                note = b.note
                val base = System.nanoTime()
                lines = vm.dao.items(id).mapIndexed { i, l -> l.copy(id = base + i, billId = 0) }
            }
            loaded = true
        }
    }

    val sub = lines.sumOf { it.qty * it.price }
    val disc = discount.toNum().coerceIn(0.0, sub)
    val total = sub - disc
    val paidValue = if (paid.isBlank()) (if (mode == "Credit") 0.0 else total) else paid.toNum().coerceIn(0.0, total)
    val payable = (total - paidValue).coerceAtLeast(0.0)
    val partsById = remember(parts) { parts.associateBy { it.id } }
    val results = remember(parts, query) { pickParts(parts, query) }
    val vendors = remember(purchases, parts, vendor) { vendorList(purchases, parts, vendor) }

    fun save() {
        vm.savePurchase(
            if (isNew) 0L else id,
            PurchaseInput(
                vendor = vendor, phone = phone, ref = ref, date = date, lines = lines,
                discount = disc, paid = paidValue, payMode = mode, note = note
            )
        ) { vm.back() }
    }

    fun pickDate() {
        val cal = Calendar.getInstance().apply { timeInMillis = date }
        DatePickerDialog(
            context,
            { _, y, m, dd ->
                date = Calendar.getInstance().apply {
                    timeInMillis = date
                    set(y, m, dd)
                }.timeInMillis
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (isNew) "New Purchase" else "Purchase " + (original?.let { billCode(it) } ?: ""),
            subtitle = if (isNew) "Stock goes up automatically when you save" else "Changes correct the stock automatically",
            onBack = { vm.back() },
            actions = {
                if (!isNew) HeaderAction(Icons.Rounded.Delete, "Delete purchase") { askDelete = true }
            }
        )
        if (!loaded) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Navy)
            }
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                PCard("Vendor", Icons.Rounded.Store, Navy) {
                    AppField(vendor, { vendor = it }, "Vendor / supplier name *")
                    PickChips(vendors) { v ->
                        vendor = v
                        if (phone.isBlank()) {
                            purchases.firstOrNull { it.customer.equals(v, true) && it.phone.isNotBlank() }?.let { phone = it.phone }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppField(
                            phone, { phone = it.filter { c -> c.isDigit() }.take(10) }, "Mobile",
                            Modifier.weight(1f), type = KeyboardType.Phone
                        )
                        AppField(
                            ref, { ref = it }, "Vendor bill no.",
                            Modifier.weight(1f), caps = KeyboardCapitalization.Characters
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Line, RoundedCornerShape(12.dp))
                            .clickable { pickDate() }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Event, null, tint = Navy, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Date: ${fmtDate(date)}", Modifier.weight(1f), color = Ink, fontSize = 15.sp)
                        Text("Change", color = Orange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(14.dp))

                PCard("Parts purchased", Icons.Rounded.LocalShipping, Teal) {
                    SearchBox(query, { query = it }, "Search part to add…")
                    if (query.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        results.forEach { p ->
                            PickRow(p) {
                                addLine(p)
                                query = ""
                            }
                        }
                        NewPartRow(query.trim()) { newPartName = query.trim().replaceFirstChar { it.uppercase() } }
                    }
                    if (lines.isEmpty()) {
                        Text(
                            "Search and add the parts you bought. Stock will go up when you save.",
                            Modifier.padding(vertical = 12.dp),
                            color = Muted, fontSize = 13.sp
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        lines.forEachIndexed { i, l ->
                            if (i > 0) HorizontalDivider(color = Line)
                            PLineRow(
                                line = l,
                                part = l.partId?.let { partsById[it] },
                                onTap = { editLine = l },
                                onMinus = {
                                    lines = lines.mapNotNull {
                                        if (it.id == l.id) (if (it.qty > 1) it.copy(qty = it.qty - 1) else null) else it
                                    }
                                },
                                onPlus = { lines = lines.map { if (it.id == l.id) it.copy(qty = it.qty + 1) else it } }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                PCard("Payment to vendor", Icons.Rounded.Payment, Green) {
                    AppField(
                        discount, { discount = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, "Discount (if any)",
                        type = KeyboardType.Decimal, prefix = "₹ "
                    )
                    Spacer(Modifier.height(12.dp))
                    Segmented(VendorPayModes, mode, { m ->
                        mode = m
                        if (m == "Credit") paid = ""
                    }, accent = if (mode == "Credit") Red else Navy)
                    Spacer(Modifier.height(10.dp))
                    AppField(
                        paid, { paid = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                        if (mode == "Credit") "Paid now (if any)" else "Amount paid",
                        type = KeyboardType.Decimal, prefix = "₹ "
                    )
                    Text(
                        when {
                            paid.isNotBlank() -> "Paid ${rs(paidValue)}"
                            mode == "Credit" -> "Nothing paid now — full amount is payable"
                            else -> "Empty = full amount paid"
                        },
                        Modifier.padding(top = 4.dp), color = Faint, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    InfoRow("Items total", rs(sub))
                    if (disc > 0) InfoRow("Discount", "- " + rs(disc), color = Green)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Line)
                    InfoRow("Purchase total", rs(total), bold = true, color = Navy, size = 18)
                    InfoRow("Paid", rs(paidValue))
                    if (payable > 0.009) InfoRow("Payable to vendor", rs(payable), bold = true, color = Red)
                    Spacer(Modifier.height(10.dp))
                    AppField(note, { note = it }, "Note (optional)", caps = KeyboardCapitalization.Sentences)
                }
                Spacer(Modifier.height(20.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${lines.sumOf { it.qty }} pcs  •  Total", color = Muted, fontSize = 12.sp)
                    Text(rs(total), color = Navy, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
                MainButton(if (isNew) "Save Purchase" else "Update", icon = Icons.Rounded.Check, color = Orange) { save() }
            }
        }
    }

    editLine?.let { l ->
        PurchaseLineDialog(
            line = l,
            onSave = { q, r ->
                lines = if (q <= 0) lines.filter { it.id != l.id }
                else lines.map { if (it.id == l.id) it.copy(qty = q, price = r, buyPrice = r) else it }
            },
            onRemove = { lines = lines.filter { it.id != l.id } },
            onDismiss = { editLine = null }
        )
    }
    newPartName?.let { n ->
        NewPartDialog(
            initial = n,
            onCreate = { name, buy, sell ->
                vm.quickPart(name, buy, sell) { p ->
                    addLine(p, if (buy > 0) buy else p.buyPrice)
                    query = ""
                }
            },
            onDismiss = { newPartName = null }
        )
    }
    if (askDelete) {
        ConfirmDialog(
            title = "Delete this purchase?",
            text = "Its parts (${lines.sumOf { it.qty }} pcs) will be taken out of stock again.",
            confirm = "Delete",
            danger = true,
            onConfirm = { original?.let { b -> vm.deleteBill(b) { vm.back() } } },
            onDismiss = { askDelete = false }
        )
    }
}

@Composable
private fun PickRow(p: Part, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF6F8FC))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, color = Ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(p.partNo, p.brand).filter { it.isNotBlank() }.joinToString("  •  ").ifBlank { p.category } +
                    "  •  Stock ${p.qty}",
                color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "+ Add",
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Teal)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun NewPartRow(name: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, Orange.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.AddCircle, null, tint = Orange, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Create new part \"$name\"", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PLineRow(line: BillItem, part: Part?, onTap: () -> Unit, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onTap)
        ) {
            Text(line.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Rate ${rs(line.price)}  •  tap to change", color = Muted, fontSize = 12.5.sp)
            if (part != null) Text("Stock now: ${part.qty} ${part.unit}", color = Faint, fontSize = 12.sp)
            Text(rs(line.qty * line.price), color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        QtyStepper(line.qty, onMinus, onPlus, onTap = onTap)
    }
}

@Composable
private fun PurchaseLineDialog(line: BillItem, onSave: (Int, Double) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var qty by remember { mutableStateOf(line.qty.toString()) }
    var rate by remember { mutableStateOf(num(line.price)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text(line.name, color = Ink, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppField(
                        qty, { qty = it.filter { c -> c.isDigit() }.take(6) }, "Quantity",
                        Modifier.weight(1f), type = KeyboardType.Number
                    )
                    AppField(
                        rate, { rate = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, "Buy rate",
                        Modifier.weight(1f), type = KeyboardType.Decimal, prefix = "₹ ", imeAction = ImeAction.Done
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("Amount: ${rs((qty.toIntOrNull() ?: 0) * rate.toNum())}", color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(qty.toIntOrNull() ?: 0, rate.toNum())
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
            }) { Text("Remove", color = Red, fontWeight = FontWeight.SemiBold) }
        }
    )
}

@Composable
private fun NewPartDialog(initial: String, onCreate: (String, Double, Double) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var buy by remember { mutableStateOf("") }
    var sell by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text("New part", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "It will be added to your stock list. You can add brand, rack, GST etc. later from Stock.",
                    color = Muted, fontSize = 12.5.sp
                )
                Spacer(Modifier.height(10.dp))
                AppField(name, { name = it }, "Part name *")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppField(
                        buy, { buy = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, "Buy rate",
                        Modifier.weight(1f), type = KeyboardType.Decimal, prefix = "₹ "
                    )
                    AppField(
                        sell, { sell = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, "Sell price",
                        Modifier.weight(1f), type = KeyboardType.Decimal, prefix = "₹ ", imeAction = ImeAction.Done
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(name.trim(), buy.toNum(), sell.toNum())
                    onDismiss()
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange)
            ) { Text("Create & add", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
