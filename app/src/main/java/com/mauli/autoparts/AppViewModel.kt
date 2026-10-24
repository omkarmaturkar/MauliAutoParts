package com.mauli.autoparts

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed class Screen {
    data object Home : Screen()
    data object Stock : Screen()
    data object Billing : Screen()
    data object Bills : Screen()
    data object Reports : Screen()
    data object Settings : Screen()
    data class EditPart(val id: Long) : Screen()
    data class BillView(val id: Long) : Screen()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application = app
    private val db = AppDb.get(app)
    val dao = db.dao()
    private val prefs = app.getSharedPreferences("mauli", Context.MODE_PRIVATE)

    val parts: StateFlow<List<Part>> = dao.partsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bills: StateFlow<List<Bill>> = dao.billsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val frequent: StateFlow<List<Long>> = dao.frequentPartIds().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------- Navigation ----------------
    val stack = mutableStateListOf<Screen>(Screen.Home)
    fun open(s: Screen) { stack.add(s) }
    fun tab(s: Screen) { stack.clear(); stack.add(s) }
    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        else if (stack.last() != Screen.Home) tab(Screen.Home)
    }
    var stockFilter by mutableStateOf("All")
    var billFilter by mutableStateOf("All")

    // ---------------- Settings ----------------
    var settings by mutableStateOf(loadSettings())
        private set

    private fun loadSettings(): ShopSettings = try {
        prefs.getString("settings", null)?.let { settingsOf(JSONObject(it)) } ?: ShopSettings()
    } catch (e: Exception) {
        ShopSettings()
    }

    fun updateSettings(s: ShopSettings) {
        settings = s
        prefs.edit().putString("settings", s.toJson().toString()).apply()
    }

    // ---------------- Open bills (multitasking) ----------------
    val drafts = mutableStateListOf<Draft>()
    var current by mutableIntStateOf(0)
    val draft: Draft get() = drafts[current.coerceIn(0, drafts.lastIndex)]

    init {
        try {
            prefs.getString("drafts", null)?.let { s -> drafts.addAll(JSONArray(s).mapObjects { draftOf(it) }) }
        } catch (e: Exception) { }
        if (drafts.isEmpty()) drafts.add(Draft())
    }

    private fun persistDrafts() {
        prefs.edit().putString("drafts", JSONArray(drafts.map { it.toJson() }).toString()).apply()
    }

    private fun setDraft(d: Draft) {
        val i = drafts.indexOfFirst { it.id == d.id }
        if (i >= 0) { drafts[i] = d; persistDrafts() }
    }

    fun editDraft(change: (Draft) -> Draft) { setDraft(change(draft)) }

    fun newDraft() {
        drafts.add(Draft())
        current = drafts.lastIndex
        persistDrafts()
    }

    private fun closeDraft(id: Long) {
        drafts.removeAll { it.id == id }
        if (drafts.isEmpty()) drafts.add(Draft())
        current = current.coerceIn(0, drafts.lastIndex)
        persistDrafts()
    }

    fun discardDraft() { closeDraft(draft.id) }

    fun addToBill(p: Part, notify: Boolean = false) {
        val d = draft
        val line = d.lines.find { it.partId == p.id }
        val want = (line?.qty ?: 0) + 1
        if (want > p.qty) {
            toast(if (p.qty <= 0) "${p.name} is out of stock" else "Only ${p.qty} ${p.name} in stock")
            return
        }
        val lines = if (line != null) d.lines.map { if (it.id == line.id) it.copy(qty = want) else it }
        else d.lines + BillItem(
            id = System.nanoTime(), partId = p.id, name = p.name, partNo = p.partNo, qty = 1,
            price = p.sellPrice, buyPrice = p.buyPrice, gst = p.gst
        )
        setDraft(d.copy(lines = lines))
        if (notify) toast("Added to bill: ${p.name}")
    }

    fun changeQty(line: BillItem, delta: Int) { updateLine(line, line.qty + delta, line.price) }

    fun updateLine(line: BillItem, qty: Int, price: Double) {
        if (qty <= 0) { removeLine(line); return }
        val stock = line.partId?.let { id -> parts.value.find { it.id == id }?.qty }
        if (stock != null && qty > line.qty && qty > stock) { toast("Only $stock in stock"); return }
        editDraft { d -> d.copy(lines = d.lines.map { if (it.id == line.id) it.copy(qty = qty, price = price) else it }) }
    }

    fun removeLine(line: BillItem) {
        editDraft { d -> d.copy(lines = d.lines.filter { it.id != line.id }) }
    }

    fun addCustom(name: String, price: Double, qty: Int) {
        editDraft { d ->
            d.copy(lines = d.lines + BillItem(id = System.nanoTime(), partId = null, name = name, qty = qty, price = price, gst = 18.0))
        }
    }

    fun onVehicle(v: String) {
        editDraft { it.copy(vehicleNo = v.uppercase()) }
        val n = normVehicle(v)
        if (n.length >= 8) autofill(n, "")
    }

    fun onPhone(p: String) {
        val digits = p.filter { it.isDigit() }.take(10)
        editDraft { it.copy(phone = digits) }
        if (digits.length == 10) autofill("", digits)
    }

    private fun autofill(vehicle: String, phone: String) {
        val id = draft.id
        viewModelScope.launch {
            val b = dao.lastBillOf(vehicle, phone) ?: return@launch
            val i = drafts.indexOfFirst { it.id == id }
            if (i < 0) return@launch
            val d = drafts[i]
            drafts[i] = d.copy(
                customer = d.customer.ifBlank { b.customer }, phone = d.phone.ifBlank { b.phone },
                vehicleNo = d.vehicleNo.ifBlank { b.vehicleNo }, model = d.model.ifBlank { b.model }
            )
            persistDrafts()
            if (d.customer.isBlank() && b.customer.isNotBlank()) toast("Old customer: ${b.customer}")
        }
    }

    private suspend fun checkStock(lines: List<BillItem>) {
        for (l in lines) {
            val pid = l.partId ?: continue
            val p = dao.part(pid) ?: throw Exception("${l.name} was deleted from stock")
            if (p.qty < l.qty) throw Exception("Not enough stock: ${p.name} (only ${p.qty})")
        }
    }

    /** Saves bill, reduces stock automatically (in one safe transaction). */
    fun saveBill(estimate: Boolean, onSaved: (Long) -> Unit) {
        val d = draft
        if (d.lines.isEmpty()) { toast("Add at least one item"); return }
        val t = calcTotals(d.lines, d.discount.toNum())
        val paid = when {
            estimate -> 0.0
            d.payMode == "Udhaar" -> d.received.toNum().coerceIn(0.0, t.total)
            else -> t.total
        }
        viewModelScope.launch {
            try {
                val id = db.withTransaction {
                    if (!estimate) checkStock(d.lines)
                    val billId = dao.insertBill(
                        Bill(
                            billNo = dao.nextNo(estimate), isEstimate = estimate, date = now(),
                            customer = d.customer.trim(), phone = d.phone.trim(), vehicleNo = normVehicle(d.vehicleNo),
                            model = d.model.trim(), km = d.km.trim(), subTotal = t.sub, discount = t.discount,
                            total = t.total, paid = paid, payMode = if (estimate) "-" else d.payMode,
                            profit = t.profit, note = d.note.trim()
                        )
                    )
                    dao.insertItems(d.lines.map { it.copy(id = 0, billId = billId) })
                    if (!estimate) d.lines.forEach { l -> l.partId?.let { dao.changeQty(it, -l.qty, now()) } }
                    billId
                }
                closeDraft(d.id)
                toast(if (estimate) "Estimate saved" else "Bill saved • Stock updated ✓")
                onSaved(id)
            } catch (e: Exception) {
                toast(e.message ?: "Could not save")
            }
        }
    }

    fun convertEstimate(b: Bill, mode: String) {
        viewModelScope.launch {
            try {
                db.withTransaction {
                    val items = dao.items(b.id)
                    checkStock(items)
                    dao.updateBill(
                        b.copy(
                            billNo = dao.nextNo(false), isEstimate = false, date = now(), payMode = mode,
                            paid = if (mode == "Udhaar") 0.0 else b.total
                        )
                    )
                    items.forEach { l -> l.partId?.let { dao.changeQty(it, -l.qty, now()) } }
                }
                toast("Converted to bill • Stock updated ✓")
            } catch (e: Exception) {
                toast(e.message ?: "Could not convert")
            }
        }
    }

    /** Deleting a bill puts its parts back into stock. */
    fun deleteBill(b: Bill, onDone: () -> Unit) {
        viewModelScope.launch {
            db.withTransaction {
                if (!b.isEstimate) dao.items(b.id).forEach { l -> l.partId?.let { dao.changeQty(it, l.qty, now()) } }
                dao.deleteItems(b.id)
                dao.deleteBill(b.id)
            }
            toast(if (b.isEstimate) "Estimate deleted" else "Bill deleted • Stock restored")
            onDone()
        }
    }

    fun receivePayment(b: Bill, amount: Double) {
        if (amount <= 0) return
        viewModelScope.launch {
            dao.updateBill(b.copy(paid = (b.paid + amount).coerceAtMost(b.total)))
            toast("₹${num(amount)} received ✓")
        }
    }

    // ---------------- Parts ----------------
    fun savePart(p: Part, onDone: () -> Unit) {
        if (p.name.isBlank()) { toast("Please enter part name"); return }
        viewModelScope.launch {
            val x = p.copy(name = p.name.trim(), updatedAt = now())
            if (x.id == 0L) dao.insertPart(x) else dao.updatePart(x)
            toast("Saved ✓")
            onDone()
        }
    }

    fun deletePart(p: Part) {
        viewModelScope.launch { dao.deletePart(p); toast("Part deleted") }
    }

    fun addStock(p: Part, qty: Int, buy: Double?) {
        viewModelScope.launch {
            dao.changeQty(p.id, qty, now())
            if (buy != null && buy > 0) dao.setBuyPrice(p.id, buy)
            toast("+$qty added • ${p.name}")
        }
    }

    fun orderListText(): String {
        val sb = StringBuilder("*Order list - ${settings.shopName}*\n\n")
        parts.value.filter { it.qty <= it.minQty }.forEachIndexed { i, p ->
            val need = (p.minQty * 2 - p.qty).coerceAtLeast(1)
            sb.append("${i + 1}. ${p.name}")
            if (p.partNo.isNotBlank()) sb.append(" (${p.partNo})")
            if (p.brand.isNotBlank()) sb.append(" - ${p.brand}")
            sb.append(" : $need ${p.unit}\n")
        }
        return sb.toString()
    }

    // ---------------- Printing ----------------
    fun printThermal(context: Context, billId: Long) {
        val s = settings
        if (s.printerAddress.isBlank()) { toast("Select your Bluetooth printer in Settings first"); return }
        viewModelScope.launch {
            val b = dao.bill(billId) ?: return@launch
            val items = dao.items(billId)
            toast("Printing…")
            val err = Printer.printBluetooth(context.applicationContext, s.printerAddress, Printer.escPosBill(b, items, s))
            toast(err ?: "Printed ✓")
        }
    }

    fun testPrint(context: Context) {
        val s = settings
        if (s.printerAddress.isBlank()) { toast("Select a printer first"); return }
        viewModelScope.launch {
            val err = Printer.printBluetooth(context.applicationContext, s.printerAddress, Printer.testPage(s))
            toast(err ?: "Test print sent ✓")
        }
    }

    // ---------------- Backup / Restore ----------------
    fun backup(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = JSONObject().put("app", "MauliAutoParts").put("version", 1).put("time", now())
                    .put("settings", settings.toJson())
                    .put("parts", JSONArray(dao.allParts().map { it.toJson() }))
                    .put("bills", JSONArray(dao.allBills().map { it.toJson() }))
                    .put("items", JSONArray(dao.allItems().map { it.toJson() }))
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toString().toByteArray()) }
                        ?: throw Exception("Cannot write file")
                }
                toast("Backup saved ✓")
            } catch (e: Exception) {
                toast("Backup failed: ${e.message}")
            }
        }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: throw Exception("Cannot read file")
                val o = JSONObject(text)
                if (!o.has("parts")) throw Exception("Not a Mauli backup file")
                val ps = o.optJSONArray("parts").mapObjects { partOf(it) }
                val bs = o.optJSONArray("bills").mapObjects { billOf(it) }
                val its = o.optJSONArray("items").mapObjects { itemOf(it) }
                db.withTransaction {
                    dao.clearItems(); dao.clearBills(); dao.clearParts()
                    dao.putParts(ps); dao.putBills(bs); dao.putItems(its)
                }
                o.optJSONObject("settings")?.let { updateSettings(settingsOf(it)) }
                toast("Restored ${ps.size} parts & ${bs.size} bills ✓")
            } catch (e: Exception) {
                toast("Restore failed: ${e.message}")
            }
        }
    }

    // ---------------- Excel (CSV) import / export ----------------
    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            try {
                val sb = StringBuilder("\uFEFF").append(csvLine(CSV_HEADER)).append("\r\n")
                dao.allParts().sortedBy { it.name.lowercase() }.forEach { sb.append(csvLine(it.toCsvRow())).append("\r\n") }
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(sb.toString().toByteArray()) }
                        ?: throw Exception("Cannot write file")
                }
                toast("Stock exported ✓ Open it in Excel")
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
            }
        }
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: throw Exception("Cannot read file")
                val rows = parseCsv(text.removePrefix("\uFEFF"))
                var added = 0
                var updated = 0
                db.withTransaction {
                    val existing = dao.allParts()
                    val byNo = existing.filter { it.partNo.isNotBlank() }.associateBy { it.partNo.lowercase() }
                    val byName = existing.associateBy { it.name.lowercase() }
                    for (r in rows) {
                        val c = { i: Int -> r.getOrElse(i) { "" }.trim() }
                        val name = c(0)
                        if (name.isEmpty() || name.equals("name", ignoreCase = true)) continue
                        val p = Part(
                            name = name, partNo = c(1), category = c(2).ifBlank { "General" }, brand = c(3),
                            vehicle = c(4), rack = c(5), unit = c(6).ifBlank { "pcs" }, buyPrice = c(7).toNum(),
                            sellPrice = c(8).toNum(), mrp = c(9).toNum(), qty = c(10).toNum().toInt(),
                            minQty = if (c(11).isBlank()) 2 else c(11).toNum().toInt(),
                            gst = if (c(12).isBlank()) 18.0 else c(12).toNum(),
                            hsn = c(13), supplier = c(14), notes = c(15), updatedAt = now()
                        )
                        val old = (if (p.partNo.isNotBlank()) byNo[p.partNo.lowercase()] else null) ?: byName[name.lowercase()]
                        if (old != null) { dao.updatePart(p.copy(id = old.id)); updated++ } else { dao.insertPart(p); added++ }
                    }
                }
                toast("Import done: $added new, $updated updated ✓")
            } catch (e: Exception) {
                toast("Import failed: ${e.message}")
            }
        }
    }
}
