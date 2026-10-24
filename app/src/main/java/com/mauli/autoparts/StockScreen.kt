package com.mauli.autoparts

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.AddShoppingCart
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.WbIncandescent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Icon for a category name (Lights, Brake, Oil...). */
fun categoryIcon(category: String): ImageVector {
    val c = category.lowercase()
    return when {
        "light" in c || "lamp" in c || "bulb" in c || "indicator" in c -> Icons.Rounded.WbIncandescent
        "electric" in c || "wiring" in c || "switch" in c || "horn" in c -> Icons.Rounded.FlashOn
        "batter" in c -> Icons.Rounded.BatteryFull
        "brake" in c || "clutch" in c || "disc" in c -> Icons.Rounded.Album
        "oil" in c || "lube" in c || "coolant" in c -> Icons.Rounded.Opacity
        "tyre" in c || "tire" in c || "tube" in c || "wheel" in c -> Icons.Rounded.DonutLarge
        "engine" in c || "piston" in c || "gear" in c -> Icons.Rounded.Settings
        "filter" in c -> Icons.Rounded.FilterList
        "body" in c || "mirror" in c || "panel" in c -> Icons.Rounded.DirectionsCar
        "accessor" in c -> Icons.Rounded.Star
        "bearing" in c -> Icons.Rounded.RadioButtonChecked
        "cable" in c || "wire" in c -> Icons.Rounded.Timeline
        "suspension" in c || "shock" in c -> Icons.Rounded.UnfoldMore
        else -> Icons.Rounded.Build
    }
}

@Composable
fun StockScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val parts by vm.parts.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var importInfo by remember { mutableStateOf(false) }
    var stockFor by remember { mutableStateOf<Part?>(null) }
    val listState = rememberLazyListState()
    val draft = vm.draft

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCsv(uri)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri)
    }

    val categories = remember(parts) { parts.map { it.category.ifBlank { "General" } }.distinct().sortedBy { it.lowercase() } }
    val filters = remember(categories) { listOf("All", "Low", "Out") + categories }
    val filter = if (vm.stockFilter in filters) vm.stockFilter else "All"
    val counts = remember(parts) {
        val m = HashMap<String, Int>()
        parts.groupingBy { it.category.ifBlank { "General" } }.eachCount().forEach { (k, v) -> m[k] = v }
        m["All"] = parts.size
        m["Low"] = parts.count { it.qty <= it.minQty }
        m["Out"] = parts.count { it.qty <= 0 }
        m
    }
    val shown = remember(parts, query, filter) { filterParts(parts, query, filter) }
    val value = remember(parts) { parts.sumOf { it.qty.coerceAtLeast(0) * it.buyPrice } }
    val inBill = remember(draft) {
        draft.lines.filter { it.partId != null }.groupBy { it.partId }.mapValues { e -> e.value.sumOf { it.qty } }
    }

    // Jump to top when search or filter changes (but not when coming back from a part).
    var lastKey by rememberSaveable { mutableStateOf("$filter|$query") }
    LaunchedEffect(filter, query) {
        val k = "$filter|$query"
        if (k != lastKey) {
            lastKey = k
            listState.scrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Stock",
            subtitle = "${parts.size} parts  •  Value ${rs(value)}",
            actions = {
                HeaderAction(Icons.Rounded.Add, "Add part") { vm.open(Screen.EditPart(0)) }
                Box {
                    HeaderAction(Icons.Rounded.MoreVert, "More") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Import from Excel") },
                            leadingIcon = { Icon(Icons.Rounded.FileUpload, null, tint = Green) },
                            onClick = {
                                menu = false
                                importInfo = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Export to Excel") },
                            leadingIcon = { Icon(Icons.Rounded.FileDownload, null, tint = Navy) },
                            onClick = {
                                menu = false
                                exporter.launch("Mauli_Stock_" + SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date()) + ".csv")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Send order list") },
                            leadingIcon = { Icon(Icons.Rounded.LocalShipping, null, tint = Purple) },
                            onClick = {
                                menu = false
                                if (parts.none { it.qty <= it.minQty }) vm.toast("No parts are running low 👍")
                                else Printer.shareText(context, vm.orderListText())
                            }
                        )
                    }
                }
            },
            bottom = {
                SearchBox(query, { query = it }, "Search name, part no, vehicle, rack…")
                Spacer(Modifier.height(10.dp))
                ChipRow(filters, filter, { vm.stockFilter = it }, onDark = true, label = { "$it (${counts[it] ?: 0})" })
            }
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                parts.isEmpty() -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EmptyState(
                        Icons.Rounded.Category, "No parts yet",
                        "Add parts one by one, or import your full stock from an Excel sheet in one go.",
                        action = "Add first part"
                    ) { vm.open(Screen.EditPart(0)) }
                    LightButton("Import from Excel", icon = Icons.Rounded.FileUpload, color = Green) { importInfo = true }
                }
                shown.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyState(
                        Icons.Rounded.Search, "No parts found",
                        if (query.isBlank()) "Nothing in this list right now." else "Nothing matches \"${query.trim()}\".",
                        action = if (query.isBlank()) null else "Add \"${query.trim()}\" as new part"
                    ) {
                        pendingPartName = query.trim()
                        vm.open(Screen.EditPart(0))
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 14.dp,
                        bottom = if (draft.lines.isNotEmpty()) 100.dp else 36.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shown, key = { it.id }) { p ->
                        PartCard(
                            p = p,
                            inBill = inBill[p.id] ?: 0,
                            onOpen = { vm.open(Screen.EditPart(p.id)) },
                            onStock = { stockFor = p },
                            onBill = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.addToBill(p)
                            }
                        )
                    }
                }
            }
            if (draft.lines.isNotEmpty()) BillBar(vm, draft, Modifier.align(Alignment.BottomCenter))
        }
    }

    stockFor?.let { p -> AddStockDialog(vm, p) { stockFor = null } }
    if (importInfo) {
        ImportDialog(
            onSample = {
                importInfo = false
                shareSample(context)
            },
            onChoose = {
                importInfo = false
                importer.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel"))
            },
            onDismiss = { importInfo = false }
        )
    }
}

/** Quick "stock received" box: quantity + optional new buy price. */
@Composable
fun AddStockDialog(vm: AppViewModel, p: Part, onDismiss: () -> Unit) {
    var qty by remember { mutableStateOf("") }
    var buy by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(200)
        try {
            focus.requestFocus()
        } catch (e: Exception) {
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Add stock", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(p.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Now in stock: ${p.qty} ${p.unit}", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                AppField(
                    qty, { qty = it.filter { c -> c.isDigit() }.take(6) }, "Quantity received",
                    Modifier.focusRequester(focus), type = KeyboardType.Number
                )
                Spacer(Modifier.height(10.dp))
                AppField(
                    buy, { buy = it.filter { c -> c.isDigit() || c == '.' } }, "New buy price (optional)",
                    type = KeyboardType.Decimal, prefix = "₹ ", imeAction = ImeAction.Done
                )
                val q = qty.toIntOrNull() ?: 0
                if (q > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("Stock will become ${p.qty + q} ${p.unit}", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val q = qty.toIntOrNull() ?: 0
                    if (q > 0) {
                        vm.addStock(p, q, buy.toNum().takeIf { it > 0 })
                        onDismiss()
                    } else vm.toast("Enter quantity")
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy)
            ) { Text("Add", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}

@Composable
private fun PartCard(p: Part, inBill: Int, onOpen: () -> Unit, onStock: () -> Unit, onBill: () -> Unit) {
    val color = stockColor(p)
    AppCard(Modifier.fillMaxWidth(), onClick = onOpen, padding = 12.dp) {
        Row {
            IconBadge(categoryIcon(p.category), tagColor(p.category), 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Ink, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val info = listOf(p.partNo, p.brand, p.vehicle).filter { it.isNotBlank() }.joinToString("  •  ")
                if (info.isNotEmpty()) {
                    Text(info, color = Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(rs(p.sellPrice), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (p.mrp > p.sellPrice) {
                    Text("MRP ${rs(p.mrp)}", color = Faint, fontSize = 11.sp, textDecoration = TextDecoration.LineThrough)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    p.qty <= 0 -> "Out of stock"
                    p.qty <= p.minQty -> "Low • ${p.qty} ${p.unit}"
                    else -> "${p.qty} ${p.unit}"
                },
                color = color, fontSize = 12.5.sp, fontWeight = FontWeight.Bold
            )
            if (p.rack.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Place, null, tint = Faint, modifier = Modifier.size(14.dp))
                Text(p.rack, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            SmallBtn(Icons.Rounded.AddBox, "Stock", Navy, NavySoft, onStock)
            Spacer(Modifier.width(8.dp))
            SmallBtn(
                Icons.Rounded.AddShoppingCart,
                if (inBill > 0) "Bill ($inBill)" else "Bill",
                Color.White,
                if (p.qty > 0) Orange else Faint,
                onBill
            )
        }
    }
}

@Composable
private fun SmallBtn(icon: ImageVector, text: String, fg: Color, bg: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Floating bar showing the current bill while you pick parts. */
@Composable
private fun BillBar(vm: AppViewModel, d: Draft, modifier: Modifier) {
    val t = calcTotals(d.lines, d.discount.toNum())
    val count = d.lines.sumOf { it.qty }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .shadow(12.dp, shape, ambientColor = Navy, spotColor = Navy)
            .clip(shape)
            .background(HeaderBrush)
            .clickable { vm.tab(Screen.Billing) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(Icons.Rounded.Receipt, Color.White, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$count item${if (count == 1) "" else "s"} in " + (if (vm.drafts.size > 1) "Bill ${vm.current + 1}" else "bill"),
                color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp
            )
            Text(rs(t.total), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text("View bill", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ImportDialog(onSample: () -> Unit, onChoose: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Import stock from Excel", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("1.  Keep your Excel columns in this order:", color = Ink, fontSize = 14.sp)
                Text(
                    CSV_HEADER.joinToString(",  "),
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(NavySoft)
                        .padding(10.dp),
                    color = Navy, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
                Text("2.  In Excel: File → Save As → CSV", color = Ink, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text("3.  Tap Choose file and pick that CSV", color = Ink, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Parts with the same Part No (or same name) get updated, not repeated. " +
                        "Easiest way: get the sample file, fill it, then import.",
                    color = Muted, fontSize = 12.5.sp
                )
            }
        },
        confirmButton = {
            Button(onClick = onChoose, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                Text("Choose file", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onSample) { Text("Get sample file", color = Navy, fontWeight = FontWeight.SemiBold) }
        }
    )
}

private fun filterParts(parts: List<Part>, query: String, filter: String): List<Part> {
    val base = when (filter) {
        "All" -> parts
        "Low" -> parts.filter { it.qty <= it.minQty }.sortedBy { it.qty }
        "Out" -> parts.filter { it.qty <= 0 }
        else -> parts.filter { it.category.ifBlank { "General" } == filter }
    }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return base
    return base.filter { p ->
        val hay = "${p.name} ${p.partNo} ${p.brand} ${p.vehicle} ${p.category} ${p.rack} ${p.notes}".lowercase()
        words.all { it in hay }
    }
}

/** Makes a sample Excel (CSV) file and opens the share menu (save it / send to WhatsApp / open in Excel). */
private fun shareSample(context: Context) {
    try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "Mauli_Stock_Sample.csv")
        val rows = listOf(
            CSV_HEADER,
            listOf("Indicator Assembly Front", "IND-101", "Lights", "Lumax", "Splendor Plus", "A2", "pcs",
                "120", "180", "200", "10", "3", "18", "8512", "Sai Agencies", ""),
            listOf("Brake Shoe Set", "BS-220", "Brake", "Bosch", "Activa 5G", "B1", "set",
                "160", "240", "260", "6", "2", "28", "8708", "", "")
        )
        file.writeText("\uFEFF" + rows.joinToString("\r\n") { csvLine(it) } + "\r\n")
        Printer.shareFile(context, file, "text/csv", "Save or send sample file")
    } catch (e: Exception) {
    }
}
