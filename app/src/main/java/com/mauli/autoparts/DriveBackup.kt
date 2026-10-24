package com.mauli.autoparts

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One backup file in Google Drive. */
class DriveFile(val id: String, val name: String, val time: Long, val size: Long)

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

object DriveBackup {
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val FOLDER = "Mauli Auto Parts Backup"
    private const val KEEP = 15
    private const val WORK = "drive_daily_backup"

    private class Resp(val code: Int, val body: ByteArray, val location: String?)
    private class BackupData(val parts: List<Part>, val bills: List<Bill>, val items: List<BillItem>, val settings: ShopSettings?)

    fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
        .build()

    // ---------- Saved status ----------

    private fun prefs(context: Context) = context.getSharedPreferences("mauli", Context.MODE_PRIVATE)

    fun email(context: Context): String = prefs(context).getString("driveEmail", "") ?: ""
    fun lastBackup(context: Context): Long = prefs(context).getLong("driveLast", 0L)
    fun lastError(context: Context): String = prefs(context).getString("driveError", "") ?: ""
    fun autoOn(context: Context): Boolean = prefs(context).getBoolean("driveAuto", false)

    fun saveConnected(context: Context, email: String) {
        prefs(context).edit().putString("driveEmail", email).putString("driveError", "").apply()
    }

    fun disconnect(context: Context) {
        prefs(context).edit().remove("driveEmail").putBoolean("driveAuto", false).apply()
        schedule(context, false)
    }

    fun setAuto(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("driveAuto", on).apply()
        schedule(context, on)
    }

    fun ensureScheduled(context: Context) {
        if (autoOn(context) && email(context).isNotBlank()) schedule(context, true)
    }

    private fun schedule(context: Context, on: Boolean) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (on) {
            val work = PeriodicWorkRequestBuilder<DriveBackupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, work)
        } else {
            wm.cancelUniqueWork(WORK)
        }
    }

    // ---------- Google sign-in ----------

    /** Fresh access token without showing anything. Null = the user must connect again. */
    suspend fun silentToken(context: Context): String? {
        val r = Identity.getAuthorizationClient(context.applicationContext).authorize(request()).awaitTask()
        return if (r.hasResolution()) null else r.accessToken
    }

    /** SHA-1 of this app's signing key (needed once in Google Cloud). */
    fun signingSha1(context: Context): String = try {
        val pm = context.packageManager
        val sig: Signature? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        if (sig == null) "" else MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
            .joinToString(":") { String.format("%02X", it) }
    } catch (e: Exception) {
        ""
    }

    fun problem(e: Exception): String = when {
        e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR -> "Google Drive is not set up for this app yet"
        e is ApiException -> "Google sign-in problem (code ${e.statusCode})"
        e is UnknownHostException || e is SocketTimeoutException -> "No internet connection"
        else -> (e.message ?: "Something went wrong").take(150)
    }

    // ---------- Backup & restore ----------

    private suspend fun backupBytes(context: Context): ByteArray {
        val dao = AppDb.get(context).dao()
        val settings = try {
            prefs(context).getString("settings", null)?.let { settingsOf(JSONObject(it)) } ?: ShopSettings()
        } catch (e: Exception) {
            ShopSettings()
        }
        val parts = dao.allParts()
        val bills = dao.allBills()
        val items = dao.allItems()
        return withContext(Dispatchers.Default) {
            val json = JSONObject().put("app", "MauliAutoParts").put("version", 1).put("time", now())
                .put("settings", settings.toJson())
                .put("parts", JSONArray(parts.map { it.toJson() }))
                .put("bills", JSONArray(bills.map { it.toJson() }))
                .put("items", JSONArray(items.map { it.toJson() }))
            gzip(json.toString())
        }
    }

    private fun parse(bytes: ByteArray): BackupData {
        val o = JSONObject(unzip(bytes))
        if (!o.has("parts")) throw IOException("This is not a Mauli backup file")
        return BackupData(
            parts = o.optJSONArray("parts").mapObjects { partOf(it) },
            bills = o.optJSONArray("bills").mapObjects { billOf(it) },
            items = o.optJSONArray("items").mapObjects { itemOf(it) },
            settings = o.optJSONObject("settings")?.let { settingsOf(it) }
        )
    }

    /** Replaces all data on this device with a backup (.json or .json.gz). */
    suspend fun restoreBytes(context: Context, vm: AppViewModel, bytes: ByteArray): String {
        val data = withContext(Dispatchers.Default) { parse(bytes) }
        val db = AppDb.get(context)
        db.withTransaction {
            val dao = db.dao()
            dao.clearItems()
            dao.clearBills()
            dao.clearParts()
            dao.putParts(data.parts)
            dao.putBills(data.bills)
            dao.putItems(data.items)
        }
        data.settings?.let { b ->
            val cur = vm.settings
            vm.updateSettings(
                b.copy(
                    printerName = cur.printerName.ifBlank { b.printerName },
                    printerAddress = cur.printerAddress.ifBlank { b.printerAddress }
                )
            )
        }
        return "Restored ${data.parts.size} parts & ${data.bills.size} bills ✓"
    }

    suspend fun restoreFromUri(context: Context, vm: AppViewModel, uri: Uri): String {
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: throw IOException("Cannot read the file")
        return restoreBytes(context, vm, bytes)
    }

    /** Makes a backup and uploads it to Google Drive. Returns null if OK, otherwise the problem. */
    suspend fun backupNow(context: Context): String? {
        val app = context.applicationContext
        return try {
            val token = silentToken(app) ?: throw IOException("Please connect Google Drive again")
            val data = backupBytes(app)
            withContext(Dispatchers.IO) {
                val folder = folderId(token)
                val name = "Mauli_Backup_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".json.gz"
                upload(token, folder, name, data)
                listIn(token, folder).drop(KEEP).forEach { old ->
                    try {
                        call("DELETE", "$API/files/${old.id}", token)
                    } catch (e: Exception) {
                    }
                }
            }
            prefs(app).edit().putLong("driveLast", now()).putString("driveError", "").apply()
            null
        } catch (e: Exception) {
            val msg = problem(e)
            prefs(app).edit().putString("driveError", msg).apply()
            msg
        }
    }

    suspend fun listBackups(context: Context): List<DriveFile> {
        val token = silentToken(context) ?: throw IOException("Please connect Google Drive again")
        return withContext(Dispatchers.IO) { listIn(token, folderId(token)) }
    }

    suspend fun restoreFromDrive(context: Context, vm: AppViewModel, file: DriveFile): String {
        val token = silentToken(context) ?: throw IOException("Please connect Google Drive again")
        val bytes = withContext(Dispatchers.IO) {
            val r = call("GET", "$API/files/${file.id}?alt=media", token)
            ok(r)
            r.body
        }
        return restoreBytes(context, vm, bytes)
    }

    suspend fun accountEmail(token: String): String = withContext(Dispatchers.IO) {
        val r = call("GET", "$API/about?fields=" + enc("user(emailAddress)"), token)
        ok(r)
        JSONObject(String(r.body, Charsets.UTF_8)).optJSONObject("user")?.optString("emailAddress").orEmpty()
    }

    // ---------- Google Drive web API ----------

    private fun call(method: String, url: String, token: String, body: ByteArray? = null, type: String? = null): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                if (type != null) c.setRequestProperty("Content-Type", type)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Resp(code, bytes, c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }

    private fun ok(r: Resp) {
        if (r.code !in 200..299) {
            throw IOException("Google Drive error ${r.code}: " + String(r.body, Charsets.UTF_8).take(150))
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun folderId(token: String): String {
        val q = enc("name = '$FOLDER' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
        val r = call("GET", "$API/files?q=$q&fields=" + enc("files(id)"), token)
        ok(r)
        val found = JSONObject(String(r.body, Charsets.UTF_8)).optJSONArray("files")
        if (found != null && found.length() > 0) return found.getJSONObject(0).getString("id")
        val meta = JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder")
        val c = call("POST", "$API/files?fields=id", token, meta.toString().toByteArray(), "application/json; charset=UTF-8")
        ok(c)
        return JSONObject(String(c.body, Charsets.UTF_8)).getString("id")
    }

    private fun upload(token: String, folder: String, name: String, data: ByteArray) {
        val meta = JSONObject().put("name", name).put("mimeType", "application/gzip").put("parents", JSONArray().put(folder))
        val start = call("POST", "$UPLOAD/files?uploadType=resumable", token, meta.toString().toByteArray(), "application/json; charset=UTF-8")
        ok(start)
        val session = start.location ?: throw IOException("Google Drive did not accept the upload")
        ok(call("PUT", session, token, data, "application/gzip"))
    }

    private fun listIn(token: String, folder: String): List<DriveFile> {
        val q = enc("'$folder' in parents and trashed = false")
        val url = "$API/files?q=$q&orderBy=" + enc("createdTime desc") + "&pageSize=100&fields=" + enc("files(id,name,createdTime,size)")
        val r = call("GET", url, token)
        ok(r)
        val arr = JSONObject(String(r.body, Charsets.UTF_8)).optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            DriveFile(f.getString("id"), f.optString("name"), parseTime(f.optString("createdTime")), f.optString("size").toLongOrNull() ?: 0L)
        }
    }

    private fun parseTime(s: String): Long = try {
        Instant.parse(s).toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    private fun gzip(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    private fun unzip(bytes: ByteArray): String =
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        } else {
            bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
}

/** Runs once a day in the background and uploads a fresh backup. */
class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (DriveBackup.email(applicationContext).isBlank()) return Result.success()
        val error = DriveBackup.backupNow(applicationContext) ?: return Result.success()
        val retry = runAttemptCount < 3 && !error.contains("connect", true) && !error.contains("set up", true)
        return if (retry) Result.retry() else Result.success()
    }
}

// ================= Settings card =================

@Composable
fun DriveBackupCard(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(DriveBackup.email(context)) }
    var last by remember { mutableLongStateOf(DriveBackup.lastBackup(context)) }
    var error by remember { mutableStateOf(DriveBackup.lastError(context)) }
    var auto by remember { mutableStateOf(DriveBackup.autoOn(context)) }
    var busy by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<DriveFile>?>(null) }
    var pick by remember { mutableStateOf<DriveFile?>(null) }
    var showSetup by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { DriveBackup.ensureScheduled(context) }

    fun refresh() {
        email = DriveBackup.email(context)
        last = DriveBackup.lastBackup(context)
        error = DriveBackup.lastError(context)
        auto = DriveBackup.autoOn(context)
    }

    fun backupNow() {
        busy = "backup"
        scope.launch {
            val err = DriveBackup.backupNow(context)
            busy = ""
            refresh()
            vm.toast(err ?: "Backup saved to Google Drive ✓")
        }
    }

    fun onToken(token: String?) {
        if (token.isNullOrBlank()) {
            busy = ""
            vm.toast("Could not connect Google Drive")
            return
        }
        scope.launch {
            val mail = try {
                DriveBackup.accountEmail(token)
            } catch (e: Exception) {
                ""
            }
            DriveBackup.saveConnected(context, mail.ifBlank { "Google account" })
            DriveBackup.setAuto(context, true)
            refresh()
            vm.toast("Google Drive connected ✓")
            backupNow()
        }
    }

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            try {
                onToken(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(res.data).accessToken)
            } catch (e: Exception) {
                busy = ""
                vm.toast("Google sign-in failed")
            }
        } else {
            busy = ""
            vm.toast("Google sign-in cancelled")
        }
    }

    fun connect() {
        busy = "connect"
        Identity.getAuthorizationClient(context).authorize(DriveBackup.request())
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) {
                    authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    onToken(r.accessToken)
                }
            }
            .addOnFailureListener { e ->
                busy = ""
                if (e is ApiException && e.statusCode == CommonStatusCodes.DEVELOPER_ERROR) showSetup = true
                else vm.toast("Google sign-in failed: ${e.message ?: "unknown error"}")
            }
    }

    fun openRestore() {
        busy = "list"
        scope.launch {
            try {
                files = DriveBackup.listBackups(context)
            } catch (e: Exception) {
                vm.toast(DriveBackup.problem(e))
            }
            busy = ""
        }
    }

    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.CloudUpload, Green, 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Google Drive backup", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Automatic daily backup of bills, stock & settings", color = Muted, fontSize = 12.5.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (email.isBlank()) {
            Text(
                "Connect your Google account once. The app then backs up by itself every day. " +
                    "If your phone is lost or changed, restore everything in one tap.",
                color = Muted, fontSize = 13.5.sp
            )
            Spacer(Modifier.height(12.dp))
            MainButton(
                if (busy == "connect") "Connecting…" else "Connect Google Drive",
                Modifier.fillMaxWidth(), icon = Icons.Rounded.CloudUpload, color = Green, enabled = busy.isEmpty()
            ) { connect() }
            Spacer(Modifier.height(6.dp))
            Text(
                "Setup code (for first-time Google setup)",
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showSetup = true }
                    .padding(6.dp),
                color = Navy, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Green.copy(alpha = 0.08f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Green, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(email, color = Ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (last > 0) "Last backup: ${whenText(last)}" else "Not backed up yet", color = Muted, fontSize = 12.5.sp)
                }
            }
            if (error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Last try failed: $error", color = Red, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(6.dp))
            SwitchRow("Automatic daily backup", "Once a day, whenever internet is on", auto) { on ->
                DriveBackup.setAuto(context, on)
                auto = on
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MainButton(
                    if (busy == "backup") "Backing up…" else "Back up now",
                    Modifier.weight(1f), color = Green, enabled = busy.isEmpty()
                ) { backupNow() }
                LightButton(
                    if (busy == "list" || busy == "restore") "Please wait…" else "Restore",
                    Modifier.weight(1f), color = Navy
                ) { if (busy.isEmpty()) openRestore() }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = { showSetup = true }) { Text("Setup code", color = Muted, fontSize = 12.5.sp) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    DriveBackup.disconnect(context)
                    refresh()
                }) { Text("Disconnect", color = Red, fontSize = 12.5.sp) }
            }
        }
    }

    files?.let { list ->
        DriveFilesDialog(list, onPick = {
            files = null
            pick = it
        }, onDismiss = { files = null })
    }
    pick?.let { f ->
        ConfirmDialog(
            title = "Restore this backup?",
            text = "All parts, bills and settings on this device will be REPLACED with the backup from ${fmtDateTime(f.time)}.",
            confirm = "Restore",
            danger = true,
            onConfirm = {
                busy = "restore"
                scope.launch {
                    try {
                        vm.toast(DriveBackup.restoreFromDrive(context, vm, f))
                    } catch (e: Exception) {
                        vm.toast("Restore failed: " + DriveBackup.problem(e))
                    }
                    busy = ""
                }
            },
            onDismiss = { pick = null }
        )
    }
    if (showSetup) DriveSetupDialog { showSetup = false }
}

@Composable
private fun DriveFilesDialog(files: List<DriveFile>, onPick: (DriveFile) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Backups in Google Drive", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (files.isEmpty()) Text("No backups found yet.", color = Muted, fontSize = 14.sp)
                files.forEachIndexed { i, f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (i == 0) NavySoft else Color(0xFFF6F8FC))
                            .clickable { onPick(f) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.CloudDownload, null, tint = Navy, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(whenText(f.time), color = Ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(sizeText(f.size), color = Faint, fontSize = 12.sp)
                        }
                        if (i == 0) Pill("Latest", Green)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Muted) } }
    )
}

@Composable
private fun DriveSetupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sha1 = remember { DriveBackup.signingSha1(context) }
    val code = "Mauli Auto Parts – Google Drive setup\nPackage name: ${context.packageName}\nSHA-1: $sha1"
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        title = { Text("One-time Google setup", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Google Drive must be set up once in Google Cloud by the app owner. These two codes are needed there:",
                    color = Muted, fontSize = 13.5.sp
                )
                Spacer(Modifier.height(12.dp))
                Text("Package name", color = Faint, fontSize = 12.sp)
                Text(context.packageName, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("SHA-1", color = Faint, fontSize = 12.sp)
                Text(
                    sha1.ifBlank { "Not available" }, color = Navy, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { Printer.shareText(context, code) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy)
            ) { Text("Share codes", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(sha1))
                Toast.makeText(context, "SHA-1 copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy SHA-1", color = Navy) }
        }
    )
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}
