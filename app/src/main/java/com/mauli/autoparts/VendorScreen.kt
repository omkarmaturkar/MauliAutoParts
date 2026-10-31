package com.mauli.autoparts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** The vendor list. */
data object VendorsPage : Screen(), LeafScreen

/** One vendor with all its purchase bills. */
data class VendorPage(val name: String) : Screen(), LeafScreen

/** Vendor name to fill in when a new purchase is opened from a vendor page. */
var pendingVendorName: String = ""

private const val VENDOR_KIND = 4
private const val VENDOR_BASE = -1_000_000L
private val VendorFilters = listOf("All", "To pay", "A–Z")

/** A vendor: its saved details (if any) and every purchase made from it, newest first. */
private class VendorRow(val name: String, val saved: Bill?, val bills: List<Bill>) {
    val key: String = name.trim().lowercase()
    val phone: String = saved?.phone?.takeIf { it.isNotBlank() }
        ?: bills.map { it.phone }.firstOrNull { it.isNotBlank() }.orEmpty()
    val gstin: String = saved?.ref.orEmpty()
    val address: String = saved?.note.orEmpty()
    val total: Double = bills.sumOf { it.total }
    val paid: Double = bills.sumOf { it.paid }
    val payable: Double = bills.sumOf { it.due }
    val last: Bill? = bills.firstOrNull()
}

private class VendorForm(
    val id: Long = 0,
    val name: String = "",
    val phone: String = "",
    val gstin: String = "",
    val address: String = ""
)

/** Saved vendors and vendors found in old purchases, merged into one list. */
private fun vendorRows(saved: List<Bill>, purchases: List<Bill>): List<VendorRow> {
    val byKey = LinkedHashMap<String, MutableList<Bill>>()
    purchases.forEach { b ->
        val n = b.customer.trim()
        if (n.isNotEmpty()) byKey.getOrPut(n.lowercase()) { mutableListOf() }.add(b)
    }
    val savedByKey = saved.filter { it.customer.isNotBlank() }.associateBy { it.customer.trim().lowercase() }
    val keys = LinkedHashSet<String>()
    keys.addAll(savedByKey.keys)
    keys.addAll(byKey.keys)
    return keys.map { k ->
        val bills = (byKey[k] ?: mutableListOf()).sortedByDescending { it.date }
        val name = savedByKey[k]?.customer?.trim() ?: bills.firstOrNull()?.customer?.trim() ?: k
        VendorRow(name, savedByKey[k], bills)
    }
}

private fun filterVendors(rows: List<VendorRow>, query: String, filter: String): List<VendorRow> {
    val base = when (filter) {
        "To pay" -> rows.filter { it.payable > 0.009 }.sortedByDescending { it.payable }
        "A–Z" -> rows.sortedBy { it.key }
        else -> rows.sortedWith(compareByDescending<VendorRow> { it.last?.date ?: 0L }.thenBy { it.key })
    }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return base
    return base.filter { v ->
        val hay = "${v.name} ${v.phone} ${v.gstin} ${v.address}".lowercase()
        words.all { it in hay }
    }
}

private fun openNewPurchase(vm: AppViewModel, name: String) {
    pendingVendorName = name
    vm.open(Screen.PurchaseEdit(0))
}

/** Saves a vendor. Renaming also renames its old purchases and part supplier names. */
private fun saveVendor(
    vm: AppViewModel,
    savedList: List<Bill>,
    purchases: List<Bill>,
    parts: List<Part>,
    form: VendorForm,
    oldName: String,
    onDone: (String) -> Unit
) {
    val name = form.name.trim()
    if (name.isBlank()) {
        vm.toast("Please enter the vendor name")
        return
    }
    if (savedList.any { it.id != form.id && it.customer.trim().equals(name, true) }) {
        vm.toast("$name is already in your vendor list")
        return
    }
    val renamed = oldName.isNotBlank() && !oldName.equals(name, true)
    vm.viewModelScope.launch {
        try {
            val id = if (form.id != 0L) form.id else (savedList.minOfOrNull { it.id } ?: VENDOR_BASE) - 1
            vm.dao.putBills(
                listOf(
                    Bill(
                        id = id, kind = VENDOR_KIND, date = now(), customer = name,
                        phone = form.phone.trim(), ref = form.gstin.trim(), note = form.address.trim()
                    )
                )
            )
            if (renamed) {
                purchases.filter { it.customer.trim().equals(oldName, true) }
                    .forEach { vm.dao.updateBill(it.copy(customer = name)) }
                parts.filter { it.supplier.trim().equals(oldName, true) }
                    .forEach { vm.dao.setSupplier(it.id, name) }
            }
            vm.toast(if (renamed) "Vendor renamed to $name ✓" else "Vendor saved ✓")
            onDone(name)
        } catch (e: Exception) {
            vm.toast("Could not save the vendor")
        }
    }
}

private fun removeVendor(vm: AppViewModel, id: Long) {
    if (id == 0L) return
    vm.viewModelScope.launch {
        vm.dao.deleteBill(id)
        vm.toast("Vendor details removed • its purchases are safe")
    }
}

private fun partsText(items: List<BillItem>): String =
    items.joinToString(", ") { if (it.qty > 1) "${it.name} ×${it.qty}" else it.name }

private fun vendorMessage(shop: String, v: VendorRow): String = buildString {
    append("Namaskar ").append(v.name).append(",\n")
    append("This is *").append(shop).append("*.\n")
    if (v.payable > 0.009) append("Our pending payment to you is *").append(rs(v.payable)).append("*.\n")
    append("Thank you 🙏")
}

// ================= Vendor list =================

@Composable
fun VendorListScreen(vm: AppViewModel) {
    val purchases by vm.purchases.collectAsState()
    val parts by vm.parts.collectAsState()
    val savedList by remember { vm.dao.vendorsFlow() }.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var form by remember { mutableStateOf<VendorForm?>(null) }

    val rows = remember(savedList, purchases) { vendorRows(savedList, purchases) }
    val shown = remember(rows, query, filter) { filterVendors(rows, query, filter) }
    val payable = rows.sumOf { it.payable }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Vendors",
            subtitle = "${rows.size} vendor${if (rows.size == 1) "" else "s"}  •  To pay ${rs(payable)}",
            onBack = { vm.back() },
            actions = { HeaderAction(Icons.Rounded.Add, "Add vendor") { form = VendorForm() } },
            bottom = {
                SearchBox(query, { query = it }, "Search vendor name, mobile, GSTIN…")
                Spacer(Modifier.height(10.dp))
                ChipRow(VendorFilters, filter, { filter = it }, onDark = true)
            }
        )
        if (shown.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                EmptyState(
                    Icons.Rounded.Store,
                    if (rows.isEmpty()) "No vendors yet" else "No vendor found",
                    if (rows.isEmpty()) "Add the shops you buy parts from. Every purchase you record is kept under its vendor."
                    else "Try another name or mobile number.",
                    action = if (rows.isEmpty()) "Add first vendor" else null
                ) { form = VendorForm() }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.key }) { v ->
                    VendorCard(v) { vm.open(VendorPage(v.name)) }
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
            MainButton("Add vendor", Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, color = Teal) {
                form = VendorForm()
            }
        }
    }

    form?.let { f ->
        VendorDialog(
            initial = f,
            onSave = { edited -> saveVendor(vm, savedList, purchases, parts, edited, f.name) { } },
            onRemove = if (f.id != 0L) ({ removeVendor(vm, f.id) }) else null,
            onDismiss = { form = null }
        )
    }
}

@Composable
private fun VendorCard(v: VendorRow, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(v.name, if (v.payable > 0.009) Red else Teal, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.name, color = Ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(if (v.phone.isNotBlank()) v.phone else "", "${v.bills.size} bill${if (v.bills.size == 1) "" else "s"}")
                        .filter { it.isNotBlank() }.joinToString("  •  "),
                    color = Muted, fontSize = 12.5.sp, maxLines = 1
                )
                Text(
                    v.last?.let { "Last purchase: " + fmtDate(it.date) } ?: "No purchase recorded yet",
                    color = Faint, fontSize = 11.5.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(rs(v.total), color = Ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (v.payable > 0.009) Pill("Pay ${rs(v.payable)}", Red) else Pill("Settled", Green)
            }
        }
    }
}

// ================= One vendor =================

@Composable
fun VendorDetailScreen(vm: AppViewModel, name: String) {
    val context = LocalContext.current
    val purchases by vm.purchases.collectAsState()
    val parts by vm.parts.collectAsState()
    val savedList by remember { vm.dao.vendorsFlow() }.collectAsState(initial = emptyList())
    val key = name.trim().lowercase()
    val v = remember(savedList, purchases, key) {
        val saved = savedList.firstOrNull { it.customer.trim().lowercase() == key }
        val bills = purchases.filter { it.customer.trim().lowercase() == key }.sortedByDescending { it.date }
        if (saved == null && bills.isEmpty()) null
        else VendorRow(saved?.customer?.trim() ?: bills.first().customer.trim(), saved, bills)
    }
    var itemsByBill by remember { mutableStateOf<Map<Long, List<BillItem>>>(emptyMap()) }
    var form by remember { mutableStateOf<VendorForm?>(null) }
    var payFor by remember { mutableStateOf<Bill?>(null) }

    LaunchedEffect(v?.bills?.map { it.id }) {
        val map = HashMap<Long, List<BillItem>>()
        v?.bills?.forEach { map[it.id] = vm.dao.items(it.id) }
        itemsByBill = map
    }

    if (v == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title = name, subtitle = "Vendor not found", onBack = { vm.back() })
            EmptyState(Icons.Rounded.Store, "Vendor not found", "This vendor has no saved details and no purchases.")
        }
        return
    }

    val shop = vm.settings.shopName.ifBlank { "Mauli Auto Parts" }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = v.name,
            subtitle = "${v.bills.size} bill${if (v.bills.size == 1) "" else "s"}  •  To pay ${rs(v.payable)}",
            onBack = { vm.back() },
            actions = {
                HeaderAction(Icons.Rounded.Edit, "Edit vendor") {
                    form = VendorForm(v.saved?.id ?: 0L, v.name, v.phone, v.gstin, v.address)
                }
            }
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "summary") {
                AppCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(v.name, Teal, 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val line = listOf(
                                if (v.phone.isNotBlank()) "Mob ${v.phone}" else "",
                                if (v.gstin.isNotBlank()) "GSTIN ${v.gstin}" else ""
                            ).filter { it.isNotBlank() }.joinToString("  •  ")
                            if (line.isNotBlank()) Text(line, color = Muted, fontSize = 12.5.sp, maxLines = 2)
                            if (v.address.isNotBlank()) Text(v.address, color = Faint, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row {
                        VStat("Purchased", rs(v.total), Modifier.weight(1f))
                        VStat("Paid", rs(v.paid), Modifier.weight(1f))
                        VStat("To pay", rs(v.payable), Modifier.weight(1f), if (v.payable > 0.009) Red else Green)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MainButton("New purchase", Modifier.weight(1f), icon = Icons.Rounded.Add, color = Orange) {
                            openNewPurchase(vm, v.name)
                        }
                        LightButton("Call", Modifier.weight(0.7f), icon = Icons.Rounded.Call, color = Green) {
                            if (v.phone.isBlank()) vm.toast("No mobile number saved") else Printer.dial(context, v.phone)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LightButton("WhatsApp", Modifier.fillMaxWidth(), icon = Icons.Rounded.Forum, color = Teal) {
                        Printer.whatsappText(context, v.phone, vendorMessage(shop, v))
                    }
                    if (v.saved == null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "This vendor came from your old purchases. Tap the pencil above to save its mobile, GSTIN and address.",
                            color = Faint, fontSize = 12.sp
                        )
                    }
                }
            }
            item(key = "title") { SectionTitle("All bills  •  ${v.bills.size}") }
            if (v.bills.isEmpty()) {
                item(key = "none") {
                    Text(
                        "No purchase recorded from this vendor yet. Tap New purchase above.",
                        Modifier.padding(4.dp), color = Muted, fontSize = 13.5.sp
                    )
                }
            }
            items(v.bills, key = { it.id }) { b ->
                PurchaseHistoryCard(
                    b = b,
                    items = itemsByBill[b.id],
                    onOpen = { vm.open(Screen.PurchaseEdit(b.id)) },
                    onPay = { payFor = b }
                )
            }
        }
    }

    form?.let { f ->
        VendorDialog(
            initial = f,
            onSave = { edited ->
                saveVendor(vm, savedList, purchases, parts, edited, f.name) { newName ->
                    if (!newName.equals(f.name, true)) vm.back()
                }
            },
            onRemove = if (f.id != 0L) ({ removeVendor(vm, f.id) }) else null,
            onDismiss = { form = null }
        )
    }
    payFor?.let { b ->
        val due = b.due
        InputDialog(
            title = "Pay vendor",
            label = "Amount paid",
            initial = num(Math.round(due * 100) / 100.0),
            message = "${billCode(b)} of ${fmtDate(b.date)}  •  payable ${rs(due)}",
            confirm = "Save",
            onConfirm = { x -> vm.receivePayment(b, x.toNum().coerceAtMost(due)) },
            onDismiss = { payFor = null }
        )
    }
}

@Composable
private fun VStat(label: String, value: String, modifier: Modifier, color: Color = Ink) {
    Column(modifier) {
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PurchaseHistoryCard(b: Bill, items: List<BillItem>?, onOpen: () -> Unit, onPay: () -> Unit) {
    val due = b.due
    val saved = items.orEmpty().sumOf { it.qty * (it.listRate - it.price) }.coerceAtLeast(0.0)
    AppCard(Modifier.fillMaxWidth(), onClick = onOpen, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.LocalShipping, if (due > 0.009) Red else Teal, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(fmtDate(b.date), color = Ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(billCode(b), if (b.ref.isNotBlank()) "Bill ${b.ref}" else "").filter { it.isNotBlank() }.joinToString("  •  "),
                    color = Muted, fontSize = 12.5.sp, maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(rs(b.total), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (due > 0.009) Pill("Pay ${rs(due)}", Red) else Pill("Paid", Green)
            }
        }
        if (!items.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(partsText(items), color = Muted, fontSize = 12.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (saved > 0.009) {
            Spacer(Modifier.height(4.dp))
            Text("Discount saved: ${rs(saved)}", color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (due > 0.009) {
            Spacer(Modifier.height(8.dp))
            LightButton("Pay now", Modifier.fillMaxWidth(), color = Navy, onClick = onPay)
        }
    }
}

// ================= Add / edit vendor =================

@Composable
private fun VendorDialog(
    initial: VendorForm,
    onSave: (VendorForm) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var phone by remember { mutableStateOf(initial.phone) }
    var gstin by remember { mutableStateOf(initial.gstin) }
    var address by remember { mutableStateOf(initial.address) }
    val isNew = initial.id == 0L && initial.name.isBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text(if (isNew) "New vendor" else "Edit vendor", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Every purchase you record under this name is kept together, with its bills and dates.",
                    color = Muted, fontSize = 12.5.sp
                )
                Spacer(Modifier.height(12.dp))
                AppField(name, { name = it }, "Vendor / shop name *")
                Spacer(Modifier.height(10.dp))
                AppField(
                    phone, { phone = it.filter { c -> c.isDigit() }.take(10) }, "Mobile",
                    type = KeyboardType.Phone
                )
                Spacer(Modifier.height(10.dp))
                AppField(
                    gstin, { gstin = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(15) }, "GSTIN (optional)",
                    caps = KeyboardCapitalization.Characters
                )
                Spacer(Modifier.height(10.dp))
                AppField(
                    address, { address = it }, "Address / note (optional)",
                    caps = KeyboardCapitalization.Sentences, singleLine = false
                )
                if (!isNew) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Changing the name also renames this vendor in all its old purchases.",
                        color = Faint, fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(VendorForm(initial.id, name, phone, gstin, address))
                    onDismiss()
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (onRemove != null) {
                    TextButton(onClick = {
                        onRemove()
                        onDismiss()
                    }) { Text("Remove details", color = Red, fontWeight = FontWeight.SemiBold) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
            }
        }
    )
}
