package com.mauli.autoparts

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

/** The Import stock screen. */
data object ImportPage : Screen(), LeafScreen

/** A, B, C … AA for a column number. */
private fun columnName(i: Int): String {
    var n = i
    val sb = StringBuilder()
    while (n >= 0) {
        sb.insert(0, ('A' + n % 26))
        n = n / 26 - 1
    }
    return sb.toString()
}

/** Makes a sample sheet and opens the share menu, so the shop has something to fill in. */
private fun shareSample(context: Context) {
    try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "Mauli_Stock_Sample.csv")
        val rows = listOf(
            CSV_HEADER,
            listOf(
                "Indicator Assembly Front", "IND-101", "Lights", "Lumax", "Splendor Plus", "A2", "pcs",
                "120", "180", "200", "10", "3", "18", "8512", "Sai Agencies", ""
            ),
            listOf(
                "Brake Shoe Set", "BS-220", "Brake", "Bosch", "Activa 5G", "B1", "set",
                "160", "240", "260", "6", "2", "28", "8708", "", ""
            )
        )
        file.writeText("\uFEFF" + rows.joinToString("\r\n") { csvLine(it) } + "\r\n")
        Printer.shareFile(context, file, "text/csv", "Save or send sample file")
    } catch (e: Exception) {
    }
}

@Composable
fun ImportScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val parts by vm.parts.collectAsState()
    var book by remember { mutableStateOf<WorkbookData?>(null) }
    var sheetIndex by remember { mutableIntStateOf(0) }
    var hasHeading by remember { mutableStateOf(true) }
    var showExtra by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<ImportResult?>(null) }
    val map = remember { mutableStateMapOf<ImportField, Int>() }

    val sheet = book?.sheets?.getOrNull(sheetIndex)
    val rows = sheet?.rows.orEmpty()
    val width = remember(rows) { rows.take(50).maxOfOrNull { it.size } ?: 0 }
    val headings = remember(rows, hasHeading) {
        val first = rows.firstOrNull().orEmpty()
        (0 until width).map { i ->
            val h = if (hasHeading) first.getOrElse(i) { "" }.trim() else ""
            h.ifBlank { "Column ${columnName(i)}" }
        }
    }
    val samples = remember(rows, hasHeading, width) {
        val body = if (hasHeading) rows.drop(1) else rows
        (0 until width).map { i -> body.firstOrNull { it.getOrElse(i) { "" }.isNotBlank() }?.getOrElse(i) { "" }.orEmpty() }
    }

    fun loadFile(uri: android.net.Uri) {
        busy = "read"
        problem = ""
        done = null
        scope.launch {
            try {
                val data = ExcelImport.read(context, uri)
                val first = data.sheets.firstOrNull()?.rows?.firstOrNull().orEmpty()
                hasHeading = ExcelImport.looksLikeHeading(first)
                map.clear()
                if (hasHeading) ExcelImport.detect(first).forEach { (f, c) -> map[f] = c }
                sheetIndex = 0
                book = data
                if (map.isEmpty()) vm.toast("Choose which column is which, below")
            } catch (e: Exception) {
                problem = e.message ?: "Could not read that file"
                book = null
            }
            busy = ""
        }
    }

    fun reDetect() {
        val first = rows.firstOrNull().orEmpty()
        map.clear()
        if (hasHeading) ExcelImport.detect(first).forEach { (f, c) -> map[f] = c }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFile(uri)
    }

    val parsed = remember(rows, hasHeading, map.toMap()) {
        if (rows.isEmpty() || map.isEmpty()) emptyList() else ExcelImport.parseRows(rows, map.toMap(), hasHeading)
    }
    val counts = remember(parsed, parts) {
        val byNo = HashMap<String, Part>()
        val byName = HashMap<String, Part>()
        parts.forEach { p ->
            if (p.partNo.isNotBlank()) byNo[p.partNo.trim().lowercase()] = p
            if (p.name.isNotBlank()) byName[p.name.trim().lowercase()] = p
        }
        var fresh = 0
        var change = 0
        parsed.forEach { r ->
            if (ExcelImport.matchExisting(byNo, byName, r) == null) fresh++ else change++
        }
        fresh to change
    }
    val dataRows = if (hasHeading) (rows.size - 1).coerceAtLeast(0) else rows.size
    val skipped = (dataRows - parsed.size).coerceAtLeast(0)
    val ready = parsed.isNotEmpty() && (map.containsKey(ImportField.Name) || map.containsKey(ImportField.PartNo))

    fun runImport() {
        busy = "save"
        scope.launch {
            try {
                done = ExcelImport.apply(context, parsed)
            } catch (e: Exception) {
                problem = e.message ?: "Could not save"
            }
            busy = ""
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Import stock",
            subtitle = "From Excel (.xlsx) or CSV  •  any column order",
            onBack = { vm.back() }
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            val finished = done
            when {
                finished != null -> {
                    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.CheckCircle, Green, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Import finished", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "${finished.added} new parts added  •  ${finished.updated} updated",
                                    color = Muted, fontSize = 13.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "In Billing, type the part number in the search box and the part comes up with its name and price. " +
                                "If anything came in wrong, open the part from Stock and correct it.",
                            color = Muted, fontSize = 13.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        MainButton("Open Stock", Modifier.fillMaxWidth(), icon = Icons.Rounded.Inventory2, color = Navy) {
                            vm.stockFilter = "All"
                            vm.tab(Screen.Stock)
                        }
                        Spacer(Modifier.height(10.dp))
                        LightButton("Import another file", Modifier.fillMaxWidth(), icon = Icons.Rounded.FileUpload, color = Teal) {
                            book = null
                            done = null
                            map.clear()
                        }
                    }
                }

                busy == "read" || busy == "save" -> {
                    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Navy)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                if (busy == "read") "Reading the file…" else "Saving parts…",
                                color = Muted, fontSize = 14.sp
                            )
                        }
                    }
                }

                book == null -> {
                    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.Folder, Teal, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("Choose your file", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Your whole parts list can come in at once. The app reads Excel (.xlsx) and CSV files, " +
                                "and works out which column is the part number, the name and the price. " +
                                "You can correct any column before anything is saved.",
                            color = Muted, fontSize = 13.5.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        MainButton("Choose Excel or CSV file", Modifier.fillMaxWidth(), icon = Icons.Rounded.FileUpload, color = Teal) {
                            picker.launch(arrayOf("*/*"))
                        }
                        Spacer(Modifier.height(10.dp))
                        LightButton("Get a sample file", Modifier.fillMaxWidth(), icon = Icons.Rounded.Download, color = Navy) {
                            shareSample(context)
                        }
                        if (problem.isNotBlank()) {
                            Spacer(Modifier.height(14.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Red.copy(alpha = 0.08f))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(Icons.Rounded.Warning, null, tint = Red, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(problem, color = Red, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                        Text("Old .xls file?", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Open it in Excel and use Save As → Excel Workbook (.xlsx), or Save As → CSV. Then import that file here.",
                            color = Muted, fontSize = 13.sp
                        )
                    }
                }

                else -> {
                    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.Tune, Navy, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Which column is which", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("$dataRows rows  •  $width columns", color = Muted, fontSize = 12.5.sp)
                            }
                        }
                        val sheets = book?.sheets.orEmpty()
                        if (sheets.size > 1) {
                            Spacer(Modifier.height(12.dp))
                            ChipRow(sheets.map { it.name }, sheets[sheetIndex].name, { picked ->
                                sheetIndex = sheets.indexOfFirst { it.name == picked }.coerceAtLeast(0)
                                reDetect()
                            })
                        }
                        Spacer(Modifier.height(12.dp))
                        SwitchRow("First row is the heading", "Turn off if the sheet starts straight with parts", hasHeading) {
                            hasHeading = it
                            reDetect()
                        }
                        Spacer(Modifier.height(8.dp))
                        MainFields.forEach { f ->
                            ColumnPicker(f, map[f], headings, samples) { c ->
                                if (c == null) map.remove(f) else map[f] = c
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            if (showExtra) "Hide other details" else "Other details (brand, rack, qty, GST…)",
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showExtra = !showExtra }
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                            color = Navy, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        if (showExtra) {
                            ExtraFields.forEach { f ->
                                ColumnPicker(f, map[f], headings, samples) { c ->
                                    if (c == null) map.remove(f) else map[f] = c
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))

                    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                        Text("Before saving", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        InfoRow("New parts to add", "${counts.first}", bold = true, color = Green)
                        InfoRow("Parts already in stock (will update)", "${counts.second}", color = Navy)
                        if (skipped > 0) InfoRow("Rows with no name or number (skipped)", "$skipped", color = Amber)
                        if (!ready) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Choose at least the part name or the part number column above.",
                                color = Red, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (parsed.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text("First few parts as they will be saved", color = Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            parsed.take(4).forEach { r ->
                                val p = ExcelImport.toPart(r, null)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFF6F8FC))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            p.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            listOf(p.partNo, p.brand, if (p.qty != 0) "Qty ${p.qty}" else "")
                                                .filter { it.isNotBlank() }.joinToString("  •  ").ifBlank { "No part number" },
                                            color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(rs(p.sellPrice), color = Navy, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Parts already in stock keep their quantity, rack and other details. " +
                                "Only what is in this sheet is changed.",
                            color = Faint, fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    LightButton("Choose a different file", Modifier.fillMaxWidth(), icon = Icons.Rounded.Folder, color = Muted) {
                        picker.launch(arrayOf("*/*"))
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
        if (book != null && done == null && busy.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${parsed.size} parts ready", color = Muted, fontSize = 12.sp)
                    Text(
                        "${counts.first} new  •  ${counts.second} update",
                        color = Navy, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1
                    )
                }
                MainButton("Import now", icon = Icons.Rounded.FileUpload, color = Orange, enabled = ready) { runImport() }
            }
        }
    }
}

/** One part detail and the sheet column it takes its value from. */
@Composable
private fun ColumnPicker(
    field: ImportField,
    selected: Int?,
    headings: List<String>,
    samples: List<String>,
    onPick: (Int?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val chosen = selected != null
    Column(Modifier.fillMaxWidth()) {
        Text(field.label, color = Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (chosen) Green.copy(alpha = 0.06f) else Color.White)
                    .border(1.dp, if (chosen) Green.copy(alpha = 0.5f) else Line, RoundedCornerShape(12.dp))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (selected == null) "Not in my sheet"
                        else "${columnName(selected)} — ${headings.getOrElse(selected) { "" }}",
                        color = if (chosen) Ink else Faint, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (selected != null) {
                        val s = samples.getOrElse(selected) { "" }
                        if (s.isNotBlank()) {
                            Text("e.g. $s", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Icon(Icons.Rounded.ArrowDropDown, null, tint = Muted)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                DropdownMenuItem(
                    text = { Text("Not in my sheet", color = Muted) },
                    onClick = {
                        onPick(null)
                        open = false
                    }
                )
                headings.forEachIndexed { i, h ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    "${columnName(i)} — $h", color = Ink, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                val s = samples.getOrElse(i) { "" }
                                if (s.isNotBlank()) {
                                    Text(s, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                        onClick = {
                            onPick(i)
                            open = false
                        }
                    )
                }
            }
        }
    }
}
