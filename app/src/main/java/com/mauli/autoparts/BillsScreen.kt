package com.mauli.autoparts

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BillFilters = listOf("All", "Today", "Udhaar", "Estimates")
private val PaperInk = Color(0xFF111111)
private val PaperShade = Color(0xFFEDEDED)

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
    when {
        b == null -> Column(Modifier.fillMaxSize()) {
            ScreenHeader("Bill", onBack = { vm.back() })
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Navy)
            }
        }
        b.kind == 1 -> PurchaseEditScreen(vm, b.id)
        else -> BillDetails(vm, b, items)
    }
}

@Composable
private fun BillDetails(vm: AppViewModel, b: Bill, items: List<BillItem>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = vm.settings
    val parts by vm.parts.collectAsState()
    val byId = remember(parts) { parts.associateBy { it.id } }
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
                val f = Invoice.pdfFor(context, vm.dao, b.id, s)
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
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 28.dp)
        ) {
            AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
                Row(Modifier.fillMaxWidth()) {
                    ActionTile(Icons.Rounded.Edit, "Edit", Navy, Modifier.weight(1f)) { vm.editBill(b) }
                    ActionTile(Icons.Rounded.Print, "Print", Navy, Modifier.weight(1f)) { printSlip() }
                    ActionTile(Icons.Rounded.PictureAsPdf, "A4 / PDF", Navy, Modifier.weight(1f)) {
                        withPdf { Printer.printPdf(context, it, billCode(b)) }
                    }
                    ActionTile(Icons.Rounded.Forum, "WhatsApp", Navy, Modifier.weight(1f)) {
                        withPdf { Printer.whatsappFile(context, it, b.phone) }
                    }
                    ActionTile(Icons.Rounded.Share, "Share", Navy, Modifier.weight(1f)) {
                        withPdf { Printer.shareFile(context, it) }
                    }
                }
            }

            if (b.isEstimate) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MainButton("Convert to Bill", Modifier.weight(1f), icon = Icons.Rounded.CheckCircle, color = Navy) { askConvert = true }
                    LightButton("Edit", Modifier.weight(1f), icon = Icons.Rounded.Edit, color = Navy) { vm.editBill(b) }
                }
            } else if (due > 0.009) {
                Spacer(Modifier.height(10.dp))
                AppCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Balance due", color = Muted, fontSize = 12.5.sp)
                            Text(rs(due), color = Red, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        if (s.upi.isNotBlank()) {
                            TextButton(onClick = { showQr = true }) { Text("UPI QR", color = Navy, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MainButton("Receive payment", Modifier.weight(1f), color = Navy) { askPay = true }
                        LightButton("Remind", Modifier.weight(0.7f), icon = Icons.Rounded.Forum, color = Navy) { remind() }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            InvoicePaper(b, items, s, byId)

            if (!b.isEstimate) {
                val ps = items.mapNotNull { it.partId }.distinct().mapNotNull { byId[it] }
                if (ps.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    StockNowCard(ps)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showProfit = !showProfit }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (showProfit) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    null, tint = Faint, modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (showProfit) "Profit on this bill: ${rs(b.profit)}" else "Show profit (only for you)",
                    color = if (showProfit) Green else Faint, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
                )
            }
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

/** Current stock of the parts in this bill (shown on screen only, never printed). */
@Composable
private fun StockNowCard(ps: List<Part>) {
    AppCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Text("Current stock of these parts", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Only for you • not printed on the bill", color = Faint, fontSize = 11.5.sp)
        Spacer(Modifier.height(6.dp))
        ps.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f), color = Ink, fontSize = 13.5.sp, maxLines = 1)
                Text("${p.qty} ${p.unit} left", color = stockColor(p), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ================= BUSY-style bill on screen =================

@Composable
private fun PText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 10,
    bold: Boolean = false,
    align: TextAlign? = null
) {
    Text(
        text, modifier, color = PaperInk, fontSize = size.sp, lineHeight = (size + 3).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = FontFamily.SansSerif, textAlign = align
    )
}

@Composable
private fun HRule() {
    Box(Modifier.fillMaxWidth().height(0.8.dp).background(PaperInk))
}

@Composable
private fun VRule() {
    Box(Modifier.width(0.8.dp).fillMaxHeight().background(PaperInk))
}

@Composable
private fun KV(k: String, v: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        PText(k, Modifier.width(76.dp), size = 10)
        PText(": $v", Modifier.weight(1f), size = 10, bold = bold)
    }
}

@Composable
private fun SumRow(k: String, v: String, bold: Boolean = false, size: Int = 10) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        PText(k, Modifier.weight(1f), size = size, bold = bold)
        PText(v, size = size, bold = bold)
    }
}

@Composable
private fun RowScope.HCell(text: String, width: Dp?) {
    val m = if (width != null) Modifier.width(width) else Modifier.weight(1f)
    Box(m.padding(horizontal = 3.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        PText(text, size = 9, bold = true, align = TextAlign.Center)
    }
}

@Composable
private fun BCell(text: String, width: Dp, align: TextAlign, bold: Boolean = false) {
    PText(text, Modifier.width(width).padding(horizontal = 3.dp, vertical = 4.dp), size = 10, bold = bold, align = align)
}

@Composable
private fun RowScope.DescCell(name: String, sub: String) {
    Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 4.dp)) {
        PText(name, size = 10, bold = true)
        if (sub.isNotBlank()) PText(sub, size = 8)
    }
}

@Composable
private fun RowScope.TCell(text: String, bold: Boolean = false) {
    PText(text, Modifier.weight(1f).padding(horizontal = 2.dp, vertical = 3.dp), size = 9, bold = bold, align = TextAlign.Center)
}

/** The bill drawn like a BUSY invoice: black & white, boxed table. */
@Composable
fun InvoicePaper(b: Bill, items: List<BillItem>, s: ShopSettings, parts: Map<Long, Part>) {
    val gst = s.showGst
    val due = if (b.isEstimate) 0.0 else (b.total - b.paid).coerceAtLeast(0.0)
    val shop = s.shopName.ifBlank { "Mauli Auto Parts" }
    val dateFmt = remember { SimpleDateFormat("dd-MM-yyyy", Locale.US) }
    val timeFmt = remember { SimpleDateFormat("hh:mm a", Locale.US) }
    val wSn = 26.dp
    val wQty = 46.dp
    val wPrice = 62.dp
    val wAmt = 70.dp

    Column(
        Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(2.dp))
            .background(Color.White)
            .border(0.8.dp, PaperInk)
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            PText(Printer.docTitle(b, s), Modifier.align(Alignment.Center), 13, bold = true)
            PText("Original Copy", Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), 8)
        }
        HRule()

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PText(shop.uppercase(Locale.US), size = 17, bold = true, align = TextAlign.Center)
            if (s.tagline.isNotBlank()) PText(s.tagline, size = 10, align = TextAlign.Center)
            if (s.address.isNotBlank()) PText(s.address, size = 10, align = TextAlign.Center)
            val contact = listOfNotNull(
                if (s.phone.isNotBlank()) "Phone : ${s.phone}" else null,
                if (gst && s.gstin.isNotBlank()) "GSTIN : ${s.gstin}" else null
            ).joinToString("    ")
            if (contact.isNotBlank()) PText(contact, size = 10, bold = true, align = TextAlign.Center)
        }
        HRule()

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(Modifier.weight(1.15f).padding(6.dp)) {
                PText("Party Details :", size = 9, bold = true)
                PText(b.customer.ifBlank { "Cash" }, size = 12, bold = true)
                if (b.phone.isNotBlank()) PText("Mobile : ${b.phone}", size = 10)
                if (b.vehicleNo.isNotBlank()) PText("Vehicle No. : ${vehicleLabel(b.vehicleNo)}", size = 10)
                if (b.model.isNotBlank()) PText("Model : ${b.model}", size = 10)
                if (b.km.isNotBlank()) PText("KM : ${b.km}", size = 10)
            }
            VRule()
            Column(Modifier.weight(1f).padding(6.dp)) {
                KV(if (b.isEstimate) "Estimate No." else "Invoice No.", billCode(b), bold = true)
                KV("Dated", dateFmt.format(Date(b.date)))
                KV("Time", timeFmt.format(Date(b.date)))
                if (!b.isEstimate) KV("Payment", payLabel(b))
            }
        }
        HRule()

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(PaperShade)) {
            HCell("S.N.", wSn)
            VRule()
            HCell("Description of Goods", null)
            VRule()
            HCell("Qty.", wQty)
            VRule()
            HCell("Price", wPrice)
            VRule()
            HCell("Amount", wAmt)
        }
        HRule()
        items.forEachIndexed { i, it ->
            val part = it.partId?.let { pid -> parts[pid] }
            val sub = listOfNotNull(
                if (it.partNo.isNotBlank()) "Part No: ${it.partNo}" else null,
                if (gst && !part?.hsn.isNullOrBlank()) "HSN: ${part?.hsn}" else null
            ).joinToString("   ")
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                BCell("${i + 1}", wSn, TextAlign.Center)
                VRule()
                DescCell(it.name, sub)
                VRule()
                BCell("${it.qty} ${part?.unit ?: "nos"}", wQty, TextAlign.End)
                VRule()
                BCell(inr2(it.price), wPrice, TextAlign.End)
                VRule()
                BCell(inr2(it.qty * it.price), wAmt, TextAlign.End)
            }
        }
        val filler = ((6 - items.size).coerceAtLeast(0) * 22 + 10).dp
        Row(Modifier.fillMaxWidth().height(filler)) {
            Spacer(Modifier.width(wSn))
            VRule()
            Spacer(Modifier.weight(1f))
            VRule()
            Spacer(Modifier.width(wQty))
            VRule()
            Spacer(Modifier.width(wPrice))
            VRule()
            Spacer(Modifier.width(wAmt))
        }
        HRule()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            BCell("", wSn, TextAlign.Center)
            VRule()
            Box(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 4.dp)) {
                PText("Total", Modifier.align(Alignment.CenterEnd), size = 10, bold = true)
            }
            VRule()
            BCell("${items.sumOf { it.qty }}", wQty, TextAlign.End, bold = true)
            VRule()
            BCell("", wPrice, TextAlign.End)
            VRule()
            BCell(inr2(b.subTotal), wAmt, TextAlign.End, bold = true)
        }
        HRule()

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(Modifier.weight(1.15f).padding(6.dp)) {
                PText("Amount in Words :", size = 9, bold = true)
                PText(amountInWords(b.total), size = 10, bold = true)
                if (b.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    PText("Note : ${b.note}", size = 9)
                }
            }
            VRule()
            Column(Modifier.weight(1f).padding(6.dp)) {
                SumRow("Sub Total", inr2(b.subTotal))
                if (b.discount > 0) SumRow("Less : Discount", inr2(b.discount))
                Spacer(Modifier.height(3.dp))
                HRule()
                Spacer(Modifier.height(3.dp))
                SumRow("Grand Total", "₹ " + inr2(b.total), bold = true, size = 12)
                Spacer(Modifier.height(3.dp))
                HRule()
                Spacer(Modifier.height(2.dp))
                if (!b.isEstimate) {
                    SumRow(if (b.payMode == "Udhaar") "Received" else "Received (${b.payMode})", inr2(b.paid))
                    if (due > 0.009) SumRow("Balance Due", inr2(due), bold = true)
                }
            }
        }

        if (gst) {
            HRule()
            val rows = taxRows(items, b.discount, parts)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(PaperShade)) {
                TCell("HSN/SAC", true)
                VRule()
                TCell("Taxable", true)
                VRule()
                TCell("CGST", true)
                VRule()
                TCell("SGST", true)
                VRule()
                TCell("Total Tax", true)
            }
            HRule()
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    TCell(r.hsn)
                    VRule()
                    TCell(inr2(r.taxable))
                    VRule()
                    TCell("${num(r.rate / 2)}%  ${inr2(r.tax / 2)}")
                    VRule()
                    TCell("${num(r.rate / 2)}%  ${inr2(r.tax / 2)}")
                    VRule()
                    TCell(inr2(r.tax))
                }
            }
            HRule()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                TCell("Total", true)
                VRule()
                TCell(inr2(rows.sumOf { it.taxable }), true)
                VRule()
                TCell(inr2(rows.sumOf { it.tax } / 2), true)
                VRule()
                TCell(inr2(rows.sumOf { it.tax } / 2), true)
                VRule()
                TCell(inr2(rows.sumOf { it.tax }), true)
            }
        }
        HRule()

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(Modifier.weight(1.15f).padding(6.dp)) {
                PText("Terms & Conditions", size = 9, bold = true)
                PText("E.& O.E.", size = 9)
                if (s.footer.isNotBlank()) PText(s.footer, size = 9)
                Spacer(Modifier.height(18.dp))
                PText("Receiver's Signature", size = 9)
            }
            VRule()
            Column(Modifier.weight(1f).padding(6.dp), horizontalAlignment = Alignment.End) {
                PText("for ${shop.uppercase(Locale.US)}", size = 9, bold = true, align = TextAlign.End)
                Spacer(Modifier.height(36.dp))
                PText("Authorised Signatory", size = 9, align = TextAlign.End)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "This is a computer generated invoice",
        Modifier.fillMaxWidth(), color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center
    )
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
                colors = ButtonDefaults.buttonColors(containerColor = Navy)
            ) { Text("Convert", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}
