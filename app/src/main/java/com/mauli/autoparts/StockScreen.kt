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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Opacity
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
        "battery" in c -> Icons.Rounded.BatteryFull
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
    val reserved = vm.reserved()

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
