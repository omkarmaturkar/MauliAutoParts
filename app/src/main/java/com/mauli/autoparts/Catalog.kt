package com.mauli.autoparts

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A saved service / labour charge. One tap adds it to the bill. */
data class ServiceItem(val name: String, val price: Double)

/** A saved group of items (for example "General Service kit"). One tap adds all of them. */
data class Pack(val id: Long, val name: String, val lines: List<BillItem>)

/**
 * Saved services and packages for fast billing.
 * Stored inside the database with hidden ids (services = -2, packages = -101, -102 ...),
 * so they are included in every Google Drive / file backup and restore.
 */
object Catalog {
    private const val SERVICES_ID = -2L
    private const val PACK_BASE = -100L
    private const val KIND_SERVICES = 2
    private const val KIND_PACK = 3
    private val lock = Mutex()

    val services = mutableStateListOf<ServiceItem>()
    val packs = mutableStateListOf<Pack>()

    private val defaults = listOf(
        ServiceItem("General Service", 350.0),
        ServiceItem("Labour", 100.0),
        ServiceItem("Oil Change", 50.0),
        ServiceItem("Brake Adjustment", 50.0),
        ServiceItem("Chain Cleaning & Lube", 80.0),
        ServiceItem("Clutch Adjustment", 50.0),
        ServiceItem("Carburettor Cleaning", 150.0),
        ServiceItem("Electrical Work", 100.0),
        ServiceItem("Puncture Repair", 80.0),
        ServiceItem("Fitting Charge", 50.0),
        ServiceItem("Washing", 100.0)
    )

    /** Loads saved services and packages. The first time, a starter list of services is created. */
    suspend fun load(vm: AppViewModel) {
        lock.withLock {
            val dao = vm.dao
            val doc = dao.bill(SERVICES_ID)
            val list = if (doc == null) defaults else dao.items(SERVICES_ID).map { ServiceItem(it.name, it.price) }
            val ids = doc?.note.orEmpty().split(",").mapNotNull { it.trim().toLongOrNull() }
            val loaded = ids.mapNotNull { id -> dao.bill(id)?.let { b -> Pack(id, b.customer, dao.items(id)) } }
            services.clear()
            services.addAll(list)
            packs.clear()
            packs.addAll(loaded)
            if (doc == null) write(vm)
        }
    }

    private suspend fun write(vm: AppViewModel) {
        val dao = vm.dao
        dao.putBills(
            listOf(
                Bill(
                    id = SERVICES_ID, kind = KIND_SERVICES, date = now(), customer = "Saved services",
                    note = packs.joinToString(",") { it.id.toString() }
                )
            )
        )
        dao.deleteItems(SERVICES_ID)
        dao.insertItems(services.map { BillItem(billId = SERVICES_ID, name = it.name, qty = 1, price = it.price, gst = 18.0) })
    }

    private fun persist(vm: AppViewModel) {
        vm.viewModelScope.launch {
            try {
                lock.withLock { write(vm) }
            } catch (e: Exception) {
                vm.toast("Could not save the list")
            }
        }
    }

    // ---------- Services ----------

    fun saveService(vm: AppViewModel, old: ServiceItem?, name: String, price: Double) {
        val n = name.trim()
        if (n.isBlank()) return
        val item = ServiceItem(n, price)
        val i = if (old != null) services.indexOf(old) else services.indexOfFirst { it.name.equals(n, true) }
        if (i >= 0) services[i] = item else services.add(item)
        persist(vm)
    }

    fun deleteService(vm: AppViewModel, s: ServiceItem) {
        services.remove(s)
        persist(vm)
    }

    /** Remembers a labour item typed in a bill, so next time it is a one-tap button. */
    fun learn(vm: AppViewModel, name: String, price: Double) {
        val n = name.trim()
        if (n.isBlank() || price <= 0) return
        if (vm.parts.value.any { it.name.equals(n, true) }) return
        val i = services.indexOfFirst { it.name.equals(n, true) }
        if (i >= 0) {
            if (services[i].price != price) {
                services[i] = ServiceItem(services[i].name, price)
                persist(vm)
            }
        } else {
            services.add(ServiceItem(n, price))
            persist(vm)
        }
    }

    /** Adds one service to the current bill (or +1 if it is already there). */
    fun addService(vm: AppViewModel, s: ServiceItem) {
        vm.editDraft { x ->
            val ex = x.lines.find { it.partId == null && it.name.equals(s.name, true) }
            if (ex != null) {
                x.copy(lines = x.lines.map { if (it.id == ex.id) it.copy(qty = it.qty + 1) else it })
            } else {
                x.copy(lines = x.lines + BillItem(id = System.nanoTime(), partId = null, name = s.name, qty = 1, price = s.price, gst = 18.0))
            }
        }
    }

    // ---------- Packages ----------

    fun savePack(vm: AppViewModel, name: String, lines: List<BillItem>) {
        val n = name.trim()
        if (n.isBlank() || lines.isEmpty()) return
        vm.viewModelScope.launch {
            try {
                lock.withLock {
                    val id = (packs.minOfOrNull { it.id } ?: PACK_BASE) - 1
                    val clean = lines.map { it.copy(id = 0, billId = id) }
                    vm.dao.putBills(listOf(Bill(id = id, kind = KIND_PACK, date = now(), customer = n)))
                    vm.dao.deleteItems(id)
                    vm.dao.insertItems(clean)
                    packs.add(Pack(id, n, clean))
                    write(vm)
                }
                vm.toast("Package saved: $n ✓")
            } catch (e: Exception) {
                vm.toast("Could not save package")
            }
        }
    }

    fun deletePack(vm: AppViewModel, p: Pack) {
        vm.viewModelScope.launch {
            lock.withLock {
                vm.dao.deleteItems(p.id)
                vm.dao.deleteBill(p.id)
                packs.remove(p)
                write(vm)
            }
            vm.toast("Package deleted")
        }
    }

    /** Adds several items to the current bill (package / repeat last visit). Parts respect available stock. */
    fun addLines(vm: AppViewModel, lines: List<BillItem>, label: String) {
        val partsById = vm.parts.value.associateBy { it.id }
        val short = mutableListOf<String>()
        var added = 0
        lines.forEach { l ->
            val pid = l.partId
            if (pid != null) {
                val p = partsById[pid]
                if (p == null) {
                    short.add(l.name)
                    return@forEach
                }
                val have = vm.draft.lines.filter { it.partId == pid }.sumOf { it.qty }
                val can = (vm.limitFor(p) - have).coerceAtLeast(0)
                val q = minOf(l.qty, can)
                if (q <= 0) {
                    short.add(p.name)
                    return@forEach
                }
                vm.editDraft { x ->
                    val ex = x.lines.find { it.partId == pid }
                    if (ex != null) {
                        x.copy(lines = x.lines.map { if (it.id == ex.id) it.copy(qty = it.qty + q) else it })
                    } else {
                        x.copy(
                            lines = x.lines + BillItem(
                                id = System.nanoTime(), partId = pid, name = p.name, partNo = p.partNo,
                                qty = q, price = p.sellPrice, buyPrice = p.buyPrice, gst = p.gst
                            )
                        )
                    }
                }
                if (q < l.qty) short.add("${p.name} (only $q)")
                added++
            } else {
                vm.editDraft { x ->
                    val ex = x.lines.find { it.partId == null && it.name.equals(l.name, true) }
                    if (ex != null) {
                        x.copy(lines = x.lines.map { if (it.id == ex.id) it.copy(qty = it.qty + l.qty) else it })
                    } else {
                        x.copy(lines = x.lines + BillItem(id = System.nanoTime(), partId = null, name = l.name, qty = l.qty, price = l.price, gst = l.gst))
                    }
                }
                added++
            }
        }
        vm.toast(
            if (short.isEmpty()) "$label: $added items added ✓"
            else "$label added • Not enough stock: " + short.joinToString(", ")
        )
    }

    /** A part that is not in the stock list yet: saves it in stock and adds it to the bill. */
    fun newPartToBill(vm: AppViewModel, name: String, sell: Double, buy: Double, qtyInShop: Int) {
        vm.viewModelScope.launch {
            try {
                val p = Part(name = name.trim(), sellPrice = sell, buyPrice = buy, qty = qtyInShop.coerceAtLeast(1), updatedAt = now())
                val id = vm.dao.insertPart(p)
                vm.addToBill(p.copy(id = id))
                vm.toast("${p.name} saved in stock (${p.qty}) and added to the bill ✓")
            } catch (e: Exception) {
                vm.toast("Could not add the part")
            }
        }
    }
}
