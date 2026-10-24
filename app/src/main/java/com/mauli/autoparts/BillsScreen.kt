package com.mauli.autoparts

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Payment
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BillFilters = listOf("All", "Today", "Udhaar", "Estimates")

private fun filterBills(bills: List<Bill>, query: String, filter: String): List<Bill> {
    val today = dayStart()
    val base = when (filter) {
        "Today" -> bills.filter { !it.isEstimate && it.date >= today }
        "Udhaar" -> bills.filter { it.due > 0.009 }
        "Estimates" -> bills.filter { it.isEstimate }
        else -> bills
    }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return base
    return base.filter { b ->
        val hay = "${b.customer} ${b.phone} ${b.vehicleNo} ${b.model} ${billCode(b)} ${b.note}".lowercase()
        words.all { it in hay }
    }
}

// ================= Bills list =================

@Composable
fun BillsScreen(vm: AppViewModel) {
    val bills by vm.bills.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val filter = if (vm.billFilter in BillFilters) vm.billFilter else "All"
    val shown = remember(bills, query, filter) { filterBills(bills, query, filter) }
    val listState = rememberLazyListState()
    val sum = when (filter) {
        "Udhaar" -> shown.sumOf { it.due }
        "Estimates" -> shown.sumOf { it.total }
        else -> shown.filter { !it.isEstimate }.sumOf { it.total }
    }
    val grouped = remember(shown) {
        val f = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
        val today = dayStart()
        val yday = dayStart(1)
        shown.groupBy {
            when {
                it.date >= today -> "Today"
                it.date >= yday -> "Yesterday"
                else -> f.format(Date(it.date))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Bills",
            subtitle = when (filter) {
                "Udhaar" -> "${shown.size} bills  •  To collect ${rs(sum)}"
                "Estimates" -> "${shown.size} estimates  •  ${rs(sum)}"
                else -> "${shown.size} bills  •  ${rs(sum)}"
            },
            actions = { HeaderAction(Icons.Rounded.Add, "New bill") { startNewBill(vm) } },
            bottom = {
                SearchBox(query, { query = it }, "Search name, vehicle no, mobile, bill no…")
                Spacer(Modifier.height(10.dp))
                ChipRow(BillFilters, filter, { vm.billFilter = it }, onDark = true)
            }
        )
        if (shown.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                EmptyState(
                    Icons.Rounded.Receipt,
                    when {
                        query.isNotBlank() -> "No bills found"
                        filter == "Udhaar" -> "No udhaar pending 🎉"
                        filter == "Estimates" -> "No estimates yet"
                        filter == "Today" -> "No bills today yet"
                        else -> "No bills yet"
                    },
                    if (query.isNotBlank()) "Try another name, vehicle number or mobile." else "Bills you save will show here.",
                    action = if (bills.isEmpty()) "Make first bill" else null
                ) { startNewBill(vm) }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 36.dp)
            ) {
                grouped.forEach { (day, list) ->
                    item(key = "day-$day") { DayHeader(day, list, filter == "Udhaar") }
                    items(list, key = { it.id }) { b ->
                        BillRow(b, Modifier.padding(vertical = 5.dp)) { vm.open(Screen.BillView(b.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: String, list: List<Bill>, udhaar: Boolean) {
    val real = list.filter { !it.isEstimate }
    val sum = when {
        udhaar -> list.sumOf { it.due }
        real.isEmpty() -> list.sumOf { it.total }
        else -> real.sumOf { it.total }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(day, Modifier.weight(1f), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            "${list.size} bill${if (list.size == 1) "" else "s"}  •  ${rs(sum)}",
            color = Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

// ================= Bill details =================

@Composable
fun BillViewScreen(vm: AppViewModel, id: Long) {
    val bill by remember(id) { vm.dao.billFlow(id) }.collectAsState(initial = null)
    val items by remember(id) { vm.dao.itemsFlow(id) }.collectAsState(initial = emptyList())
    val b = bill
    if (b == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Bill", onBack = { vm.back() })
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Navy)
            }
        }
    } else {
        BillDetails(vm, b, items)
    }
}

@Composable
private fun BillDetails(vm: AppViewModel, b: Bill, items: List<BillItem>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = vm.settings
    val due = b.due
    var askDelete by remember { mutableStateOf(false) }
    var askPay by remember { mutableStateOf(false) }
    var askConvert by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showProfit by remember { mutableStateOf(false) }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.printThermal(context, b.id) else vm.toast("Please allow 'Nearby devices' to print")
    }

    fun printSlip() {
        when {
            s.printerAddress.isBlank() -> {
                vm.toast("First select your Bluetooth printer")
                vm.open(Screen.Settings)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Printer.hasBtPermission(context) ->
                btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else -> vm.printThermal(context, b.id)
        }
    }

    fun withPdf(action: (File) -> Unit) {
        scope.launch {
            try {
                val f = Printer.pdfFor(context, vm.dao, b.id, s)
                if (f != null) action(f) else vm.toast("Bill not found")
            } catch (e: Exception) {
                vm.toast("Could not make PDF")
            }
        }
    }

    fun copyToNewBill() {
        vm.newDraft()
        val base = System.nanoTime()
        vm.editDraft { d ->
            d.copy(
                lines = items.mapIndexed { i, l -> l.copy(id = base + i, billId = 0) },
                customer = b.customer, phone = b.phone, vehicleNo = b.vehicleNo, model = b.model
            )
        }
        vm.tab(Screen.Billing)
        vm.toast("Copied to a new bill")
    }

    fun remind() {
        val msg = buildString {
            append("Namaskar")
            if (b.customer.isNotBlank()) append(" ").append(b.customer)
            append(",\nYour pending amount at *").append(s.shopName).append("* is *").append(rs(due)).append("*")
            append(" (").append(billCode(b)).append(", ").append(fmtDate(b.date)).append(").")
            if (s.upi.isNotBlank()) append("\nPay by UPI: ").append(s.upi)
            append("\nThank you 🙏")
        }
        Printer.whatsappText(context, b.phone, msg)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = billCode(b),
            subtitle = (if (b.isEstimate) "Estimate  •  " else "") + fmtDateTime(b.date),
            onBack = { vm.back() },
            actions = {
                HeaderAction(Icons.Rounded.ContentCopy, "Copy to new bill") { copyToNewBill() }
                HeaderAction(Icons.Rounded.Delete, "Delete") { askDelete = true }
            }
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp)
        ) {
            // Status
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (b.isEstimate) "Estimate total" else "Bill total", color = Muted, fontSize = 13.sp)
                        Text(rs(b.total), color = Ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    when {
                        b.isEstimate -> Pill("ESTIMATE", Purple, filled = true)
                        due > 0.009 -> Pill("DUE", Red, filled = true)
                        else -> Pill("PAID", Green, filled = true)
                    }
                }
                if (!b.isEstimate) {
                    val frac = if (b.total > 0) (b.paid / b.total).toFloat().coerceIn(0f, 1f) else 1f
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Red.copy(alpha = 0.15f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(frac)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .background(Green)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text(
                            "Paid ${rs(b.paid)}" + (if (b.payMode != "Udhaar") " by ${b.payMode}" else ""),
                            Modifier.weight(1f), color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                        if (due > 0.009) Text("Due ${rs(due)}", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showProfit = !showProfit }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (showProfit) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        null, tint = Faint, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (showProfit) "Profit on this bill: ${rs(b.profit)}" else "Show profit",
                        color = if (showProfit) Green else Faint, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // Print & share
            AppCard(Modifier.fillMaxWidth(), padding = 6.dp) {
                Row(Modifier.fillMaxWidth()) {
                    ActionTile(Icons.Rounded.Print, "Print", Navy, Modifier.weight(1f)) { printSlip() }
                    ActionTile(Icons.Rounded.PictureAsPdf, "A4 / PDF", Red, Modifier.weight(1f)) {
                        withPdf { Printer.printPdf(context, it, billCode(b)) }
                    }
                    ActionTile(Icons.Rounded.Forum, "WhatsApp", Green, Modifier.weight(1f)) {
                        withPdf { Printer.whatsappFile(context, it, b.phone) }
                    }
                    ActionTile(Icons.Rounded.Share, "Share", Purple, Modifier.weight(1f)) {
                        Printer.shareText(context, Printer.billText(b, items, s))
                    }
                }
            }
            // Udhaar
            if (due > 0.009) {
                Spacer(Modifier.height(12.dp))
                AppCard(Modifier.fillMaxWidth(), color = Color(0xFFFFF7F7), padding = 14.dp) {
                    Text("Udhaar: ${rs(due)} to collect", color = Red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MainButton("Receive", Modifier.weight(1f), icon = Icons.Rounded.Payment, color = Green) { askPay = true }
                        LightButton("Remind", Modifier.weight(1f), icon = Icons.Rounded.Forum, color = Green) { remind() }
                    }
                    if (s.upi.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        LightButton("Show UPI QR for ${rs(due)}", Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCode, color = Navy) {
                            showQr = true
                        }
                    }
                }
            }
            // Estimate
            if (b.isEstimate) {
                Spacer(Modifier.height(12.dp))
                MainButton("Convert to bill", Modifier.fillMaxWidth(), icon = Icons.Rounded.CheckCircle, color = Orange) { askConvert = true }
                Spacer(Modifier.height(8.dp))
                LightButton("Edit in billing", Modifier.fillMaxWidth(), icon = Icons.Rounded.Edit, color = Purple) { copyToNewBill() }
            }
            Spacer(Modifier.height(12.dp))
            ReceiptCard(b, items, s) { Printer.dial(context, b.phone) }
        }
    }

    if (askDelete) {
        ConfirmDialog(
            title = if (b.isEstimate) "Delete this estimate?" else "Delete this bill?",
            text = if (b.isEstimate) "This estimate will be removed." else "This bill will be removed and its parts will go back into stock.",
            confirm = "Delete",
            danger = true,
            onConfirm = { vm.deleteBill(b) { vm.back() } },
            onDismiss = { askDelete = false }
        )
    }
    if (askPay) {
        InputDialog(
            title = "Receive payment",
            label = "Amount received",
            initial = num(Math.round(due * 100) / 100.0),
            message = "${b.customer.ifBlank { "Customer" }} has to pay ${rs(due)}",
            confirm = "Save",
            onConfirm = { v -> vm.receivePayment(b, v.toNum().coerceAtMost(due)) },
            onDismiss = { askPay = false }
        )
    }
    if (askConvert) ConvertDialog(onConvert = { vm.convertEstimate(b, it) }, onDismiss = { askConvert = false })
    if (showQr) UpiQrDialog(s, due, billCode(b)) { showQr = false }
}

/** The bill shown like a real printed receipt. */
@Composable
private fun ReceiptCard(b: Bill, items: List<BillItem>, s: ShopSettings, onCall: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Text(s.shopName, Modifier.fillMaxWidth(), color = Navy, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        if (s.address.isNotBlank()) {
            Text(s.address, Modifier.fillMaxWidth(), color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        if (s.phone.isNotBlank()) {
            Text("Ph: ${s.phone}", Modifier.fillMaxWidth(), color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            Printer.docTitle(b, s), Modifier.fillMaxWidth(), color = Ink, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp, textAlign = TextAlign.Center
        )
        Dashed()
        if (b.customer.isNotBlank() || b.phone.isNotBlank() || b.vehicleNo.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.customer.ifBlank { "Walk-in customer" }, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (b.vehicleNo.isNotBlank()) {
                        Text(
                            vehicleLabel(b.vehicleNo) + (if (b.model.isNotBlank()) "  •  ${b.model}" else ""),
                            color = Muted, fontSize = 13.sp
                        )
                    }
                    if (b.km.isNotBlank()) Text("${b.km} km", color = Muted, fontSize = 12.5.sp)
                    if (b.phone.isNotBlank()) Text(b.phone, color = Muted, fontSize = 13.sp)
                }
                if (b.phone.isNotBlank()) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Green.copy(alpha = 0.12f))
                            .clickable(onClick = onCall),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Call, "Call", tint = Green, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Dashed()
        }
        items.forEachIndexed { i, line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${i + 1}. ${line.name}", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${line.qty} × ${rs(line.price)}" + (if (line.partNo.isNotBlank()) "   •   ${line.partNo}" else ""),
                        color = Muted, fontSize = 12.5.sp
                    )
                }
                Text(rs(line.qty * line.price), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Dashed()
        InfoRow("Sub total", rs(b.subTotal))
        if (b.discount > 0) InfoRow("Discount", "- " + rs(b.discount), color = Green)
        if (s.showGst) {
            val t = calcTotals(items, b.discount)
            InfoRow("Taxable value", rs(t.taxable))
            InfoRow("CGST", rs(t.tax / 2))
            InfoRow("SGST", rs(t.tax / 2))
        }
        InfoRow("Total", rs(b.total), bold = true, color = Navy, size = 17)
        if (!b.isEstimate) {
            InfoRow("Paid", rs(b.paid))
            if (b.due > 0.009) InfoRow("Balance due", rs(b.due), bold = true, color = Red)
        }
        Spacer(Modifier.height(8.dp))
        Text(amountInWords(b.total), color = Muted, fontSize = 12.sp, fontStyle = FontStyle.Italic)
        if (b.note.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Note: ${b.note}", color = Ink, fontSize = 13.sp)
        }
        if (s.footer.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(s.footer, Modifier.fillMaxWidth(), color = Navy, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Dashed() {
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(1.dp)
    ) {
        drawLine(
            color = Faint,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
        )
    }
}

@Composable
private fun ConvertDialog(onConvert: (String) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf("Cash") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Convert to bill", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("How did the customer pay? Stock will reduce now.", color = Muted, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                Segmented(listOf("Cash", "UPI", "Card", "Udhaar"), mode, { mode = it })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConvert(mode)
                    onDismiss()
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange)
            ) { Text("Convert", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
