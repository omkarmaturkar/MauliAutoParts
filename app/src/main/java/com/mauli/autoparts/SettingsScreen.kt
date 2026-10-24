package com.mauli.autoparts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val s = vm.settings
    var showPrinters by remember { mutableStateOf(false) }
    var askRestore by remember { mutableStateOf(false) }
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
    val today = remember { SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date()) }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) showPrinters = true else vm.toast("Please allow 'Nearby devices' to connect the printer")
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.backup(uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restore(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCsv(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri)
    }
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !Printer.hasBtPermission(context)

    fun update(change: (ShopSettings) -> ShopSettings) {
        vm.updateSettings(change(vm.settings))
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", "Shop, printer & backup", onBack = { vm.back() })
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp)
        ) {
            AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(HeaderBrush)
                            .padding(7.dp)
                    ) { MauliLogo(Modifier.fillMaxSize()) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.shopName.ifBlank { "Mauli Auto Parts" }, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Billing & stock app  •  Version $version", color = Muted, fontSize = 12.5.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            SettingsCard("Shop details", "Printed on every bill", Icons.Rounded.Store, Navy) {
                AppField(s.shopName, { v -> update { it.copy(shopName = v) } }, "Shop name")
                Spacer(Modifier.height(10.dp))
                AppField(s.tagline, { v -> update { it.copy(tagline = v) } }, "Tagline (e.g. Two & Four Wheeler Spare Parts)")
                Spacer(Modifier.height(10.dp))
                AppField(
                    s.address, { v -> update { it.copy(address = v) } }, "Shop address",
                    caps = KeyboardCapitalization.Sentences, singleLine = false
                )
                Spacer(Modifier.height(10.dp))
                AppField(
                    s.phone, { v -> update { it.copy(phone = v.filter { c -> c.isDigit() || c in " +,/-" }.take(40)) } },
                    "Shop phone number", type = KeyboardType.Phone
                )
                Spacer(Modifier.height(10.dp))
                AppField(s.upi, { v -> update { it.copy(upi = v.trim()) } }, "UPI ID (e.g. mauli@okaxis)", type = KeyboardType.Email)
                if (s.upi.isNotBlank() && !s.upi.contains("@")) {
                    Text("UPI ID should look like name@bank", Modifier.padding(top = 4.dp), color = Red, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                AppField(
                    s.footer, { v -> update { it.copy(footer = v) } }, "Message at the end of bill",
                    caps = KeyboardCapitalization.Sentences
                )
                Spacer(Modifier.height(8.dp))
                Text("✓ Changes are saved automatically", color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))

            SettingsCard("GST", "For shops with GST registration", Icons.Rounded.Description, Purple) {
                SwitchRow("GST bill (Tax Invoice)", "Shows GSTIN, CGST & SGST on bills. Prices include GST.", s.showGst) { v ->
                    update { it.copy(showGst = v) }
                }
                if (s.showGst) {
                    Spacer(Modifier.height(8.dp))
                    AppField(
                        s.gstin, { v -> update { it.copy(gstin = v.uppercase().filter { c -> c.isLetterOrDigit() }.take(15)) } },
                        "GSTIN (15 characters)", caps = KeyboardCapitalization.Characters
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            SettingsCard("Bill printer", "Bluetooth thermal printer", Icons.Rounded.Print, Orange) {
                val none = s.printerAddress.isBlank()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (none) OrangeSoft else Green.copy(alpha = 0.08f))
                        .clickable {
                            if (needsPermission) btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) else showPrinters = true
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Bluetooth, null, tint = if (none) Orange else Green, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (none) "No printer selected" else s.printerName.ifBlank { "Printer" }, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (none) "Tap to choose your Bluetooth printer" else "Selected  •  tap to change", color = Muted, fontSize = 12.5.sp)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = Faint)
                }
                Spacer(Modifier.height(12.dp))
                Text("Paper size", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Segmented(
                    listOf("58 mm", "80 mm"), if (s.paper == 80) "80 mm" else "58 mm",
                    { v -> update { it.copy(paper = if (v == "80 mm") 80 else 58) } }
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow("Print automatically", "Print the bill as soon as you tap Save", s.autoPrint) { v ->
                    update { it.copy(autoPrint = v) }
                }
                Spacer(Modifier.height(8.dp))
                LightButton("Print test page", Modifier.fillMaxWidth(), icon = Icons.Rounded.Print, color = Orange) {
                    if (needsPermission) btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) else vm.testPrint(context)
                }
                Spacer(Modifier.height(6.dp))
                Text("For A4 bills on any WiFi / USB printer, use \"A4 / PDF\" on the bill screen.", color = Faint, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))

            SettingsCard("Backup & data", "Keep your shop data safe", Icons.Rounded.CloudUpload, Teal) {
                MenuRow(Icons.Rounded.CloudUpload, Teal, "Backup all data", "Save to Google Drive, WhatsApp or phone") {
                    backupLauncher.launch("Mauli_Backup_$today.json")
                }
                MenuRow(Icons.Rounded.SettingsBackupRestore, Navy, "Restore from backup", "Bring back data from a backup file") {
                    askRestore = true
                }
                MenuRow(Icons.Rounded.FileUpload, Green, "Import stock from Excel (CSV)", "Add or update many parts at once") {
                    importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel"))
                }
                MenuRow(Icons.Rounded.FileDownload, Purple, "Export stock to Excel (CSV)", "Full stock list with prices") {
                    exportLauncher.launch("Mauli_Stock_$today.csv")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tip: take a backup every week and keep it in Google Drive. If the phone is lost or changed, " +
                        "Restore brings back all parts, bills and settings.",
                    color = Muted, fontSize = 12.5.sp
                )
            }
            Spacer(Modifier.height(14.dp))

            SettingsCard("Help", "Getting started", Icons.Rounded.Info, Sky) {
                MenuRow(Icons.Rounded.CheckCircle, Green, "Show 'Get started' on Home again", "Step-by-step setup checklist") {
                    context.getSharedPreferences("mauli", Context.MODE_PRIVATE).edit().putBoolean("hideSetup", false).apply()
                    vm.toast("Done ✓ Check the Home screen")
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Works 100% offline  •  Your data stays on this phone",
                Modifier.fillMaxWidth(), color = Faint, fontSize = 12.sp, textAlign = TextAlign.Center
            )
        }
    }

    if (showPrinters) PrinterDialog(vm) { showPrinters = false }
    if (askRestore) {
        ConfirmDialog(
            title = "Restore from backup?",
            text = "This will REPLACE all current parts, bills and settings with the backup file. Take a fresh backup first if unsure.",
            confirm = "Choose file",
            danger = true,
            onConfirm = { restoreLauncher.launch(arrayOf("*/*")) },
            onDismiss = { askRestore = false }
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, fontSize = 12.5.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** Lists paired Bluetooth devices so the shop can pick its bill printer. */
@Composable
private fun PrinterDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val btOn = remember { Printer.bluetoothOn(context) }
    val devices = remember { Printer.pairedDevices(context) }
    fun openBluetoothSettings() {
        try {
            context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: Exception) {
            vm.toast("Open Bluetooth from phone settings")
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Choose bill printer", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when {
                    !btOn -> {
                        Text("Bluetooth is OFF. Turn it on and switch on your printer, then try again.", color = Muted, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        LightButton("Open Bluetooth settings", Modifier.fillMaxWidth(), icon = Icons.Rounded.Bluetooth, color = Navy) {
                            openBluetoothSettings()
                        }
                    }
                    devices.isEmpty() -> {
                        Text(
                            "No paired devices found. Switch on the printer and pair it in phone Bluetooth settings. " +
                                "The PIN is usually 0000 or 1234.",
                            color = Muted, fontSize = 14.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        LightButton("Pair a printer", Modifier.fillMaxWidth(), icon = Icons.Rounded.Bluetooth, color = Navy) {
                            openBluetoothSettings()
                        }
                    }
                    else -> {
                        Text("Select your printer:", color = Muted, fontSize = 13.5.sp)
                        Spacer(Modifier.height(6.dp))
                        devices.forEach { d ->
                            val selected = d.address == vm.settings.printerAddress
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) NavySoft else Color(0xFFF6F8FC))
                                    .clickable {
                                        vm.updateSettings(vm.settings.copy(printerName = d.name, printerAddress = d.address))
                                        vm.toast("Printer selected: ${d.name}")
                                        onDismiss()
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (d.isPrinter) Icons.Rounded.Print else Icons.Rounded.Bluetooth, null,
                                    tint = if (d.isPrinter) Orange else Navy, modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text(d.address, color = Faint, fontSize = 11.5.sp)
                                }
                                if (d.isPrinter) Pill("Printer", Orange)
                                if (selected) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Rounded.CheckCircle, null, tint = Green, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Muted) } }
    )
}
