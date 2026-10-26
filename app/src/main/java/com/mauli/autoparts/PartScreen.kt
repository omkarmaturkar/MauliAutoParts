package com.mauli.autoparts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Name typed in Stock search, used to pre-fill a new part. */
var pendingPartName: String = ""

/** Part to put in a new purchase when "Record purchase" is tapped on the part screen. */
var pendingPurchasePartId: Long = 0L

val DefaultCategories = listOf(
    "Engine", "Electrical", "Lights", "Brake", "Clutch", "Suspension", "Body Parts", "Filters",
    "Oil & Lube", "Tyres & Tubes", "Battery", "Bearings", "Cables", "Accessories", "General"
)

private val UnitOptions = listOf("pcs", "set", "pair", "ltr", "kg", "mtr", "box")
private val GstOptions = listOf("0", "5", "12", "18", "28")

/** Form values are kept as text so typing feels natural. */
private data class PartForm(
    val name: String = "",
    val partNo: String = "",
    val category: String = "",
    val brand: String = "",
    val vehicle: String = "",
    val rack: String = "",
    val unit: String = "pcs",
    val buy: String = "",
    val sell: String = "",
    val mrp: String = "",
    val qty: String = "",
    val minQty: String = "2",
    val gst: String = "18",
    val hsn: String = "",
    val supplier: String = "",
    val notes: String = ""
)

private fun blankZero(v: Double): String = if (v == 0.0) "" else num(v)

private fun Part.toForm() = PartForm(
    name = name, partNo = partNo, category = category, brand = brand, vehicle = vehicle, rack = rack,
    unit = unit, buy = blankZero(buyPrice), sell = blankZero(sellPrice), mrp = blankZero(mrp),
    qty = qty.toString(), minQty = minQty.toString(), gst = num(gst), hsn = hsn, supplier = supplier, notes = notes
)

private fun PartForm.toPart(id: Long): Part {
    val sellN = sell.toNum()
    val mrpN = mrp.toNum()
    return Part(
        id = id,
        name = name.trim(),
        partNo = partNo.trim(),
        category = category.trim().ifBlank { "General" },
        brand = brand.trim(),
        vehicle = vehicle.trim(),
        rack = rack.trim().uppercase(),
        unit = unit.ifBlank { "pcs" },
        buyPrice = buy.toNum(),
        sellPrice = if (sellN > 0) sellN else mrpN,
        mrp = mrpN,
        qty = qty.toNum().toInt(),
        minQty = minQty.toNum().toInt(),
        gst = gst.toNum(),
        hsn = hsn.trim(),
        supplier = supplier.trim(),
        notes = notes.trim()
    )
}

private fun decimal(s: String) = s.filter { it.isDigit() || it == '.' }.take(10)
private fun digits(s: String) = s.filter { it.isDigit() }.take(6)

/** Most-used values (brands, vehicles, racks...) that match what is typed. */
private fun suggest(all: List<String>, typed: String, extra: List<String> = emptyList()): List<String> {
    val t = typed.trim().lowercase()
    val ranked = all.map { it.trim() }.filter { it.isNotEmpty() }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    val pool = (ranked + extra).distinctBy { it.lowercase() }
    val exact = pool.any { it.lowercase() == t }
    return pool.filter { it.lowercase() != t && (t.isEmpty() || exact || it.lowercase().contains(t)) }.take(10)
}

@Composable
private fun Suggestions(values: List<String>, onPick: (String) -> Unit) {
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

@Composable
private fun FormCard(title: String, icon: ImageVector, tint: Color, content: @Composable ColumnScope.() -> Unit) {
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

/** Warning shown when this part is sitting in an open (not yet saved) bill. */
@Composable
private fun OpenBillNotice(qty: Int, unit: String, stock: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFFF7E6))
            .border(1.dp, Amber.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Rounded.Warning, null, tint = Amber, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("$qty $unit in an open bill", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "Stock will reduce by itself when that bill is saved ($stock → ${stock - qty}). Don't reduce it here.",
                color = Muted, fontSize = 12.5.sp
            )
        }
    }
}

@Composable
fun EditPartScreen(vm: AppViewModel, id: Long) {
    val parts by vm.parts.collectAsState()
    val history by remember(id) { vm.dao.partPurchases(id) }.collectAsState(initial = emptyList())
    var form by remember { mutableStateOf(PartForm()) }
    var original by remember { mutableStateOf<Part?>(null) }
    var added by remember { mutableIntStateOf(0) }
    var askDelete by remember { mutableStateOf(false) }
    var dup by remember { mutableStateOf<Pair<Part, Boolean>?>(null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val nameFocus = remember { FocusRequester() }
    val isNew = id == 0L
    val inOpenBills = if (isNew) 0 else (vm.reserved()[id] ?: 0)

    LaunchedEffect(id) {
        if (isNew) {
            if (pendingPartName.isNotBlank()) {
                form = form.copy(name = pendingPartName.replaceFirstChar { it.uppercase() })
                pendingPartName = ""
            }
            delay(350)
            try {
                nameFocus.requestFocus()
            } catch (e: Exception) {
            }
        } else {
            vm.dao.part(id)?.let {
                original = it
                form = it.toForm()
            }
        }
    }

    // After saving: "Save" goes back, "Save & Next" clears the form but keeps brand/category/rack etc.
    fun afterSave(next: Boolean) {
        if (next) {
            added++
            form = PartForm(
                category = form.category, brand = form.brand, vehicle = form.vehicle, rack = form.rack,
                unit = form.unit, minQty = form.minQty, gst = form.gst, hsn = form.hsn, supplier = form.supplier
            )
            scope.launch { scroll.animateScrollTo(0) }
            try {
                nameFocus.requestFocus()
            } catch (e: Exception) {
            }
        } else {
            vm.back()
        }
    }

    fun save(next: Boolean) {
        val p = form.toPart(id)
        if (p.name.isBlank()) {
            vm.toast("Please enter part name")
            return
        }
        if (isNew) {
            val same = parts.firstOrNull {
                it.name.equals(p.name, true) && it.partNo.equals(p.partNo, true) && it.brand.equals(p.brand, true)
            }
            if (same != null) {
                dup = same to next
                return
            }
            vm.savePart(p) { afterSave(next) }
        } else {
            // Keep any stock change that happened while this screen was open (bills, purchases).
            val loaded = original?.qty ?: p.qty
            scope.launch {
                val fresh = vm.dao.part(id)
                val qty = (fresh?.qty ?: loaded) + (p.qty - loaded)
                vm.savePart(p.copy(qty = qty)) { afterSave(next) }
            }
        }
    }

    val buyN = form.buy.toNum()
    val sellN = form.sell.toNum().takeIf { it > 0 } ?: form.mrp.toNum()
    val units = (UnitOptions + form.unit).distinct()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (isNew) "Add Part" else "Edit Part",
            subtitle = when {
                isNew && added > 0 -> "$added part${if (added == 1) "" else "s"} added ✓  Keep going!"
                isNew -> "Enter once, stock updates automatically"
                else -> original?.takeIf { it.updatedAt > 0 }?.let { "Last updated ${fmtDate(it.updatedAt)}" }
            },
            onBack = { vm.back() },
            actions = {
                if (!isNew) HeaderAction(Icons.Rounded.Delete, "Delete") { askDelete = true }
            }
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (inOpenBills > 0) {
                OpenBillNotice(inOpenBills, form.unit, original?.qty ?: 0)
                Spacer(Modifier.height(14.dp))
            }
            FormCard("Part details", Icons.Rounded.Build, Navy) {
                AppField(form.name, { form = form.copy(name = it) }, "Part name *", Modifier.focusRequester(nameFocus))
                Spacer(Modifier.height(10.dp))
                AppField(form.partNo, { form = form.copy(partNo = it) }, "Part number", caps = KeyboardCapitalization.Characters)
                Spacer(Modifier.height(10.dp))
                AppField(form.brand, { form = form.copy(brand = it) }, "Brand / company")
                Suggestions(remember(parts, form.brand) { suggest(parts.map { it.brand }, form.brand) }) {
                    form = form.copy(brand = it)
                }
                Spacer(Modifier.height(10.dp))
                AppField(form.vehicle, { form = form.copy(vehicle = it) }, "Fits vehicle (e.g. Splendor, Activa)")
                Suggestions(remember(parts, form.vehicle) { suggest(parts.map { it.vehicle }, form.vehicle) }) {
                    form = form.copy(vehicle = it)
                }
                Spacer(Modifier.height(10.dp))
                AppField(form.category, { form = form.copy(category = it) }, "Category")
                Suggestions(remember(parts, form.category) { suggest(parts.map { it.category }, form.category, DefaultCategories) }) {
                    form = form.copy(category = it)
                }
                Spacer(Modifier.height(10.dp))
                AppField(form.rack, { form = form.copy(rack = it) }, "Rack / shelf (e.g. A2)", caps = KeyboardCapitalization.Characters)
                Suggestions(remember(parts, form.rack) { suggest(parts.map { it.rack }, form.rack) }) {
                    form = form.copy(rack = it)
                }
            }
            Spacer(Modifier.height(14.dp))
            FormCard("Price & GST", Icons.Rounded.LocalOffer, Green) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppField(
                        form.buy, { form = form.copy(buy = decimal(it)) }, "Buy price", Modifier.weight(1f),
                        type = KeyboardType.Decimal, prefix = "₹ "
                    )
                    AppField(
                        form.sell, { form = form.copy(sell = decimal(it)) }, "Sell price", Modifier.weight(1f),
                        type = KeyboardType.Decimal, prefix = "₹ "
                    )
                }
                Spacer(Modifier.height(10.dp))
                AppField(form.mrp, { form = form.copy(mrp = decimal(it)) }, "MRP (printed price)", type = KeyboardType.Decimal, prefix = "₹ ")
                if (form.sell.isBlank() && form.mrp.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Sell price is empty, so MRP will be used", color = Muted, fontSize = 12.sp)
                }
                if (buyN > 0 && sellN > 0) {
                    val margin = sellN - buyN
                    val pct = Math.round(margin / buyN * 1000) / 10.0
                    val mColor = if (margin >= 0) Green else Red
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Profit ${rs(margin)} per ${form.unit}  (${num(pct)}%)",
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(mColor.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        color = mColor, fontSize = 13.5.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text("GST rate", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                ChipRow(GstOptions, num(form.gst.toNum()), { form = form.copy(gst = it) }, label = { "$it%" })
                Spacer(Modifier.height(10.dp))
                AppField(
                    form.hsn, { form = form.copy(hsn = it.filter { c -> c.isDigit() }.take(8)) },
                    "HSN code (for GST bills)", type = KeyboardType.Number
                )
            }
            Spacer(Modifier.height(14.dp))
            FormCard("Stock", Icons.Rounded.Layers, Orange) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppField(
                        form.qty, { form = form.copy(qty = digits(it)) },
                        if (isNew) "Quantity in shop" else "Quantity now",
                        Modifier.weight(1f), type = KeyboardType.Number
                    )
                    AppField(
                        form.minQty, { form = form.copy(minQty = digits(it)) }, "Low alert at",
                        Modifier.weight(1f), type = KeyboardType.Number
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Unit", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                ChipRow(units, form.unit, { form = form.copy(unit = it) })
                Spacer(Modifier.height(10.dp))
                Text(
                    "You'll get a Low stock alert when quantity reaches ${form.minQty.ifBlank { "0" }}.",
                    color = Muted, fontSize = 12.sp
                )
                if (!isNew) {
                    Spacer(Modifier.height(12.dp))
                    LightButton("Record purchase (adds stock)", Modifier.fillMaxWidth(), icon = Icons.Rounded.LocalShipping, color = Teal) {
                        pendingPurchasePartId = id
                        vm.open(Screen.PurchaseEdit(0))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "When new stock arrives, use Record purchase so the vendor, quantity and rate are saved. " +
                            "Change the quantity above only to correct a counting mistake.",
                        color = Muted, fontSize = 12.sp
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            FormCard("More details (optional)", Icons.Rounded.Info, Purple) {
                AppField(form.supplier, { form = form.copy(supplier = it) }, "Supplier / dealer")
                Suggestions(remember(parts, form.supplier) { suggest(parts.map { it.supplier }, form.supplier) }) {
                    form = form.copy(supplier = it)
                }
                Spacer(Modifier.height(10.dp))
                AppField(
                    form.notes, { form = form.copy(notes = it) }, "Notes",
                    caps = KeyboardCapitalization.Sentences, singleLine = false
                )
            }
            if (!isNew) {
                Spacer(Modifier.height(14.dp))
                FormCard("Purchased from", Icons.Rounded.LocalShipping, Teal) {
                    if (history.isEmpty()) {
                        Text("No purchases recorded for this part yet.", color = Muted, fontSize = 13.sp)
                    } else {
                        history.forEachIndexed { i, h ->
                            if (i > 0) HorizontalDivider(color = Line)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.open(Screen.PurchaseEdit(h.id)) }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        h.vendor.ifBlank { "Vendor" }, color = Ink, fontSize = 14.5.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(fmtDate(h.date), color = Muted, fontSize = 12.5.sp)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("+${h.qty} ${form.unit}", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("@ ${rs(h.rate)}", color = Muted, fontSize = 12.5.sp)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isNew) {
                LightButton("Save & Next", Modifier.weight(1f), icon = Icons.Rounded.Add) { save(true) }
                MainButton("Save", Modifier.weight(1f), icon = Icons.Rounded.Check, color = Orange) { save(false) }
            } else {
                MainButton("Save changes", Modifier.fillMaxWidth(), icon = Icons.Rounded.Check, color = Orange) { save(false) }
            }
        }
    }

    if (askDelete) {
        ConfirmDialog(
            title = "Delete this part?",
            text = "${form.name} will be removed from stock. Old bills will still show it.",
            confirm = "Delete",
            danger = true,
            onConfirm = {
                original?.let { vm.deletePart(it) }
                vm.back()
            },
            onDismiss = { askDelete = false }
        )
    }

    dup?.let { (same, next) ->
        val q = form.qty.toNum().toInt()
        AlertDialog(
            onDismissRequest = { dup = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Already in stock", color = Ink, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "${same.name} is already saved (${same.qty} ${same.unit} in stock)." +
                        if (q > 0) " Add $q more to it instead of making a duplicate?" else " Open it instead of making a duplicate?",
                    color = Muted, fontSize = 15.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        dup = null
                        if (q > 0) {
                            vm.addStock(same, q, form.buy.toNum().takeIf { it > 0 })
                            afterSave(next)
                        } else {
                            vm.back()
                            vm.open(Screen.EditPart(same.id))
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Navy)
                ) { Text(if (q > 0) "Add to stock" else "Open it", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    dup = null
                    vm.savePart(form.toPart(0)) { afterSave(next) }
                }) { Text("Save as new", color = Muted) }
            }
        )
    }
}
