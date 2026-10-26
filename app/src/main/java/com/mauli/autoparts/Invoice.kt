package com.mauli.autoparts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// ================= Shared invoice helpers =================

/** Indian number format with 2 decimals: 1,23,456.50 */
fun inr2(v: Double): String {
    val r = Math.round(abs(v) * 100)
    val whole = (r / 100).toString()
    val grouped = if (whole.length <= 3) whole
    else whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
    val sign = if (v < 0 && r != 0L) "-" else ""
    return sign + grouped + "." + (r % 100).toString().padStart(2, '0')
}

/** Payment mode as printed on the bill. */
fun payLabel(b: Bill): String = when {
    b.isEstimate -> "-"
    b.payMode == "Udhaar" -> "Credit"
    else -> b.payMode
}

/** One line of the GST summary (per HSN code and tax rate). */
class TaxRow(val hsn: String, val rate: Double, val taxable: Double, val tax: Double)

fun taxRows(items: List<BillItem>, discount: Double, parts: Map<Long, Part>): List<TaxRow> {
    val sub = items.sumOf { it.qty * it.price }
    val factor = if (sub > 0) (sub - discount.coerceIn(0.0, sub)) / sub else 0.0
    return items.groupBy { (it.partId?.let { id -> parts[id]?.hsn } ?: "").ifBlank { "-" } to it.gst }
        .map { (key, list) ->
            val gross = list.sumOf { it.qty * it.price } * factor
            val taxable = gross * 100.0 / (100.0 + key.second)
            TaxRow(key.first, key.second, taxable, gross - taxable)
        }
}

// ================= Thermal printer helpers =================

private fun posAscii(s: String): String =
    s.replace("₹", "Rs.").map { c -> if (c == '\n' || c.code in 32..126) c else '?' }.joinToString("")

private fun posWrap(text: String, width: Int): List<String> {
    val lines = mutableListOf<String>()
    var cur = ""
    for (w0 in text.trim().split(Regex("\\s+"))) {
        var w = w0
        while (w.length > width) {
            if (cur.isNotEmpty()) {
                lines.add(cur)
                cur = ""
            }
            lines.add(w.take(width))
            w = w.drop(width)
        }
        if (w.isEmpty()) continue
        cur = when {
            cur.isEmpty() -> w
            cur.length + 1 + w.length <= width -> "$cur $w"
            else -> {
                lines.add(cur)
                w
            }
        }
    }
    if (cur.isNotEmpty()) lines.add(cur)
    return lines.ifEmpty { listOf("") }
}

/** Item | Qty | Rate | Amount line for 80 mm paper. */
private fun row4(a: String, q: String, r: String, m: String, w: Int): String {
    val wq = 5
    val wr = 10
    val wm = 12
    val wa = w - wq - wr - wm
    return a.take(wa).padEnd(wa) + q.take(wq).padStart(wq) + r.take(wr).padStart(wr) + m.take(wm).padStart(wm)
}

/** QR code as printer dots, centred on the paper. */
private fun posQr(text: String, size: Int, paperDots: Int): ByteArray {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val widthBytes = paperDots / 8
    val left = ((paperDots - m.width) / 2).coerceAtLeast(0)
    val out = ByteArrayOutputStream()
    out.write(0x1D); out.write(0x76); out.write(0x30); out.write(0x00)
    out.write(widthBytes and 0xFF); out.write((widthBytes shr 8) and 0xFF)
    out.write(m.height and 0xFF); out.write((m.height shr 8) and 0xFF)
    for (y in 0 until m.height) {
        val row = ByteArray(widthBytes)
        for (x in 0 until m.width) {
            val px = left + x
            if (px < paperDots && m.get(x, y)) row[px / 8] = (row[px / 8].toInt() or (0x80 shr (px % 8))).toByte()
        }
        out.write(row, 0, row.size)
    }
    return out.toByteArray()
}

private class PosBuilder(val width: Int) {
    private val out = ByteArrayOutputStream()
    fun cmd(vararg v: Int): PosBuilder {
        v.forEach { out.write(it) }
        return this
    }
    fun raw(bytes: ByteArray): PosBuilder {
        out.write(bytes, 0, bytes.size)
        return this
    }
    fun line(s: String = ""): PosBuilder = raw((posAscii(s) + "\n").toByteArray(Charsets.US_ASCII))
    fun center(): PosBuilder = cmd(0x1B, 0x61, 1)
    fun left(): PosBuilder = cmd(0x1B, 0x61, 0)
    fun bold(on: Boolean): PosBuilder = cmd(0x1B, 0x45, if (on) 1 else 0)
    fun size(n: Int): PosBuilder = cmd(0x1D, 0x21, n)
    fun rule(ch: Char = '-'): PosBuilder = line(ch.toString().repeat(width))
    fun wrap(s: String): PosBuilder {
        posWrap(s, width).forEach { line(it) }
        return this
    }
    fun lr(l: String, r: String): PosBuilder {
        val right = r.take(width)
        val room = (width - right.length - 1).coerceAtLeast(0)
        val left = l.take(room)
        return line(left + " ".repeat((width - left.length - right.length).coerceAtLeast(0)) + right)
    }
    fun bytes(): ByteArray = out.toByteArray()
}

// ================= BUSY-style invoice =================

object Invoice {
    private const val PW = 595
    private const val PH = 842
    private const val L = 28f
    private const val R = 567f
    private const val T = 30f
    private const val B = 810f
    private const val SPLIT = 318f

    private class Col(val title: String, val l: Float, val r: Float, val align: Paint.Align)
    private class ItemLine(val cells: List<String>, val desc: List<String>, val sub: String, val h: Float)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 0.8f
        style = Paint.Style.STROKE
    }
    private val shade = Paint().apply {
        color = Color.rgb(236, 236, 236)
        style = Paint.Style.FILL
    }

    private fun pen(size: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = size
            textAlign = align
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun wrap(text: String, p: Paint, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        text.split("\n").forEach { para ->
            var rest = para.trim()
            while (rest.isNotEmpty()) {
                var n = p.breakText(rest, true, maxW, null)
                if (n <= 0) n = 1
                if (n < rest.length) {
                    val sp = rest.lastIndexOf(' ', n)
                    if (sp > 0) n = sp
                }
                out.add(rest.substring(0, n).trim())
                rest = rest.substring(n).trim()
            }
        }
        return out.ifEmpty { listOf("") }
    }

    private fun fit(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        val n = p.breakText(text, true, maxW - p.measureText(".."), null).coerceAtLeast(0)
        return text.take(n) + ".."
    }

    private fun hline(c: Canvas, y: Float) {
        c.drawLine(L, y, R, y, stroke)
    }

    private fun cell(c: Canvas, text: String, col: Col, base: Float, p: Paint, align: Paint.Align = col.align) {
        if (text.isEmpty()) return
        val pp = Paint(p).apply { textAlign = align }
        val x = when (align) {
            Paint.Align.LEFT -> col.l + 4f
            Paint.Align.RIGHT -> col.r - 4f
            else -> (col.l + col.r) / 2f
        }
        c.drawText(fit(text, pp, col.r - col.l - 6f), x, base, pp)
    }

    private fun title(b: Bill, s: ShopSettings): String = if (b.kind == 1) "PURCHASE VOUCHER" else Printer.docTitle(b, s)

    /** Shop name block + party / invoice details. Returns the y where the item table starts. */
    private fun header(c: Canvas, top: Float, b: Bill, s: ShopSettings, shop: String, gst: Boolean): Float {
        val mid = PW / 2f
        var y = top + 21f
        c.drawText(fit(shop.uppercase(Locale.US), pen(17f, true), R - L - 20f), mid, y, pen(17f, true, Paint.Align.CENTER))
        y += 14f
        if (s.tagline.isNotBlank()) {
            c.drawText(fit(s.tagline, pen(9f), R - L - 20f), mid, y, pen(9f, false, Paint.Align.CENTER))
            y += 12f
        }
        wrap(s.address, pen(9f), R - L - 60f).filter { it.isNotBlank() }.take(3).forEach { line ->
            c.drawText(line, mid, y, pen(9f, false, Paint.Align.CENTER))
            y += 11.5f
        }
        val contact = listOfNotNull(
            if (s.phone.isNotBlank()) "Phone : ${s.phone}" else null,
            if (gst && s.gstin.isNotBlank()) "GSTIN : ${s.gstin}" else null
        ).joinToString("        ")
        if (contact.isNotBlank()) {
            c.drawText(contact, mid, y, pen(9f, true, Paint.Align.CENTER))
            y += 11.5f
        }
        y += 2f
        hline(c, y)

        val boxTop = y
        val purchase = b.kind == 1
        var ly = boxTop + 13f
        c.drawText(if (purchase) "Supplier :" else "Party Details :", L + 6f, ly, pen(8.5f, true))
        ly += 13f
        c.drawText(fit(b.customer.ifBlank { "Cash" }, pen(10.5f, true), SPLIT - L - 12f), L + 6f, ly, pen(10.5f, true))
        ly += 12.5f
        val extra = mutableListOf<String>()
        if (b.phone.isNotBlank()) extra.add("Mobile : ${b.phone}")
        if (b.vehicleNo.isNotBlank()) extra.add("Vehicle No. : ${vehicleLabel(b.vehicleNo)}")
        val mk = listOfNotNull(
            if (b.model.isNotBlank()) "Model : ${b.model}" else null,
            if (b.km.isNotBlank()) "KM : ${b.km}" else null
        ).joinToString("     ")
        if (mk.isNotBlank()) extra.add(mk)
        extra.forEach { line ->
            c.drawText(fit(line, pen(9f), SPLIT - L - 12f), L + 6f, ly, pen(9f))
            ly += 11.5f
        }

        val df = SimpleDateFormat("dd-MM-yyyy", Locale.US)
        val tf = SimpleDateFormat("hh:mm a", Locale.US)
        val label = when {
            purchase -> "Purchase No."
            b.isEstimate -> "Estimate No."
            else -> "Invoice No."
        }
        val details = mutableListOf(label to billCode(b), "Dated" to df.format(Date(b.date)), "Time" to tf.format(Date(b.date)))
        if (purchase && b.ref.isNotBlank()) details.add("Supplier Bill No." to b.ref)
        if (!b.isEstimate) details.add("Payment Mode" to payLabel(b))
        var ry = boxTop + 13f
        details.forEachIndexed { i, (k, v) ->
            c.drawText(k, SPLIT + 8f, ry, pen(9f))
            c.drawText(": $v", SPLIT + 104f, ry, pen(9f, i == 0))
            ry += 12.5f
        }
        val end = max(ly, ry) + 1f
        c.drawLine(SPLIT, boxTop, SPLIT, end, stroke)
        hline(c, end)
        return end
    }

    /** A4 PDF invoice in BUSY style (black & white, boxed). */
    fun pdf(context: Context, b: Bill, items: List<BillItem>, s: ShopSettings, parts: List<Part>): File {
        val byId = parts.associateBy { it.id }
        val gst = s.showGst
        val shop = s.shopName.ifBlank { "Mauli Auto Parts" }
        val cols = if (gst) listOf(
            Col("S.N.", L, 54f, Paint.Align.CENTER),
            Col("Description of Goods", 54f, 318f, Paint.Align.LEFT),
            Col("HSN/SAC", 318f, 368f, Paint.Align.CENTER),
            Col("Qty.", 368f, 404f, Paint.Align.RIGHT),
            Col("Unit", 404f, 440f, Paint.Align.CENTER),
            Col("Price", 440f, 498f, Paint.Align.RIGHT),
            Col("Amount", 498f, R, Paint.Align.RIGHT)
        ) else listOf(
            Col("S.N.", L, 54f, Paint.Align.CENTER),
            Col("Description of Goods", 54f, 368f, Paint.Align.LEFT),
            Col("Qty.", 368f, 404f, Paint.Align.RIGHT),
            Col("Unit", 404f, 440f, Paint.Align.CENTER),
            Col("Price", 440f, 498f, Paint.Align.RIGHT),
            Col("Amount", 498f, R, Paint.Align.RIGHT)
        )
        val qtyCol = if (gst) 3 else 2
        val body = pen(9f)
        val bodyBold = pen(9f, true)
        val small = pen(7.5f)
        val descW = cols[1].r - cols[1].l - 8f
        val rows = items.mapIndexed { i, it ->
            val part = it.partId?.let { id -> byId[id] }
            val unit = (part?.unit ?: "nos").replaceFirstChar { c -> c.uppercase() }
            val desc = wrap(it.name, bodyBold, descW)
            val sub = if (it.partNo.isNotBlank()) "Part No: ${it.partNo}" else ""
            val cells = mutableListOf("${i + 1}", "")
            if (gst) cells.add(part?.hsn.orEmpty())
            cells.add("${it.qty}")
            cells.add(unit)
            cells.add(inr2(it.price))
            cells.add(inr2(it.qty * it.price))
            ItemLine(cells, desc, sub, 5f + desc.size * 11f + (if (sub.isNotEmpty()) 10f else 0f))
        }

        // Sizes of the bottom blocks (printed on the last page)
        val due = if (b.isEstimate) 0.0 else (b.total - b.paid).coerceAtLeast(0.0)
        val words = wrap(amountInWords(b.total), bodyBold, SPLIT - L - 12f)
        val noteLines = if (b.note.isNotBlank()) wrap("Note : ${b.note}", pen(8.5f), SPLIT - L - 12f) else emptyList()
        val sums = mutableListOf("Sub Total" to inr2(b.subTotal))
        if (b.discount > 0) sums.add("Less : Discount" to inr2(b.discount))
        val after = mutableListOf<Pair<String, String>>()
        if (!b.isEstimate) {
            after.add((if (b.payMode == "Udhaar") "Received" else "Received (${b.payMode})") to inr2(b.paid))
            if (due > 0.009) after.add("Balance Due" to inr2(due))
        }
        val sumH = max(16f + words.size * 12f + noteLines.size * 11f + 8f, 38f + sums.size * 13f + after.size * 13f)
        val taxes = if (gst) taxRows(items, b.discount, byId) else emptyList()
        val taxH = if (gst) 16f + taxes.size * 13f + 15f else 0f
        val terms = wrap(s.footer, pen(8f), 200f).filter { it.isNotBlank() }
        val footH = max(90f, 40f + terms.size * 10f + 20f)
        val totalH = 16f
        val bodyEnd = B - footH - taxH - sumH - totalH

        val doc = PdfDocument()
        var pageNo = 0
        var index = 0
        var done = false
        while (!done) {
            pageNo++
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, pageNo).create())
            val c = page.canvas
            var y = T
            c.drawText(title(b, s), PW / 2f, y + 13f, pen(11f, true, Paint.Align.CENTER))
            c.drawText(if (pageNo == 1) "Original Copy" else "Page $pageNo", R - 5f, y + 12f, pen(7.5f, false, Paint.Align.RIGHT))
            y += 18f
            hline(c, y)
            if (pageNo == 1) {
                y = header(c, y, b, s, shop, gst)
            } else {
                c.drawText("$shop  •  ${billCode(b)}  (continued)", L + 6f, y + 13f, pen(9f, true))
                y += 18f
                hline(c, y)
            }

            // Item table
            val headTop = y
            c.drawRect(L, headTop, R, headTop + 17f, shade)
            cols.forEach { col -> cell(c, col.title, col, headTop + 12f, pen(8.5f, true), Paint.Align.CENTER) }
            y = headTop + 17f
            hline(c, headTop)
            hline(c, y)
            var placed = 0
            while (index < rows.size && (y + rows[index].h <= bodyEnd || placed == 0)) {
                val r = rows[index]
                val base = y + 11f
                cols.forEachIndexed { ci, col ->
                    if (ci == 1) {
                        var ly = base
                        r.desc.forEach { line ->
                            c.drawText(line, col.l + 4f, ly, bodyBold)
                            ly += 11f
                        }
                        if (r.sub.isNotEmpty()) c.drawText(r.sub, col.l + 4f, ly - 1f, small)
                    } else {
                        cell(c, r.cells[ci], col, base, body)
                    }
                }
                y += r.h
                index++
                placed++
            }
            val last = index >= rows.size
            val lineEnd = if (last) bodyEnd + totalH else B
            for (ci in 1 until cols.size) c.drawLine(cols[ci].l, headTop, cols[ci].l, lineEnd, stroke)

            if (last) {
                // Total row
                hline(c, bodyEnd)
                val tb = bodyEnd + 11.5f
                c.drawText("Total", cols[1].r - 6f, tb, pen(9f, true, Paint.Align.RIGHT))
                cell(c, "${items.sumOf { it.qty }}", cols[qtyCol], tb, pen(9f, true))
                cell(c, inr2(b.subTotal), cols.last(), tb, pen(9f, true))
                var fy = bodyEnd + totalH
                hline(c, fy)

                // Amount in words + totals
                val sTop = fy
                var wy = sTop + 13f
                c.drawText("Amount in Words :", L + 6f, wy, pen(8.5f, true))
                wy += 13f
                words.forEach { line ->
                    c.drawText(line, L + 6f, wy, bodyBold)
                    wy += 12f
                }
                noteLines.forEach { line ->
                    c.drawText(line, L + 6f, wy, pen(8.5f))
                    wy += 11f
                }
                var ry = sTop + 13f
                sums.forEach { (k, v) ->
                    c.drawText(k, SPLIT + 8f, ry, body)
                    c.drawText(v, R - 6f, ry, pen(9f, false, Paint.Align.RIGHT))
                    ry += 13f
                }
                c.drawLine(SPLIT, ry - 8f, R, ry - 8f, stroke)
                ry += 8f
                c.drawText("Grand Total", SPLIT + 8f, ry, pen(11f, true))
                c.drawText("₹ " + inr2(b.total), R - 6f, ry, pen(11f, true, Paint.Align.RIGHT))
                c.drawLine(SPLIT, ry + 6f, R, ry + 6f, stroke)
                ry += 20f
                after.forEach { (k, v) ->
                    val strong = k == "Balance Due"
                    c.drawText(k, SPLIT + 8f, ry, pen(9f, strong))
                    c.drawText(v, R - 6f, ry, pen(9f, strong, Paint.Align.RIGHT))
                    ry += 13f
                }
                fy = sTop + sumH
                c.drawLine(SPLIT, sTop, SPLIT, fy, stroke)
                hline(c, fy)

                // GST summary
                if (gst) {
                    val tc = floatArrayOf(L, 118f, 208f, 262f, 340f, 394f, 472f, R)
                    val heads = listOf("HSN/SAC", "Taxable Amt.", "CGST %", "CGST Amt.", "SGST %", "SGST Amt.", "Total Tax")
                    val tTop = fy
                    c.drawRect(L, tTop, R, tTop + 16f, shade)
                    heads.forEachIndexed { i, h -> c.drawText(h, (tc[i] + tc[i + 1]) / 2f, tTop + 11f, pen(7.5f, true, Paint.Align.CENTER)) }
                    var gy = tTop + 16f
                    hline(c, gy)
                    var sTaxable = 0.0
                    var sTax = 0.0
                    taxes.forEach { tr ->
                        sTaxable += tr.taxable
                        sTax += tr.tax
                        val vals = listOf(
                            tr.hsn, inr2(tr.taxable), num(tr.rate / 2) + "%", inr2(tr.tax / 2),
                            num(tr.rate / 2) + "%", inr2(tr.tax / 2), inr2(tr.tax)
                        )
                        vals.forEachIndexed { i, v ->
                            if (i == 1 || i == 3 || i == 5 || i == 6) {
                                c.drawText(v, tc[i + 1] - 4f, gy + 10f, pen(8f, false, Paint.Align.RIGHT))
                            } else {
                                c.drawText(v, (tc[i] + tc[i + 1]) / 2f, gy + 10f, pen(8f, false, Paint.Align.CENTER))
                            }
                        }
                        gy += 13f
                    }
                    hline(c, gy)
                    val totals = listOf("Total", inr2(sTaxable), "", inr2(sTax / 2), "", inr2(sTax / 2), inr2(sTax))
                    totals.forEachIndexed { i, v ->
                        if (v.isEmpty()) return@forEachIndexed
                        if (i == 0) c.drawText(v, (tc[0] + tc[1]) / 2f, gy + 11f, pen(8f, true, Paint.Align.CENTER))
                        else c.drawText(v, tc[i + 1] - 4f, gy + 11f, pen(8f, true, Paint.Align.RIGHT))
                    }
                    gy += 15f
                    for (i in 1 until tc.size - 1) c.drawLine(tc[i], tTop, tc[i], gy, stroke)
                    fy = gy
                    hline(c, fy)
                }

                // Terms + signatures
                val fTop = fy
                var ky = fTop + 13f
                c.drawText("Terms & Conditions", L + 6f, ky, pen(8.5f, true))
                ky += 12f
                c.drawText("E.& O.E.", L + 6f, ky, pen(8f))
                ky += 10f
                terms.forEach { line ->
                    c.drawText(line, L + 6f, ky, pen(8f))
                    ky += 10f
                }
                c.drawText("Receiver's Signature", L + 6f, B - 7f, pen(8f))
                if (Printer.showUpiQr(b, s)) {
                    val amt = if (due > 0.009) due else b.total
                    val q = 60f
                    val qx = SPLIT - q - 10f
                    val qy = fTop + 6f
                    c.drawBitmap(qrBitmap(upiLink(s, amt, billCode(b)), 300), null, RectF(qx, qy, qx + q, qy + q), null)
                    c.drawText("Scan to pay ₹" + inr2(amt), qx + q / 2f, qy + q + 9f, pen(7f, false, Paint.Align.CENTER))
                }
                c.drawLine(SPLIT, fTop, SPLIT, B, stroke)
                c.drawText(
                    fit("for " + shop.uppercase(Locale.US), pen(9.5f, true), R - SPLIT - 12f),
                    R - 6f, fTop + 14f, pen(9.5f, true, Paint.Align.RIGHT)
                )
                c.drawText("Authorised Signatory", R - 6f, B - 7f, pen(8.5f, false, Paint.Align.RIGHT))
            } else {
                c.drawText("Continued on next page...", R - 6f, B - 6f, pen(8f, false, Paint.Align.RIGHT))
            }
            c.drawRect(L, T, R, B, stroke)
            c.drawText("This is a computer generated invoice", PW / 2f, B + 13f, pen(7f, false, Paint.Align.CENTER))
            doc.finishPage(page)
            done = last
        }

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "${billCode(b)}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** Loads a bill from the database and makes its PDF (runs in background). */
    suspend fun pdfFor(context: Context, dao: AppDao, billId: Long, s: ShopSettings): File? = withContext(Dispatchers.IO) {
        val b = dao.bill(billId) ?: return@withContext null
        pdf(context, b, dao.items(billId), s, dao.allParts())
    }

    /** Thermal (Bluetooth) print in BUSY POS style. */
    fun thermal(b: Bill, items: List<BillItem>, s: ShopSettings): ByteArray {
        val w = if (s.paper == 80) 48 else 32
        val e = PosBuilder(w)
        val shop = s.shopName.ifBlank { "Mauli Auto Parts" }.uppercase(Locale.US)
        val due = if (b.isEstimate) 0.0 else (b.total - b.paid).coerceAtLeast(0.0)
        val t = calcTotals(items, b.discount)

        e.cmd(0x1B, 0x40).center().bold(true)
        if (shop.length <= w / 2) e.size(0x11).line(shop) else e.size(0x01).wrap(shop)
        e.size(0x00).bold(false)
        if (s.tagline.isNotBlank()) e.wrap(s.tagline)
        if (s.address.isNotBlank()) e.wrap(s.address.replace("\n", " "))
        if (s.phone.isNotBlank()) e.line("Ph: ${s.phone}")
        if (s.showGst && s.gstin.isNotBlank()) e.line("GSTIN: ${s.gstin}")
        e.left().rule().center().bold(true).line(title(b, s)).bold(false).left().rule()

        val dt = SimpleDateFormat("dd-MM-yyyy  hh:mm a", Locale.US).format(Date(b.date))
        e.line("No.     : " + billCode(b))
        e.line("Date    : " + dt)
        e.wrap((if (b.kind == 1) "Supplier: " else "Party   : ") + b.customer.ifBlank { "Cash" })
        if (b.phone.isNotBlank()) e.line("Mobile  : " + b.phone)
        if (b.vehicleNo.isNotBlank()) {
            e.wrap("Vehicle : " + vehicleLabel(b.vehicleNo) + (if (b.model.isNotBlank()) " (${b.model})" else ""))
        }
        if (b.km.isNotBlank()) e.line("KM      : " + b.km)
        e.rule()

        if (w >= 48) {
            e.bold(true).line(row4("Item", "Qty", "Rate", "Amount", w)).bold(false).rule()
            val nameW = w - 27
            items.forEachIndexed { i, it ->
                val names = posWrap("${i + 1}. ${it.name}", nameW)
                e.line(row4(names[0], "${it.qty}", inr2(it.price), inr2(it.qty * it.price), w))
                names.drop(1).forEach { n -> e.line("   $n") }
            }
        } else {
            e.bold(true).lr("Item  (Qty x Rate)", "Amount").bold(false).rule()
            items.forEachIndexed { i, it ->
                e.wrap("${i + 1}. ${it.name}")
                e.lr("   ${it.qty} x ${inr2(it.price)}", inr2(it.qty * it.price))
            }
        }
        e.rule()
        e.lr("Total Qty", "${items.sumOf { it.qty }}")
        e.lr("Sub Total", inr2(b.subTotal))
        if (b.discount > 0) e.lr("Less: Discount", "-" + inr2(b.discount))
        e.rule('=')
        e.bold(true).size(0x01).lr("NET AMOUNT", "Rs." + inr2(b.total)).size(0x00).bold(false)
        e.rule('=')
        if (!b.isEstimate) {
            e.lr(if (b.payMode == "Udhaar") "Received" else "Received (${b.payMode})", inr2(b.paid))
            if (due > 0.009) e.bold(true).lr("Balance Due", inr2(due)).bold(false)
        }
        if (s.showGst) {
            e.rule()
            e.lr("Taxable Value", inr2(t.taxable))
            e.lr("CGST", inr2(t.tax / 2))
            e.lr("SGST", inr2(t.tax / 2))
        }
        e.rule()
        e.wrap(amountInWords(b.total))
        e.rule()
        e.line("E.& O.E.")
        if (s.footer.isNotBlank()) e.wrap(s.footer)
        if (Printer.showUpiQr(b, s)) {
            val amt = if (due > 0.009) due else b.total
            e.center().bold(true).line("Scan & Pay Rs.${inr2(amt)}").bold(false)
            e.raw(posQr(upiLink(s, amt, billCode(b)), if (w == 48) 300 else 240, if (w == 48) 576 else 384))
            e.line("UPI: ${s.upi}")
        }
        e.center()
        if (b.isEstimate) e.line("* Estimate only - not a bill *")
        e.cmd(0x1B, 0x64, 4)
        if (w == 48) e.cmd(0x1D, 0x56, 0x42, 0x00)
        return e.bytes()
    }
}
