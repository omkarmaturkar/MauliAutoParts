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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
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

/** Saved vendors and vendors used before (purchases + part suppliers), most used first. */
private fun vendorList(purchases: List<Bill>, parts: List<Part>, saved: List<String>, typed: String): List<String> {
    val t = typed.trim().lowercase()
    val ranked = (purchases.map { it.customer } + parts.map { it.supplier })
        .map { it.trim() }.filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    val pool = (ranked + saved.map { it.trim() }.filter { it.isNotEmpty() }).distinctBy { it.lowercase() }
    val exact = pool.any { it.lowercase() == t }
    return pool.filter { it.lowercase() != t && (t.isEmpty() || exact || it.lowercase().contains(t)) }.take(10)
}

/** Parts for the picker: everything when nothing is typed. */
private fun searchParts(parts: List<Part>, query: String): List<Part> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return parts
    return parts.filter { p ->
        val hay = "${p.name} ${p.partNo} ${p.brand} ${p.vehicle} ${p.category} ${p.rack}".lowercase()
        words.all { it in hay }
    }
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

private fun decimalText(s: String): String = s.filter { it.isDigit() || it == '.' }.take(10)

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
            actions = {
                HeaderAction(Icons.Rounded.Store, "Vendors") { vm.open(VendorsPage) }
                HeaderAction(Icons.Rounded.Add, "New purchase") { vm.open(Screen.PurchaseEdit(0)) }
            },
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
                item(key = "vendors") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Teal.copy(alpha = 0.08f))
                            .border(1.dp, Teal.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .clickable { vm.open(VendorsPage) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconBadge(Icons.Rounded.Store, Teal, 38.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Vendors", color = Ink, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            Text("Tap a vendor to see all its bills and what you owe", color = Muted, fontSize = 12.sp)
                        }
                        if (payable > 0.009) Pill(rs(payable), Red)
                    }
                }
                items(shown, key = { it.id }) { b ->
                    PurchaseRow(b) { vm.open(Screen.PurchaseEdit(b.id)) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            MainButton("New purchase", Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, color = Teal) {
                vm.open(Screen.PurchaseEdit(0))
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
    val savedVendors by remember { vm.dao.vendorsFlow() }.collectAsState(initial = emptyList())
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
    var showPicker by remember { mutableStateOf(false) }
    var newPartName by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var askAll by remember { mutableStateOf(false) }

    // What is typed in each line's Rate and Disc % box, kept per line id.
    val rateText = remember { mutableStateMapOf<Long, String>() }
    val pctText = remember { mutableStateMapOf<Long, String>() }

    /** Adds a part, with its own rate and discount %. Adding it again only raises the quantity. */
    fun addLine(p: Part, list: Double = p.buyPrice, pct: Double = 0.0) {
        val ex = lines.find { it.partId == p.id }
        if (ex != null) {
            lines = lines.map { if (it.id == ex.id) it.copy(qty = it.qty + 1) else it }
            return
        }
        val lineId = System.nanoTime()
        val net = netRate(list, pct)
        lines = lines + BillItem(
            id = lineId, partId = p.id, name = p.name, partNo = p.partNo,
            qty = 1, price = net, buyPrice = net, listPrice = list, discPct = pct, gst = p.gst
        )
        rateText[lineId] = if (list > 0.0) num(list) else ""
        pctText[lineId] = if (pct > 0.0) num(pct) else ""
    }

    fun removeLine(lineId: Long) {
        lines = lines.filter { it.id != lineId }
        rateText.remove(lineId)
        pctText.remove(lineId)
    }

    /** Re-reads one line's rate and % from its boxes and works out what you pay. */
    fun recalc(lineId: Long) {
        val list = rateText[lineId].orEmpty().toNum()
        val pct = pctText[lineId].orEmpty().toNum().coerceIn(0.0, 100.0)
        val net = netRate(list, pct)
        lines = lines.map {
            if (it.id == lineId) it.copy(listPrice = list, discPct = pct, price = net, buyPrice = net) else it
        }
    }

    /** Only for a vendor who gives one flat % on the whole bill. */
    fun applyAll(pct: Double) {
        val pc = pct.coerceIn(0.0, 100.0)
        lines = lines.map { l ->
            val list = l.listRate
            pctText[l.id] = if (pc > 0.0) num(pc) else ""
            l.copy(listPrice = list, discPct = pc, price = netRate(list, pc), buyPrice = netRate(list, pc))
        }
    }

    fun fillPhone(name: String) {
        if (phone.isNotBlank()) return
        val fromSaved = savedVendors.firstOrNull { it.customer.trim().equals(name, true) && it.phone.isNotBlank() }
        val fromBill = purchases.firstOrNull { it.customer.trim().equals(name, true) && it.phone.isNotBlank() }
        phone = (fromSaved ?: fromBill)?.phone.orEmpty()
    }

    LaunchedEffect(id) {
        if (isNew) {
            if (pendingVendorName.isNotBlank()) {
                vendor = pendingVendorName
                pendingVendorName = ""
            }
            if (pendingPurchasePartId != 0L) {
                vm.dao.part(pendingPurchasePartId)?.let { p ->
                    addLine(p)
                    if (vendor.isBlank() && p.supplier.isNotBlank()) vendor = p.supplier
                }
                pendingPurchasePartId = 0L
            }
            if (vendor.isNotBlank()) fillPhone(vendor)
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
                val loadedLines = vm.dao.items(id).mapIndexed { i, l -> l.copy(id = base + i, billId = 0) }
                loadedLines.forEach { l ->
                    rateText[l.id] = if (l.listRate > 0.0) num(l.listRate) else ""
                    pctText[l.id] = if (l.discPct > 0.0) num(l.discPct) else ""
                }
                lines = loadedLines
            }
            loaded = true
        }
    }

    val sub = lines.sumOf { it.qty * it.price }
    val saved = lines.sumOf { it.qty * (it.listRate - it.price) }.coerceAtLeast(0.0)
    val disc = discount.toNum().coerceIn(0.0, sub)
    val total = sub - disc
    val paidValue = if (paid.isBlank()) (if (mode == "Credit") 0.0 else total) else paid.toNum().coerceIn(0.0, total)
    val payable = (total - paidValue).coerceAtLeast(0.0)
    val partsById = remember(parts) { parts.associateBy { it.id } }
    val vendors = remember(purchases, parts, savedVendors, vendor) {
        vendorList(purchases, parts, savedVendors.map { it.customer }, vendor)
    }
    val inPurchase = lines.filter { it.partId != null }.groupBy { it.partId!! }.mapValues { e -> e.value.sumOf { it.qty } }

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
                        fillPhone(v)
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
                    if (vendor.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "See all bills of this vendor →",
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { vm.open(VendorPage(vendor.trim())) }
                                .padding(vertical = 4.dp),
                            color = Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                PCard("Parts purchased", Icons.Rounded.LocalShipping, Teal) {
                    MainButton("+ Add parts", Modifier.fillMaxWidth(), color = Teal) { showPicker = true }
                    if (lines.isEmpty()) {
                        Text(
                            "Tap Add parts to pick from your stock list. Stock goes up when you save.",
                            Modifier.padding(vertical = 12.dp),
                            color = Muted, fontSize = 13.sp
                        )
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Type the vendor's rate and the discount % for each part separately.",
                            color = Faint, fontSize = 12.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        lines.forEachIndexed { i, l ->
                            if (i > 0) HorizontalDivider(color = Line)
                            PLineRow(
                                index = i,
                                line = l,
                                part = l.partId?.let { partsById[it] },
                                rate = rateText[l.id].orEmpty(),
                                pct = pctText[l.id].orEmpty(),
                                onRate = {
                                    rateText[l.id] = decimalText(it)
                                    recalc(l.id)
                                },
                                onPct = {
                                    pctText[l.id] = it.filter { c -> c.isDigit() || c == '.' }.take(5)
                                    recalc(l.id)
                                },
                                onMinus = {
                                    if (l.qty > 1) lines = lines.map { x -> if (x.id == l.id) x.copy(qty = x.qty - 1) else x }
                                    else removeLine(l.id)
                                },
                                onPlus = { lines = lines.map { x -> if (x.id == l.id) x.copy(qty = x.qty + 1) else x } },
                                onRemove = { removeLine(l.id) }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Same % for all items →",
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { askAll = true }
                                .padding(vertical = 6.dp, horizontal = 2.dp),
                            color = Navy, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                PCard("Payment to vendor", Icons.Rounded.Payment, Green) {
                    AppField(
                        discount, { discount = decimalText(it) }, "Extra discount on whole bill (if any)",
                        type = KeyboardType.Decimal, prefix = "₹ "
                    )
                    Spacer(Modifier.height(12.dp))
                    Segmented(VendorPayModes, mode, { m ->
                        mode = m
                        if (m == "Credit") paid = ""
                    }, accent = if (mode == "Credit") Red else Navy)
                    Spacer(Modifier.height(10.dp))
                    AppField(
                        paid, { paid = decimalText(it) },
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
                    if (saved > 0.009) {
                        InfoRow("Items at vendor rate", rs(sub + saved))
                        InfoRow("Discount on items", "- " + rs(saved), color = Green)
                    }
                    InfoRow("Items total", rs(sub))
                    if (disc > 0) InfoRow("Extra bill discount", "- " + rs(disc), color = Green)
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

    if (showPicker) {
        PartPickerDialog(
            parts = parts,
            inPurchase = inPurchase,
            onAdd = { p -> addLine(p) },
            onCreate = { typed ->
                showPicker = false
                newPartName = typed.replaceFirstChar { it.uppercase() }
            },
            onDismiss = { showPicker = false }
        )
    }
    if (askAll) {
        InputDialog(
            title = "Same % for all items",
            label = "Discount %",
            message = "Use this only when the vendor gives one flat % on the whole bill. It replaces the % of every item.",
            confirm = "Apply",
            onConfirm = { v -> applyAll(v.toNum()) },
            onDismiss = { askAll = false }
        )
    }
    newPartName?.let { n ->
        NewPartDialog(
            initial = n,
            onCreate = { name, list, pct, sell ->
                vm.quickPart(name, netRate(list, pct), sell) { p -> addLine(p, list, pct) }
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

/** Small text box used inside a purchase line (rate and discount %). */
@Composable
private fun MiniField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    suffix: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label, fontSize = 11.sp, maxLines = 1) },
        prefix = if (prefix != null) { { Text(prefix, color = Muted, fontSize = 13.sp) } } else null,
        suffix = if (suffix != null) { { Text(suffix, color = Muted, fontSize = 13.sp) } } else null,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedBorderColor = Teal,
            unfocusedBorderColor = Line,
            focusedLabelColor = Teal,
            unfocusedLabelColor = Muted,
            cursorColor = Navy,
            focusedTextColor = Ink,
            unfocusedTextColor = Ink
        )
    )
}

/** One purchased part: its own rate, its own discount %, and the amount. */
@Composable
private fun PLineRow(
    index: Int,
    line: BillItem,
    part: Part?,
    rate: String,
    pct: String,
    onRate: (String) -> Unit,
    onPct: (String) -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onRemove: () -> Unit
) {
    val off = line.discPct > 0.0 && line.listRate > line.price
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${index + 1}. ${line.name}", color = Ink, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (part != null) Text("Stock now: ${part.qty} ${part.unit}", color = Faint, fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Close, "Remove", tint = Faint, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniField(rate, onRate, "Rate", Modifier.weight(1.25f), prefix = "₹")
            Spacer(Modifier.width(8.dp))
            MiniField(pct, onPct, "Discount", Modifier.weight(1f), suffix = "%")
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (off) "${rs(line.price)} each after ${num(line.discPct)}% off" else "${rs(line.price)} each",
                    color = if (off) Green else Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
                )
                if (off) {
                    Text(
                        "You save ${rs(line.qty * (line.listRate - line.price))}",
                        color = Green, fontSize = 11.5.sp
                    )
                }
            }
            QtyStepper(line.qty, onMinus, onPlus)
            Spacer(Modifier.width(10.dp))
            Text(rs(line.qty * line.price), color = Navy, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Full stock list with a search box. Stays open so several parts can be added one after another. */
@Composable
private fun PartPickerDialog(
    parts: List<Part>,
    inPurchase: Map<Long, Int>,
    onAdd: (Part) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(parts, query) { searchParts(parts, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Add parts", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                SearchBox(query, { query = it }, "Search part name, part no…")
                Spacer(Modifier.height(10.dp))
                NewPartRow(query.trim()) { onCreate(query.trim()) }
                Spacer(Modifier.height(6.dp))
                if (shown.isEmpty()) {
                    Text(
                        if (parts.isEmpty()) "Your stock list is empty. Tap above to create the first part."
                        else "No part matches \"${query.trim()}\".",
                        Modifier.padding(vertical = 10.dp), color = Muted, fontSize = 13.5.sp
                    )
                } else {
                    Text("${shown.size} part${if (shown.size == 1) "" else "s"}  •  tap to add", color = Faint, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(shown, key = { it.id }) { p ->
                            PickRow(p, inPurchase[p.id] ?: 0) { onAdd(p) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) { Text("Done", fontWeight = FontWeight.Bold) }
        }
    )
}

@Composable
private fun PickRow(p: Part, added: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (added > 0) Teal.copy(alpha = 0.08f) else Color(0xFFF6F8FC))
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
            if (added > 0) "Added $added" else "+ Add",
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (added > 0) Navy else Teal)
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
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, Orange.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.AddCircle, null, tint = Orange, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (name.isBlank()) "Create a new part" else "Create new part \"$name\"",
            color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NewPartDialog(
    initial: String,
    onCreate: (String, Double, Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    var rate by remember { mutableStateOf("") }
    var pct by remember { mutableStateOf("") }
    var sell by remember { mutableStateOf("") }
    val list = rate.toNum()
    val pc = pct.toNum().coerceIn(0.0, 100.0)
    val net = netRate(list, pc)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text("New part", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "It will be added to your stock list and to this purchase. Brand, rack and GST can be added later from Stock.",
                    color = Muted, fontSize = 12.5.sp
                )
                Spacer(Modifier.height(10.dp))
                AppField(name, { name = it }, "Part name *")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppField(
                        rate, { rate = decimalText(it) }, "Vendor rate",
                        Modifier.weight(1.2f), type = KeyboardType.Decimal, prefix = "₹ "
                    )
                    AppField(
                        pct, { pct = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, "Discount",
                        Modifier.weight(1f), type = KeyboardType.Decimal, suffix = "%"
                    )
                }
                if (pc > 0.0 && list > 0.0) {
                    Spacer(Modifier.height(6.dp))
                    Text("You pay ${rs(net)} each", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                AppField(
                    sell, { sell = decimalText(it) }, "Sell price (to customer)",
                    type = KeyboardType.Decimal, prefix = "₹ ", imeAction = ImeAction.Done
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(name.trim(), list, pc, sell.toNum())
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
