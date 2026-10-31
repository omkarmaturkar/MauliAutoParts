package com.mauli.autoparts

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.util.zip.ZipInputStream

/** One sheet of the chosen file: its name and its rows. */
class SheetData(val name: String, val rows: List<List<String>>)

/** Everything read from the chosen file. */
class WorkbookData(val sheets: List<SheetData>, val kind: String)

/** A part detail that a column of the sheet can fill. */
enum class ImportField(val label: String) {
    PartNo("Part number"),
    Name("Part name"),
    SellPrice("Sell price"),
    Mrp("MRP"),
    BuyPrice("Buy price"),
    Qty("Quantity in stock"),
    Category("Category"),
    Brand("Brand"),
    Vehicle("Fits vehicle"),
    Rack("Rack / shelf"),
    Unit("Unit"),
    MinQty("Low stock alert at"),
    Gst("GST %"),
    Hsn("HSN code"),
    Supplier("Supplier"),
    Notes("Notes")
}

/** The three the shop really needs. The rest are extra. */
val MainFields = listOf(ImportField.PartNo, ImportField.Name, ImportField.SellPrice)
val ExtraFields = ImportField.values().filter { it !in MainFields }

/** One row of the sheet, holding only the cells that were filled in. */
class ParsedRow(val values: Map<ImportField, String>) {
    fun get(f: ImportField): String = values[f].orEmpty()
}

class ImportResult(val added: Int, val updated: Int)

private class Hints(val good: List<String>, val bad: List<String> = emptyList())

private class Guess(val field: ImportField, val column: Int, val score: Int)

object ExcelImport {
    private const val MAX_BYTES = 16 * 1024 * 1024

    private val HINTS = mapOf(
        ImportField.PartNo to Hints(
            listOf(
                "part no", "part number", "partno", "part code", "partcode", "item code", "itemcode",
                "product code", "cat no", "catalogue no", "oem", "oem no", "sku", "article", "code", "p no", "pno"
            ),
            listOf("hsn", "sac", "bill", "invoice", "phone", "mobile", "sr no", "s no", "sl no", "srno", "serial")
        ),
        ImportField.Name to Hints(
            listOf(
                "part name", "item name", "product name", "description of goods", "description",
                "particulars", "particular", "item", "product", "material", "name", "parts", "goods"
            ),
            listOf("brand", "company", "vendor", "supplier", "category", "customer", "unit", "code", "number")
        ),
        ImportField.SellPrice to Hints(
            listOf(
                "sell price", "selling price", "sale price", "sales price", "sell", "retail price",
                "customer price", "s p", "sp", "rate", "price"
            ),
            listOf("buy", "purchase", "cost", "dealer", "landing", "mrp", "total", "amount", "value", "discount", "net")
        ),
        ImportField.Mrp to Hints(listOf("mrp", "m r p", "max retail", "maximum retail", "list price", "printed price")),
        ImportField.BuyPrice to Hints(
            listOf(
                "buy price", "purchase price", "purchase rate", "cost price", "dealer price", "net rate",
                "landing", "basic rate", "buy", "purchase", "cost", "d p", "dp"
            ),
            listOf("sell", "retail", "mrp")
        ),
        ImportField.Qty to Hints(
            listOf("qty", "quantity", "stock", "closing stock", "closing", "balance", "available", "on hand", "pcs", "nos"),
            listOf("min", "reorder", "low", "sold", "order")
        ),
        ImportField.MinQty to Hints(listOf("min qty", "minimum qty", "min stock", "reorder", "re order", "low stock", "minimum", "min")),
        ImportField.Gst to Hints(listOf("gst", "gst %", "tax", "tax %", "vat", "igst"), listOf("amount", "value")),
        ImportField.Hsn to Hints(listOf("hsn", "hsn code", "sac", "hsn sac")),
        ImportField.Category to Hints(listOf("category", "catagory", "group", "section", "class", "type")),
        ImportField.Brand to Hints(listOf("brand", "company", "make", "manufacturer", "mfg")),
        ImportField.Vehicle to Hints(listOf("vehicle", "model", "application", "suitable for", "fits", "bike", "car")),
        ImportField.Rack to Hints(listOf("rack", "shelf", "location", "bin", "position", "place")),
        ImportField.Unit to Hints(listOf("unit", "uom", "u o m")),
        ImportField.Supplier to Hints(listOf("supplier", "vendor", "dealer", "party", "seller")),
        ImportField.Notes to Hints(listOf("note", "notes", "remark", "remarks", "comment"))
    )

    // ---------------- Reading the file ----------------

    /** Reads an .xlsx, .csv or text sheet. Throws a plain message the shop can understand. */
    suspend fun read(context: Context, uri: Uri): WorkbookData = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not open that file")
        if (bytes.isEmpty()) throw IOException("That file is empty")
        if (bytes.size > MAX_BYTES) throw IOException("That file is too big")
        when {
            isOldExcel(bytes) -> throw IOException("This is an old .xls file. Open it in Excel and use Save As → Excel Workbook (.xlsx) or CSV.")
            isZip(bytes) -> readXlsx(bytes)
            else -> readTextSheet(bytes)
        }
    }

    private fun isZip(b: ByteArray): Boolean =
        b.size > 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()

    private fun isOldExcel(b: ByteArray): Boolean =
        b.size > 8 && b[0] == 0xD0.toByte() && b[1] == 0xCF.toByte() && b[2] == 0x11.toByte()

    private fun readTextSheet(bytes: ByteArray): WorkbookData {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val head = text.take(5000)
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> head.count { it == d } } ?: ','
        val rows = parseDelimited(text, if (head.count { it == delim } == 0) ',' else delim)
        if (rows.isEmpty()) throw IOException("No rows found in that file")
        return WorkbookData(listOf(SheetData("Sheet 1", rows)), "text")
    }

    private fun readXlsx(bytes: ByteArray): WorkbookData {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val n = entry.name
                val wanted = n == "xl/sharedStrings.xml" || n == "xl/workbook.xml" ||
                    (n.startsWith("xl/worksheets/") && n.endsWith(".xml") && !n.contains("/_rels/"))
                if (wanted) entries[n] = zin.readBytes()
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        val files = entries.keys
            .filter { it.startsWith("xl/worksheets/") }
            .sortedBy { name -> name.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        if (files.isEmpty()) throw IOException("No sheet found inside that Excel file")
        val shared = entries["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val names = entries["xl/workbook.xml"]?.let { sheetNames(it) } ?: emptyList()
        val sheets = files.mapIndexed { i, f ->
            SheetData(names.getOrNull(i)?.ifBlank { null } ?: "Sheet ${i + 1}", parseSheet(entries[f]!!, shared))
        }.filter { it.rows.isNotEmpty() }
        if (sheets.isEmpty()) throw IOException("All sheets in that file are empty")
        return WorkbookData(sheets, "excel")
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = Xml.newPullParser()
        p.setInput(bytes.inputStream(), null)
        val sb = StringBuilder()
        var inSi = false
        var inT = false
        var inRuby = 0
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> {
                        inSi = true
                        sb.setLength(0)
                    }
                    "rPh" -> inRuby++
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inSi && inT && inRuby == 0) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "rPh" -> if (inRuby > 0) inRuby--
                    "si" -> {
                        out.add(sb.toString())
                        inSi = false
                    }
                }
            }
            e = p.next()
        }
        return out
    }

    private fun sheetNames(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val p = Xml.newPullParser()
        p.setInput(bytes.inputStream(), null)
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && p.name == "sheet") {
                out.add(p.getAttributeValue(null, "name").orEmpty())
            }
            e = p.next()
        }
        return out
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val p = Xml.newPullParser()
        p.setInput(bytes.inputStream(), null)
        var cells: ArrayList<String>? = null
        var col = -1
        var type = ""
        val text = StringBuilder()
        var capture = false
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> cells = ArrayList()
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        col = if (ref.isNullOrBlank()) -1 else columnOf(ref)
                        type = p.getAttributeValue(null, "t").orEmpty()
                        text.setLength(0)
                        capture = false
                    }
                    "v", "t" -> capture = true
                }
                XmlPullParser.TEXT -> if (capture) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> capture = false
                    "c" -> cells?.let { list ->
                        val raw = text.toString()
                        val value = when (type) {
                            "s" -> shared.getOrElse(raw.trim().toIntOrNull() ?: -1) { "" }
                            "inlineStr", "str" -> raw
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            else -> tidyNumber(raw)
                        }
                        val at = if (col < 0) list.size else col
                        while (list.size < at) list.add("")
                        if (list.size == at) list.add(value) else list[at] = value
                    }
                    "row" -> {
                        cells?.let { if (it.any { c -> c.isNotBlank() }) rows.add(it) }
                        cells = null
                    }
                }
            }
            e = p.next()
        }
        return rows
    }

    /** A1 -> 0, B2 -> 1, AA7 -> 26 */
    private fun columnOf(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return (n - 1).coerceAtLeast(0)
    }

    private fun tidyNumber(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val d = t.toDoubleOrNull() ?: return t
        return num(d)
    }

    private fun parseDelimited(text: String, delim: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else inQuotes = false
                } else cell.append(ch)
            } else when (ch) {
                '"' -> inQuotes = true
                delim -> {
                    row.add(cell.toString())
                    cell.clear()
                }
                '\n' -> {
                    row.add(cell.toString())
                    cell.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                '\r' -> {}
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
        }
        return rows
    }

    // ---------------- Working out the columns ----------------

    private fun score(header: String, h: Hints): Int {
        val t = header.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return 0
        if (h.bad.any { t.contains(it) }) return 0
        val words = t.split(" ")
        var best = 0
        h.good.forEachIndexed { i, key ->
            val bonus = (h.good.size - i).coerceAtMost(9)
            val s = when {
                t == key -> 100 + bonus
                words.contains(key) -> 70 + bonus
                t.contains(key) -> 50 + bonus
                else -> 0
            }
            if (s > best) best = s
        }
        return best
    }

    /** Matches the heading row to part details. Columns it is unsure about are left out. */
    fun detect(headers: List<String>): Map<ImportField, Int> {
        val guesses = ArrayList<Guess>()
        ImportField.values().forEach { f ->
            val h = HINTS[f] ?: return@forEach
            headers.forEachIndexed { i, head ->
                val s = score(head, h)
                if (s >= 50) guesses.add(Guess(f, i, s))
            }
        }
        val out = LinkedHashMap<ImportField, Int>()
        val used = HashSet<Int>()
        guesses.sortedByDescending { it.score }.forEach { g ->
            if (!out.containsKey(g.field) && !used.contains(g.column)) {
                out[g.field] = g.column
                used.add(g.column)
            }
        }
        return out
    }

    /** True when the first row is headings rather than a part. */
    fun looksLikeHeading(row: List<String>): Boolean {
        val m = detect(row)
        return m.containsKey(ImportField.Name) || m.containsKey(ImportField.PartNo) || m.size >= 3
    }

    // ---------------- Turning rows into parts ----------------

    fun parseRows(rows: List<List<String>>, map: Map<ImportField, Int>, skipFirst: Boolean): List<ParsedRow> {
        val body = if (skipFirst) rows.drop(1) else rows
        val out = ArrayList<ParsedRow>()
        body.forEach { row ->
            val values = LinkedHashMap<ImportField, String>()
            map.forEach { (f, col) ->
                val v = row.getOrElse(col) { "" }.trim()
                if (v.isNotEmpty()) values[f] = v
            }
            val hasName = values[ImportField.Name]?.isNotBlank() == true
            val hasNo = values[ImportField.PartNo]?.isNotBlank() == true
            if (hasName || hasNo) out.add(ParsedRow(values))
        }
        return out
    }

    /** Finds the part this row belongs to: by part number first, then by name. */
    fun matchExisting(byNo: Map<String, Part>, byName: Map<String, Part>, r: ParsedRow): Part? {
        val no = r.get(ImportField.PartNo).trim().lowercase()
        if (no.isNotEmpty()) byNo[no]?.let { return it }
        val name = r.get(ImportField.Name).trim().lowercase()
        if (name.isNotEmpty()) return byName[name]
        return null
    }

    /** Only the details present in the sheet are changed. Everything else stays as it is. */
    fun toPart(r: ParsedRow, existing: Part?): Part {
        var p = existing ?: Part()
        r.values.forEach { (f, raw) ->
            val v = raw.trim()
            p = when (f) {
                ImportField.PartNo -> p.copy(partNo = v)
                ImportField.Name -> p.copy(name = v)
                ImportField.SellPrice -> p.copy(sellPrice = v.toNum())
                ImportField.Mrp -> p.copy(mrp = v.toNum())
                ImportField.BuyPrice -> p.copy(buyPrice = v.toNum())
                ImportField.Qty -> p.copy(qty = v.toNum().toInt())
                ImportField.MinQty -> p.copy(minQty = v.toNum().toInt())
                ImportField.Gst -> p.copy(gst = v.toNum())
                ImportField.Hsn -> p.copy(hsn = v)
                ImportField.Category -> p.copy(category = v)
                ImportField.Brand -> p.copy(brand = v)
                ImportField.Vehicle -> p.copy(vehicle = v)
                ImportField.Rack -> p.copy(rack = v.uppercase())
                ImportField.Unit -> p.copy(unit = v.lowercase())
                ImportField.Supplier -> p.copy(supplier = v)
                ImportField.Notes -> p.copy(notes = v)
            }
        }
        if (p.name.isBlank()) p = p.copy(name = p.partNo)
        if (p.sellPrice <= 0.0 && p.mrp > 0.0) p = p.copy(sellPrice = p.mrp)
        if (p.category.isBlank()) p = p.copy(category = "General")
        if (p.unit.isBlank()) p = p.copy(unit = "pcs")
        return p.copy(updatedAt = now())
    }

    /** Saves everything in one safe step. Parts already in stock are updated, not repeated. */
    suspend fun apply(context: Context, rows: List<ParsedRow>): ImportResult = withContext(Dispatchers.IO) {
        val db = AppDb.get(context)
        val dao = db.dao()
        var added = 0
        var updated = 0
        db.withTransaction {
            val byNo = HashMap<String, Part>()
            val byName = HashMap<String, Part>()
            dao.allParts().forEach { p ->
                if (p.partNo.isNotBlank()) byNo[p.partNo.trim().lowercase()] = p
                if (p.name.isNotBlank()) byName[p.name.trim().lowercase()] = p
            }
            rows.forEach { r ->
                val old = matchExisting(byNo, byName, r)
                val part = toPart(r, old)
                val saved: Part
                if (old == null) {
                    val id = dao.insertPart(part)
                    saved = part.copy(id = id)
                    added++
                } else {
                    dao.updatePart(part)
                    saved = part
                    updated++
                }
                if (saved.partNo.isNotBlank()) byNo[saved.partNo.trim().lowercase()] = saved
                if (saved.name.isNotBlank()) byName[saved.name.trim().lowercase()] = saved
            }
        }
        ImportResult(added, updated)
    }
}
