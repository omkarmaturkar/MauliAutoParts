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
    data object Purchases : Screen()
    data class EditPart(val id: Long) : Screen()
    data class BillView(val id: Long) : Screen()
    data class PurchaseEdit(val id: Long) : Screen()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application = app
    private val db = AppDb.get(app)
    val dao = db.dao()
    private val prefs = app.getSharedPreferences("mauli", Context.MODE_PRIVATE)

    val parts: StateFlow<List<Part>> = dao.partsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bills: StateFlow<List<Bill>> = dao.billsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val purchases: StateFlow<List<Bill>> = dao.purchasesFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val frequent: StateFlow<List<Long>> = dao.frequentPartIds().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
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
        } catch (e: Exception) {
        }
        if (drafts.isEmpty()) drafts.add(Draft())
    }

    private fun persistDrafts() {
        prefs.edit().putString("drafts", JSONArray(drafts.map { it.toJson() }).toString()).apply()
    }

    private fun setDraft(d: Draft) {
        val i = drafts.indexOfFirst { it.id == d.id }
        if (i >= 0) {
            drafts[i] = d
            persistDrafts()
        }
    }

    fun editDraft(change: (Draft) -> Draft) {
        setDraft(change(draft))
    }

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

    fun discardDraft() {
        closeDraft(draft.id)
    }

    // ---------------- Stock waiting in open bills ----------------

    /** Extra stock an open bill will take when it is saved (part id -> qty). */
    private fun pending(d: Draft): Map<Long, Int> {
        val qty = HashMap<Long, Int>()
        d.lines.forEach { l -> l.partId?.let { qty[it] = (qty[it] ?: 0) + l.qty } }
        val out = HashMap<Long, Int>()
        (qty.keys + d.orig.keys).forEach { id ->
            val delta = (qty[id] ?: 0) - (d.orig[id] ?: 0)
            if (delta != 0) out[id] = delta
        }
        return out
    }

    /** Quantity of each part sitting in open (not yet saved) bills. */
    fun reserved(): Map<Long, Int> {
        val m = HashMap<Long, Int>()
        drafts.forEach { d -> pending(d).forEach { (id, q) -> m[id] = (m[id] ?: 0) + q } }
        return m
    }

    /** Stock that will be left after all open bills are saved. */
    fun available(p: Part): Int = p.qty - (reserved()[p.id] ?: 0)

    private fun limitFor(p: Part, d: Draft): Int {
        var others = 0
        drafts.forEach { o -> if (o.id != d.id) others += pending(o)[p.id] ?: 0 }
        return p.qty + (d.orig[p.id] ?: 0) - others
    }

    /** Total quantity of this part the current bill is allowed to use. */
    fun limitFor(p: Part): Int = limitFor(p, draft)

    // ---------------- Bill lines ----------------

    fun addToBill(p: Part, notify: Boolean = false) {
        val d = draft
        val line = d.lines.find { it.partId == p.id }
        val have = d.lines.filter { it.partId == p.id }.sumOf { it.qty }
        val limit = limitFor(p, d)
        if (have + 1 > limit) {
            toast(if (limit <= 0) "${p.name} is out of stock" else "Only $limit ${p.name} available")
            return
        }
        val lines = if (line != null) d.lines.map { if (it.id == line.id) it.copy(qty = it.qty + 1) else it }
        else d.lines + BillItem(
            id = System.nanoTime(), partId = p.id, name = p.name, partNo = p.partNo, qty = 1,
            price = p.sellPrice, buyPrice = p.buyPrice, gst = p.gst
        )
        setDraft(d.copy(lines = lines))
        if (notify) toast("Added ${p.name} • ${limit - have - 1} left in stock")
    }

    fun changeQty(line: BillItem, delta: Int) {
        updateLine(line, line.qty + delta, line.price)
    }

    fun updateLine(line: BillItem, qty: Int, price: Double) {
        if (qty <= 0) {
            removeLine(line)
            return
        }
        val d = draft
        val p = line.partId?.let { id -> parts.value.find { it.id == id } }
        if (p != null && qty > line.qty) {
            val otherLines = d.lines.filter { it.partId == p.id && it.id != line.id }.sumOf { it.qty }
            val limit = limitFor(p, d) - otherLines
            if (qty > limit) {
                toast("Only $limit ${p.name} available")
                return
            }
        }
        editDraft { x -> x.copy(lines = x.lines.map { if (it.id == line.id) it.copy(qty = qty, price = price) else it }) }
    }

    fun removeLine(line: BillItem) {
        editDraft { d -> d.copy(lines = d.lines.filter { it.id != line.id }) }
    }

    /** Labour / other item. If the name matches a stock part, it is linked so stock reduces. */
    fun addCustom(name: String, price: Double, qty: Int) {
        val clean = name.trim()
        val match = parts.value.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        if (match != null) {
            val d = draft
            val have = d.lines.filter { it.partId == match.id }.sumOf { it.qty }
            val limit = limitFor(match, d)
            if (have + qty > limit) {
                toast("Only $limit ${match.name} available")
                return
            }
            val existing = d.lines.find { it.partId == match.id }
            val lines = if (existing != null) {
                d.lines.map { if (it.id == existing.id) it.copy(qty = it.qty + qty, price = price) else it }
            } else {
                d.lines + BillItem(
                    id = System.nanoTime(), partId = match.id, name = match.name, partNo = match.partNo,
                    qty = qty, price = price, buyPrice = match.buyPrice, gst = match.gst
                )
            }
            setDraft(d.copy(lines = lines))
            toast("${match.name} is in your stock • linked, stock will reduce ✓")
            return
        }
        editDraft { d ->
            d.copy(lines = d.lines + BillItem(id = System.nanoTime(), partId = null, name = clean, qty = qty, price = price, gst = 18.0))
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

    // ---------------- Saving bills ----------------

    private suspend fun checkStock(lines: List<BillItem>) {
        val need = HashMap<Long, Int>()
        lines.forEach { l -> l.partId?.let { need[it] = (need[it] ?: 0) + l.qty } }
        for ((pid, q) in need) {
            val p = dao.part(pid) ?: throw Exception("A part in this bill was deleted from stock. Remove it and try again.")
            if (p.qty < q) throw Exception("Not enough stock: ${p.name} (only ${p.qty})")
        }
    }

    private suspend fun noNegative(items: List<BillItem>) {
        for (id in items.mapNotNull { it.partId }.distinct()) {
            val p = dao.part(id) ?: continue
            if (p.qty < 0) throw Exception("Can't do this: ${p.name} would go below zero. Some of them are already sold.")
        }
    }

    private suspend fun stockMessage(lines: List<BillItem>, head: String): String {
        val ids = lines.mapNotNull { it.partId }.distinct()
        if (ids.isEmpty()) return "$head ✓"
        val ps = ids.mapNotNull { dao.part(it) }
        return if (ps.size <= 2) "$head • Stock now: " + ps.joinToString(", ") { "${it.name} ${it.qty}" }
        else "$head • Stock updated for ${ps.size} parts ✓"
    }

    /** Opens a saved bill or estimate in Billing so it can be changed. */
    fun editBill(b: Bill) {
        if (b.kind != 0) return
        val already = drafts.indexOfFirst { it.editId == b.id }
        if (already >= 0) {
            current = already
            tab(Screen.Billing)
            return
        }
        viewModelScope.launch {
            val items = dao.items(b.id)
            val base = System.nanoTime()
            val orig = if (b.isEstimate) emptyMap() else items.filter { it.partId != null }
                .groupBy { it.partId!! }.mapValues { e -> e.value.sumOf { it.qty } }
            val d = Draft(
                lines = items.mapIndexed { i, l -> l.copy(id = base + i, billId = 0) },
                customer = b.customer, phone = b.phone, vehicleNo = b.vehicleNo, model = b.model, km = b.km,
                discount = if (b.discount > 0) num(b.discount) else "",
                received = if (b.payMode == "Udhaar") num(b.paid) else "",
                payMode = if (b.isEstimate || b.payMode == "-") "Cash" else b.payMode,
                note = b.note, editId = b.id, editCode = billCode(b), orig = orig
            )
            val blank = drafts.indexOfFirst {
                it.editId == 0L && it.lines.isEmpty() && it.customer.isBlank() && it.vehicleNo.isBlank() && it.phone.isBlank()
            }
            if (blank >= 0) {
                drafts[blank] = d
                current = blank
            } else {
                drafts.add(d)
                current = drafts.lastIndex
            }
            persistDrafts()
            tab(Screen.Billing)
            toast("Editing ${billCode(b)} • make changes and tap Update")
        }
    }

    /** Saves the current bill (new or edited). Stock is updated automatically in one safe step. */
    fun saveBill(estimate: Boolean, onSaved: (Long) -> Unit) {
        val d = draft
        if (d.lines.isEmpty()) {
            toast("Add at least one item")
            return
        }
        val t = calcTotals(d.lines, d.discount.toNum())
        val paid = when {
            estimate -> 0.0
            d.payMode == "Udhaar" -> d.received.toNum().coerceIn(0.0, t.total)
            else -> t.total
        }
        viewModelScope.launch {
            try {
                val id = db.withTransaction {
                    if (d.editId != 0L) {
                        val old = dao.bill(d.editId)
                        if (old == null || billCode(old) != d.editCode) {
                            throw Exception("This bill was changed or deleted. Please open it again.")
                        }
                        if (!old.isEstimate && estimate) throw Exception("A saved bill can't be changed into an estimate")
                        if (!old.isEstimate) dao.items(old.id).forEach { l -> l.partId?.let { dao.changeQty(it, l.qty, now()) } }
                        if (!estimate) checkStock(d.lines)
                        dao.deleteItems(old.id)
                        dao.insertItems(d.lines.map { it.copy(id = 0, billId = old.id) })
                        if (!estimate) d.lines.forEach { l -> l.partId?.let { dao.changeQty(it, -l.qty, now()) } }
                        val toBill = old.isEstimate && !estimate
                        dao.updateBill(
                            old.copy(
                                billNo = if (toBill) dao.nextNo(false) else old.billNo,
                                isEstimate = estimate,
                                date = if (toBill) now() else old.date,
                                customer = d.customer.trim(), phone = d.phone.trim(), vehicleNo = normVehicle(d.vehicleNo),
                                model = d.model.trim(), km = d.km.trim(), subTotal = t.sub, discount = t.discount,
                                total = t.total, paid = paid, payMode = if (estimate) "-" else d.payMode,
                                profit = t.profit, note = d.note.trim()
                            )
                        )
                        old.id
                    } else {
                        if (!estimate) checkStock(d.lines)
                        val billId = dao.insertBill(
                            Bill(
                                billNo = dao.nextNo(estimate), isEstimate = estimate, date = now(),
                                customer = d.customer.trim(), phone = d.phone.trim(), vehicleNo = normVehicle(d.vehicleNo),
                                model = d.model.trim(), km = d.km.trim(), subTotal = t.sub, discount = t.discount,
                                total = t.total, paid = paid, payMode = if (estimate) "-" else d.payMode,
                                profit = t.profit, note = d.note.trim(), kind = 0
                            )
                        )
                        dao.insertItems(d.lines.map { it.copy(id = 0, billId = billId) })
                        if (!estimate) d.lines.forEach { l -> l.partId?.let { dao.changeQty(it, -l.qty, now()) } }
                        billId
                    }
                }
                closeDraft(d.id)
                val head = when {
                    estimate -> "Estimate saved • Stock not changed"
                    d.editId != 0L -> "Bill updated"
                    else -> "Bill saved"
                }
                toast(if (estimate) head else stockMessage(d.lines, head))
                onSaved(id)
            } catch (e: Exception) {
                toast(e.message ?: "Could not save")
            }
        }
    }

    fun convertEstimate(b: Bill, mode: String) {
        viewModelScope.launch {
            try {
                val items = dao.items(b.id)
                db.withTransaction {
                    checkStock(items)
                    dao.updateBill(
                        b.copy(
                            billNo = dao.nextNo(false), isEstimate = false, date = now(), payMode = mode,
                            paid = if (mode == "Udhaar") 0.0 else b.total
                        )
                    )
                    items.forEach { l -> l.partId?.let { dao.changeQty(it, -l.qty, now()) } }
                }
                drafts.filter { it.editId == b.id }.map { it.id }.forEach { closeDraft(it) }
                toast(stockMessage(items, "Converted to bill"))
            } catch (e: Exception) {
                toast(e.message ?: "Could not convert")
            }
        }
    }

    /** Deleting a bill puts its parts back. Deleting a purchase takes its parts out again. */
    fun deleteBill(b: Bill, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                db.withTransaction {
                    val items = dao.items(b.id)
                    if (b.kind == 1) {
                        items.forEach { l -> l.partId?.let { dao.changeQty(it, -l.qty, now()) } }
                        noNegative(items)
                    } else if (!b.isEstimate) {
                        items.forEach { l -> l.partId?.let { dao.changeQty(it, l.qty, now()) } }
                    }
                    dao.deleteItems(b.id)
                    dao.deleteBill(b.id)
                }
                drafts.filter { it.editId == b.id }.map { it.id }.forEach { closeDraft(it) }
                toast(
                    when {
                        b.kind == 1 -> "Purchase deleted • Stock reduced"
                        b.isEstimate -> "Estimate deleted"
                        else -> "Bill deleted • Stock added back"
                    }
                )
                onDone()
            } catch (e: Exception) {
                toast(e.message ?: "Could not delete")
            }
        }
    }

    fun receivePayment(b: Bill, amount: Double) {
        if (amount <= 0) return
        viewModelScope.launch {
            dao.updateBill(b.copy(paid = (b.paid + amount).coerceAtMost(b.total)))
            toast(if (b.kind == 1) "₹${num(amount)} paid to vendor ✓" else "₹${num(amount)} received ✓")
        }
    }

    // ---------------- Purchases (stock in) ----------------

    /** Saves a purchase (new or edited). Stock goes up, buy price and supplier are updated. */
    fun savePurchase(editId: Long, input: PurchaseInput, onDone: (Long) -> Unit) {
        val lines = input.lines.filter { it.qty > 0 }
        if (input.vendor.isBlank()) {
            toast("Please enter the vendor name")
            return
        }
        if (lines.isEmpty()) {
            toast("Add at least one part")
            return
        }
        val sub = lines.sumOf { it.qty * it.price }
        val disc = input.discount.coerceIn(0.0, sub)
        val total = sub - disc
        val paid = input.paid.coerceIn(0.0, total)
        val vendor = input.vendor.trim()
        viewModelScope.launch {
            try {
                val id = db.withTransaction {
                    val old: Bill? = if (editId != 0L) dao.bill(editId) ?: throw Exception("This purchase was deleted") else null
                    val touched = mutableListOf<BillItem>()
                    if (old != null) {
                        val oldItems = dao.items(old.id)
                        touched += oldItems
                        oldItems.forEach { l -> l.partId?.let { dao.changeQty(it, -l.qty, now()) } }
                        dao.deleteItems(old.id)
                    }
                    lines.forEach { l ->
                        l.partId?.let { pid ->
                            dao.changeQty(pid, l.qty, now())
                            if (l.price > 0) dao.setBuyPrice(pid, l.price)
                            dao.setSupplier(pid, vendor)
                        }
                    }
                    touched += lines
                    noNegative(touched)
                    val bill = Bill(
                        id = old?.id ?: 0L, billNo = old?.billNo ?: dao.nextPurchaseNo(), kind = 1, date = input.date,
                        customer = vendor, phone = input.phone.trim(), ref = input.ref.trim(),
                        subTotal = sub, discount = disc, total = total, paid = paid,
                        payMode = input.payMode, note = input.note.trim()
                    )
                    val billId = if (old != null) {
                        dao.updateBill(bill)
                        old.id
                    } else {
                        dao.insertBill(bill)
                    }
                    dao.insertItems(lines.map { it.copy(id = 0, billId = billId, buyPrice = it.price) })
                    billId
                }
                toast(stockMessage(lines, if (editId != 0L) "Purchase updated" else "Purchase saved"))
                onDone(id)
            } catch (e: Exception) {
                toast(e.message ?: "Could not save")
            }
        }
    }

    /** Creates a new part (with 0 stock) from the purchase screen. */
    fun quickPart(name: String, buy: Double, sell: Double, onDone: (Part) -> Unit) {
        val n = name.trim()
        if (n.isBlank()) {
            toast("Please enter part name")
            return
        }
        parts.value.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let {
            onDone(it)
            return
        }
        viewModelScope.launch {
            val p = Part(name = n, buyPrice = buy, sellPrice = sell, qty = 0, updatedAt = now())
            val id = dao.insertPart(p)
            toast("New part added: $n")
            onDone(p.copy(id = id))
        }
    }

    // ---------------- Parts ----------------
    fun savePart(p: Part, onDone: () -> Unit) {
        if (p.name.isBlank()) {
            toast("Please enter part name")
            return
        }
        viewModelScope.launch {
            val x = p.copy(name = p.name.trim(), updatedAt = now())
            if (x.id == 0L) dao.insertPart(x) else dao.updatePart(x)
            toast("Saved ✓")
            onDone()
        }
    }

    fun deletePart(p: Part) {
        viewModelScope.launch {
            dao.deletePart(p)
            toast("Part deleted")
        }
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
        if (s.printerAddress.isBlank()) {
            toast("Select your Bluetooth printer in Settings first")
            return
        }
        viewModelScope.launch {
            val b = dao.bill(billId) ?: return@launch
            val items = dao.items(billId)
            toast("Printing…")
            val err = Printer.printBluetooth(context.applicationContext, s.printerAddress, Invoice.thermal(b, items, s))
            toast(err ?: "Printed ✓")
        }
    }

    fun testPrint(context: Context) {
        val s = settings
        if (s.printerAddress.isBlank()) {
            toast("Select a printer first")
            return
        }
        viewModelScope.launch {
            val err = Printer.printBluetooth(context.applicationContext, s.printerAddress, Printer.testPage(s))
            toast(err ?: "Test print sent ✓")
        }
    }

    // ---------------- Backup / Restore ----------------
    fun backup(uri: Uri) {
        viewModelScope.launch {
            try {
                val json = JSONObject().put("app", "MauliAutoParts").put("version", 2).put("time", now())
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
                drafts.clear()
                drafts.add(Draft())
                current = 0
                persistDrafts()
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
                        if (old != null) {
                            dao.updatePart(p.copy(id = old.id))
                            updated++
                        } else {
                            dao.insertPart(p)
                            added++
                        }
                    }
                }
                toast("Import done: $added new, $updated updated ✓")
            } catch (e: Exception) {
                toast("Import failed: ${e.message}")
            }
        }
    }
}
