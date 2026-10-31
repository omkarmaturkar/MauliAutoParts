package com.mauli.autoparts

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// ---------------- Database tables ----------------

@Entity(tableName = "parts")
data class Part(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val partNo: String = "",
    val category: String = "General",
    val brand: String = "",
    val vehicle: String = "",
    val rack: String = "",
    val unit: String = "pcs",
    val buyPrice: Double = 0.0,
    val sellPrice: Double = 0.0,
    val mrp: Double = 0.0,
    val qty: Int = 0,
    val minQty: Int = 2,
    val gst: Double = 18.0,
    val hsn: String = "",
    val supplier: String = "",
    val notes: String = "",
    val updatedAt: Long = 0
)

/**
 * A saved document.
 * kind 0 = sale bill (an estimate when isEstimate = true)
 * kind 1 = purchase from a vendor: customer = vendor name, phone = vendor phone, ref = vendor's bill no.
 */
@Entity(tableName = "bills", indices = [Index("date"), Index("vehicleNo"), Index("phone")])
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billNo: Int = 0,
    val isEstimate: Boolean = false,
    val date: Long = 0,
    val customer: String = "",
    val phone: String = "",
    val vehicleNo: String = "",
    val model: String = "",
    val km: String = "",
    val subTotal: Double = 0.0,
    val discount: Double = 0.0,
    val total: Double = 0.0,
    val paid: Double = 0.0,
    val payMode: String = "Cash",
    val profit: Double = 0.0,
    val note: String = "",
    val kind: Int = 0,
    val ref: String = ""
)

/**
 * One line of a bill.
 * For purchase lines: listPrice = the vendor's rate before discount, discPct = discount % given on it,
 * price (and buyPrice) = the rate you really pay after the discount.
 * Sale lines leave listPrice and discPct at 0.
 */
@Entity(tableName = "bill_items", indices = [Index("billId"), Index("partId")])
data class BillItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long = 0,
    val partId: Long? = null,
    val name: String = "",
    val partNo: String = "",
    val qty: Int = 1,
    val price: Double = 0.0,
    val buyPrice: Double = 0.0,
    val gst: Double = 0.0,
    val listPrice: Double = 0.0,
    val discPct: Double = 0.0
)

data class TopSeller(val name: String, val qty: Int, val amount: Double)

/** One purchase of a part, for the part's "purchased from" history. */
data class PartPurchase(val id: Long, val date: Long, val vendor: String, val qty: Int, val rate: Double)

@Dao
interface AppDao {
    @Query("SELECT * FROM parts ORDER BY name COLLATE NOCASE")
    fun partsFlow(): Flow<List<Part>>

    @Query("SELECT * FROM parts")
    suspend fun allParts(): List<Part>

    @Query("SELECT * FROM parts WHERE id = :id")
    suspend fun part(id: Long): Part?

    @Insert
    suspend fun insertPart(p: Part): Long

    @Update
    suspend fun updatePart(p: Part)

    @Delete
    suspend fun deletePart(p: Part)

    @Query("UPDATE parts SET qty = qty + :delta, updatedAt = :ts WHERE id = :id")
    suspend fun changeQty(id: Long, delta: Int, ts: Long)

    @Query("UPDATE parts SET buyPrice = :price WHERE id = :id")
    suspend fun setBuyPrice(id: Long, price: Double)

    @Query("UPDATE parts SET supplier = :supplier WHERE id = :id")
    suspend fun setSupplier(id: Long, supplier: String)

    @Query("SELECT * FROM bills WHERE kind = 0 ORDER BY date DESC")
    fun billsFlow(): Flow<List<Bill>>

    @Query("SELECT * FROM bills WHERE kind = 1 ORDER BY date DESC")
    fun purchasesFlow(): Flow<List<Bill>>

    @Query("SELECT * FROM bills")
    suspend fun allBills(): List<Bill>

    @Query("SELECT * FROM bills WHERE id = :id")
    suspend fun bill(id: Long): Bill?

    @Query("SELECT * FROM bills WHERE id = :id")
    fun billFlow(id: Long): Flow<Bill?>

    @Query("SELECT * FROM bill_items WHERE billId = :billId ORDER BY id")
    fun itemsFlow(billId: Long): Flow<List<BillItem>>

    @Query("SELECT * FROM bill_items WHERE billId = :billId ORDER BY id")
    suspend fun items(billId: Long): List<BillItem>

    @Query("SELECT * FROM bill_items")
    suspend fun allItems(): List<BillItem>

    @Query("SELECT IFNULL(MAX(billNo), 0) + 1 FROM bills WHERE isEstimate = :estimate AND kind = 0")
    suspend fun nextNo(estimate: Boolean): Int

    @Query("SELECT IFNULL(MAX(billNo), 0) + 1 FROM bills WHERE kind = 1")
    suspend fun nextPurchaseNo(): Int

    @Insert
    suspend fun insertBill(b: Bill): Long

    @Update
    suspend fun updateBill(b: Bill)

    @Insert
    suspend fun insertItems(items: List<BillItem>)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteBill(id: Long)

    @Query("DELETE FROM bill_items WHERE billId = :billId")
    suspend fun deleteItems(billId: Long)

    @Query("SELECT * FROM bills WHERE kind = 0 AND ((:vehicle != '' AND vehicleNo = :vehicle) OR (:phone != '' AND phone = :phone)) ORDER BY date DESC LIMIT 1")
    suspend fun lastBillOf(vehicle: String, phone: String): Bill?

    @Query("SELECT bi.name AS name, SUM(bi.qty) AS qty, SUM(bi.qty * bi.price) AS amount FROM bill_items bi INNER JOIN bills b ON b.id = bi.billId WHERE b.kind = 0 AND b.isEstimate = 0 AND b.date >= :fromTime AND b.date < :toTime GROUP BY bi.name ORDER BY SUM(bi.qty) DESC LIMIT 10")
    fun topSellers(fromTime: Long, toTime: Long): Flow<List<TopSeller>>

    @Query("SELECT bi.partId FROM bill_items bi INNER JOIN bills b ON b.id = bi.billId WHERE bi.partId IS NOT NULL AND b.kind = 0 AND b.isEstimate = 0 GROUP BY bi.partId ORDER BY SUM(bi.qty) DESC LIMIT 12")
    fun frequentPartIds(): Flow<List<Long>>

    @Query("SELECT b.id AS id, b.date AS date, b.customer AS vendor, bi.qty AS qty, bi.price AS rate FROM bill_items bi INNER JOIN bills b ON b.id = bi.billId WHERE b.kind = 1 AND bi.partId = :partId ORDER BY b.date DESC LIMIT 20")
    fun partPurchases(partId: Long): Flow<List<PartPurchase>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putParts(list: List<Part>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBills(list: List<Bill>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putItems(list: List<BillItem>)

    @Query("DELETE FROM parts")
    suspend fun clearParts()

    @Query("DELETE FROM bills")
    suspend fun clearBills()

    @Query("DELETE FROM bill_items")
    suspend fun clearItems()
}

/** Upgrades the old database (version 1) without losing any parts or bills. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bills ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE bills ADD COLUMN ref TEXT NOT NULL DEFAULT ''")
    }
}

/** Version 3: purchase lines remember the vendor's list rate and the discount % given on it. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bill_items ADD COLUMN listPrice REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE bill_items ADD COLUMN discPct REAL NOT NULL DEFAULT 0")
    }
}

@Database(entities = [Part::class, Bill::class, BillItem::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "mauli_auto_parts.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}

// ---------------- App models ----------------

/** An open (not yet saved) bill. editId != 0 means it is changing a saved bill. */
data class Draft(
    val id: Long = System.nanoTime(),
    val lines: List<BillItem> = emptyList(),
    val customer: String = "",
    val phone: String = "",
    val vehicleNo: String = "",
    val model: String = "",
    val km: String = "",
    val discount: String = "",
    val received: String = "",
    val payMode: String = "Cash",
    val note: String = "",
    val editId: Long = 0,
    val editCode: String = "",
    val orig: Map<Long, Int> = emptyMap()
)

/** What the purchase screen sends to be saved. */
data class PurchaseInput(
    val vendor: String,
    val phone: String,
    val ref: String,
    val date: Long,
    val lines: List<BillItem>,
    val discount: Double,
    val paid: Double,
    val payMode: String,
    val note: String
)

data class ShopSettings(
    val shopName: String = "Mauli Auto Parts",
    val tagline: String = "Two & Four Wheeler Spare Parts",
    val address: String = "",
    val phone: String = "",
    val gstin: String = "",
    val upi: String = "",
    val footer: String = "Thank you! Visit again.",
    val showGst: Boolean = false,
    val paper: Int = 58,
    val printerName: String = "",
    val printerAddress: String = "",
    val autoPrint: Boolean = false
)

data class Totals(
    val sub: Double, val discount: Double, val total: Double,
    val cost: Double, val taxable: Double, val tax: Double
) {
    val profit: Double get() = total - cost
}

/** Prices are GST-inclusive. Discount is spread proportionally across items. */
fun calcTotals(items: List<BillItem>, discount: Double): Totals {
    val sub = items.sumOf { it.qty * it.price }
    val disc = discount.coerceIn(0.0, sub)
    val total = sub - disc
    val factor = if (sub > 0) total / sub else 0.0
    var taxable = 0.0
    items.forEach { taxable += it.qty * it.price * factor * 100.0 / (100.0 + it.gst) }
    return Totals(sub, disc, total, items.sumOf { it.qty * it.buyPrice }, taxable, total - taxable)
}

// ---------------- Small helpers ----------------

fun now(): Long = System.currentTimeMillis()

fun String.toNum(): Double = replace(",", "").replace("₹", "").replace("%", "")
    .replace("Rs.", "", ignoreCase = true).replace("Rs", "", ignoreCase = true)
    .trim().toDoubleOrNull() ?: 0.0

fun num(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

fun normVehicle(v: String): String = v.uppercase().replace(" ", "").replace("-", "")

fun billCode(b: Bill): String {
    val prefix = when {
        b.kind == 1 -> "PUR-"
        b.isEstimate -> "EST-"
        else -> "INV-"
    }
    return prefix + b.billNo.toString().padStart(4, '0')
}

/** Rate after a vendor discount, rounded to paise: 100 at 10% -> 90. */
fun netRate(list: Double, pct: Double): Double =
    Math.round(list * (1.0 - pct.coerceIn(0.0, 100.0) / 100.0) * 100.0) / 100.0

/** The vendor's rate before discount for a purchase line (older lines had no discount). */
val BillItem.listRate: Double get() = if (listPrice > 0.0) listPrice else price

// ---------------- JSON (backup + saved drafts) ----------------

fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

fun Part.toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("partNo", partNo)
    .put("category", category).put("brand", brand).put("vehicle", vehicle).put("rack", rack).put("unit", unit)
    .put("buyPrice", buyPrice).put("sellPrice", sellPrice).put("mrp", mrp).put("qty", qty).put("minQty", minQty)
    .put("gst", gst).put("hsn", hsn).put("supplier", supplier).put("notes", notes).put("updatedAt", updatedAt)

fun partOf(o: JSONObject) = Part(
    id = o.optLong("id"), name = o.optString("name"), partNo = o.optString("partNo"),
    category = o.optString("category", "General"), brand = o.optString("brand"), vehicle = o.optString("vehicle"),
    rack = o.optString("rack"), unit = o.optString("unit", "pcs"), buyPrice = o.optDouble("buyPrice", 0.0),
    sellPrice = o.optDouble("sellPrice", 0.0), mrp = o.optDouble("mrp", 0.0), qty = o.optInt("qty"),
    minQty = o.optInt("minQty", 2), gst = o.optDouble("gst", 18.0), hsn = o.optString("hsn"),
    supplier = o.optString("supplier"), notes = o.optString("notes"), updatedAt = o.optLong("updatedAt")
)

fun Bill.toJson(): JSONObject = JSONObject().put("id", id).put("billNo", billNo).put("isEstimate", isEstimate)
    .put("date", date).put("customer", customer).put("phone", phone).put("vehicleNo", vehicleNo)
    .put("model", model).put("km", km).put("subTotal", subTotal).put("discount", discount).put("total", total)
    .put("paid", paid).put("payMode", payMode).put("profit", profit).put("note", note)
    .put("kind", kind).put("ref", ref)

fun billOf(o: JSONObject) = Bill(
    id = o.optLong("id"), billNo = o.optInt("billNo"), isEstimate = o.optBoolean("isEstimate"),
    date = o.optLong("date"), customer = o.optString("customer"), phone = o.optString("phone"),
    vehicleNo = o.optString("vehicleNo"), model = o.optString("model"), km = o.optString("km"),
    subTotal = o.optDouble("subTotal", 0.0), discount = o.optDouble("discount", 0.0),
    total = o.optDouble("total", 0.0), paid = o.optDouble("paid", 0.0), payMode = o.optString("payMode", "Cash"),
    profit = o.optDouble("profit", 0.0), note = o.optString("note"),
    kind = o.optInt("kind", 0), ref = o.optString("ref")
)

fun BillItem.toJson(): JSONObject = JSONObject().put("id", id).put("billId", billId).put("partId", partId ?: -1L)
    .put("name", name).put("partNo", partNo).put("qty", qty).put("price", price)
    .put("buyPrice", buyPrice).put("gst", gst).put("listPrice", listPrice).put("discPct", discPct)

fun itemOf(o: JSONObject) = BillItem(
    id = o.optLong("id"), billId = o.optLong("billId"), partId = o.optLong("partId", -1L).takeIf { it > 0 },
    name = o.optString("name"), partNo = o.optString("partNo"), qty = o.optInt("qty", 1),
    price = o.optDouble("price", 0.0), buyPrice = o.optDouble("buyPrice", 0.0), gst = o.optDouble("gst", 0.0),
    listPrice = o.optDouble("listPrice", 0.0), discPct = o.optDouble("discPct", 0.0)
)

fun ShopSettings.toJson(): JSONObject = JSONObject().put("shopName", shopName).put("tagline", tagline)
    .put("address", address).put("phone", phone).put("gstin", gstin).put("upi", upi).put("footer", footer)
    .put("showGst", showGst).put("paper", paper).put("printerName", printerName)
    .put("printerAddress", printerAddress).put("autoPrint", autoPrint)

fun settingsOf(o: JSONObject): ShopSettings {
    val d = ShopSettings()
    return ShopSettings(
        shopName = o.optString("shopName", d.shopName), tagline = o.optString("tagline", d.tagline),
        address = o.optString("address"), phone = o.optString("phone"), gstin = o.optString("gstin"),
        upi = o.optString("upi"), footer = o.optString("footer", d.footer), showGst = o.optBoolean("showGst"),
        paper = o.optInt("paper", 58), printerName = o.optString("printerName"),
        printerAddress = o.optString("printerAddress"), autoPrint = o.optBoolean("autoPrint")
    )
}

fun Draft.toJson(): JSONObject = JSONObject().put("id", id).put("lines", JSONArray(lines.map { it.toJson() }))
    .put("customer", customer).put("phone", phone).put("vehicleNo", vehicleNo).put("model", model)
    .put("km", km).put("discount", discount).put("received", received).put("payMode", payMode).put("note", note)
    .put("editId", editId).put("editCode", editCode)
    .put("orig", JSONObject().also { o -> orig.forEach { (k, v) -> o.put(k.toString(), v) } })

fun draftOf(o: JSONObject) = Draft(
    id = o.optLong("id", System.nanoTime()), lines = o.optJSONArray("lines").mapObjects { itemOf(it) },
    customer = o.optString("customer"), phone = o.optString("phone"), vehicleNo = o.optString("vehicleNo"),
    model = o.optString("model"), km = o.optString("km"), discount = o.optString("discount"),
    received = o.optString("received"), payMode = o.optString("payMode", "Cash"), note = o.optString("note"),
    editId = o.optLong("editId", 0L), editCode = o.optString("editCode"),
    orig = o.optJSONObject("orig")?.let { j ->
        j.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to j.optInt(k) } }.toMap()
    } ?: emptyMap()
)

// ---------------- Excel (CSV) ----------------

val CSV_HEADER = listOf(
    "Name", "Part No", "Category", "Brand", "Vehicle", "Rack", "Unit", "Buy Price",
    "Sell Price", "MRP", "Qty", "Min Qty", "GST %", "HSN", "Supplier", "Notes"
)

fun Part.toCsvRow(): List<String> = listOf(
    name, partNo, category, brand, vehicle, rack, unit, num(buyPrice), num(sellPrice), num(mrp),
    qty.toString(), minQty.toString(), num(gst), hsn, supplier, notes
)

fun csvLine(cells: List<String>): String = cells.joinToString(",") { c ->
    if (c.any { it == ',' || it == '"' || it == '\n' }) "\"" + c.replace("\"", "\"\"") + "\"" else c
}

fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (inQuotes) {
            if (ch == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else inQuotes = false
            } else cell.append(ch)
        } else when (ch) {
            '"' -> inQuotes = true
            ',' -> { row.add(cell.toString()); cell.clear() }
            '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf() }
            '\r' -> {}
            else -> cell.append(ch)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
    return rows
}
