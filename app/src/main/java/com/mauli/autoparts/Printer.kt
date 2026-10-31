package com.mauli.autoparts

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentAdapter.LayoutResultCallback
import android.print.PrintDocumentAdapter.WriteResultCallback
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

// ================= Formatting helpers (used by all screens) =================

/** Indian number style: 12,34,567.50 */
fun money(v: Double): String {
    val r = Math.round(abs(v) * 100)
    val whole = (r / 100).toString()
    val paise = r % 100
    val grouped = if (whole.length <= 3) whole
    else whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
    val sign = if (v < 0 && r != 0L) "-" else ""
    return sign + grouped + (if (paise != 0L) "." + paise.toString().padStart(2, '0') else "")
}

/** Rupee text: ₹12,34,567 */
fun rs(v: Double): String =
    if (v < 0 && Math.round(abs(v) * 100) != 0L) "-₹" + money(-v) else "₹" + money(v)

fun fmtDate(ms: Long): String = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(ms))
fun fmtDateTime(ms: Long): String = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(ms))
fun fmtTime(ms: Long): String = SimpleDateFormat("hh:mm a", Locale.US).format(Date(ms))

/** MH35AB1234 -> MH 35 AB 1234 */
fun vehicleLabel(v: String): String {
    val n = normVehicle(v)
    val m = Regex("^([A-Z]{2})(\\d{1,2})([A-Z]{0,3})(\\d{1,4})$").find(n) ?: return n
    return m.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString(" ")
}

fun upiLink(s: ShopSettings, amount: Double, note: String): String =
    "upi://pay?pa=${s.upi.trim()}&pn=${Uri.encode(s.shopName)}" +
        "&am=${String.format(Locale.US, "%.2f", amount)}&cu=INR&tn=${Uri.encode(note)}"

fun qrBitmap(text: String, size: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(m.width * m.height)
    for (y in 0 until m.height) {
        for (x in 0 until m.width) px[y * m.width + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}

private val ONES = arrayOf(
    "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve",
    "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"
)
private val TENS = arrayOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")

private fun words(n: Long): String = when {
    n < 20 -> ONES[n.toInt()]
    n < 100 -> TENS[(n / 10).toInt()] + (if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else "")
    n < 1000 -> ONES[(n / 100).toInt()] + " Hundred" + (if (n % 100 != 0L) " " + words(n % 100) else "")
    n < 100000 -> words(n / 1000) + " Thousand" + (if (n % 1000 != 0L) " " + words(n % 1000) else "")
    n < 10000000 -> words(n / 100000) + " Lakh" + (if (n % 100000 != 0L) " " + words(n % 100000) else "")
    else -> words(n / 10000000) + " Crore" + (if (n % 10000000 != 0L) " " + words(n % 10000000) else "")
}

/** 1250.0 -> "Rupees One Thousand Two Hundred Fifty Only" */
fun amountInWords(v: Double): String {
    val total = Math.round(abs(v) * 100)
    val rupees = total / 100
    val paise = total % 100
    val sb = StringBuilder("Rupees ").append(if (rupees == 0L) "Zero" else words(rupees))
    if (paise != 0L) sb.append(" and ").append(words(paise)).append(" Paise")
    return sb.append(" Only").toString()
}

// ================= Private helpers =================

private fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)

/** Thermal printers understand only plain English letters. */
private fun clean(s: String): String =
    s.replace("₹", "Rs.").map { c -> if (c == '\n' || c.code in 32..126) c else '?' }.joinToString("")

private fun wrapLine(text: String, width: Int): List<String> {
    val lines = mutableListOf<String>()
    var cur = ""
    for (w0 in text.trim().split(Regex("\\s+"))) {
        var w = w0
        while (w.length > width) {
            if (cur.isNotEmpty()) { lines.add(cur); cur = "" }
            lines.add(w.take(width))
            w = w.drop(width)
        }
        if (w.isEmpty()) continue
        cur = when {
            cur.isEmpty() -> w
            cur.length + 1 + w.length <= width -> "$cur $w"
            else -> { lines.add(cur); w }
        }
    }
    if (cur.isNotEmpty()) lines.add(cur)
    return lines.ifEmpty { listOf("") }
}

private fun wrapText(text: String, p: Paint, maxW: Float): List<String> {
    val out = mutableListOf<String>()
    var rest = text.trim()
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
    return out.ifEmpty { listOf("") }
}

private fun fit(text: String, p: Paint, maxW: Float): String {
    if (p.measureText(text) <= maxW) return text
    val n = p.breakText(text, true, maxW - p.measureText("..."), null).coerceAtLeast(0)
    return text.take(n) + "..."
}

/** Builds ESC/POS commands for thermal printers. */
private class Esc(val width: Int) {
    private val out = ByteArrayOutputStream()
    fun cmd(vararg b: Int): Esc { b.forEach { out.write(it) }; return this }
    fun raw(bytes: ByteArray): Esc { out.write(bytes, 0, bytes.size); return this }
    fun text(s: String): Esc = raw(clean(s).toByteArray(Charsets.US_ASCII))
    fun line(s: String = ""): Esc = text(s + "\n")
    fun center(): Esc = cmd(0x1B, 0x61, 1)
    fun left(): Esc = cmd(0x1B, 0x61, 0)
    fun bold(on: Boolean): Esc = cmd(0x1B, 0x45, if (on) 1 else 0)
    fun big(n: Int): Esc = cmd(0x1D, 0x21, n)
    fun feed(n: Int): Esc = cmd(0x1B, 0x64, n)
    fun dash(): Esc = line("-".repeat(width))
    fun wrap(s: String): Esc { wrapLine(s, width).forEach { line(it) }; return this }
    fun lr(l: String, r: String): Esc {
        val right = r.take(width)
        val room = (width - right.length - 1).coerceAtLeast(0)
        val left = l.take(room)
        return line(left + " ".repeat((width - left.length - right.length).coerceAtLeast(0)) + right)
    }
    fun bytes(): ByteArray = out.toByteArray()
}

/** QR code as a printer image, centred on the paper. */
private fun qrRaster(text: String, size: Int, paperDots: Int): ByteArray {
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
            if (px < paperDots && m.get(x, y)) {
                row[px / 8] = (row[px / 8].toInt() or (0x80 shr (px % 8))).toByte()
            }
        }
        out.write(row, 0, row.size)
    }
    return out.toByteArray()
}

// ================= Printer =================

object Printer {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    data class Device(val name: String, val address: String, val isPrinter: Boolean)

    fun docTitle(b: Bill, s: ShopSettings): String = when {
        b.isEstimate -> "ESTIMATE"
        s.showGst && s.gstin.isNotBlank() -> "TAX INVOICE"
        else -> "INVOICE"
    }

        /** The QR goes on every bill, unless the shop switched it off for this one. */
    fun showUpiQr(b: Bill, s: ShopSettings): Boolean =
        s.upi.isNotBlank() && !b.noQr && b.total > 0.009

    // ---------- Bluetooth ----------

    fun hasBtPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun bluetoothOn(context: Context): Boolean = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (e: Exception) {
        false
    }

    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<Device> {
        if (!hasBtPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map { d ->
                Device(
                    d.name ?: "Unknown device", d.address,
                    d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING
                )
            }.sortedByDescending { it.isPrinter }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice): BluetoothSocket {
        val secure = device.createRfcommSocketToServiceRecord(SPP)
        try {
            secure.connect()
            return secure
        } catch (e: Exception) {
            try { secure.close() } catch (x: Exception) { }
        }
        val insecure = device.createInsecureRfcommSocketToServiceRecord(SPP)
        insecure.connect()
        return insecure
    }

    /** Sends data to the printer. Returns null when OK, otherwise an error message. */
    @SuppressLint("MissingPermission")
    suspend fun printBluetooth(context: Context, address: String, data: ByteArray): String? = withContext(Dispatchers.IO) {
        if (!hasBtPermission(context)) return@withContext "Allow 'Nearby devices' permission to print"
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return@withContext "Bluetooth not available on this phone"
        if (!adapter.isEnabled) return@withContext "Please turn ON Bluetooth"
        var socket: BluetoothSocket? = null
        try {
            val sock = connect(adapter.getRemoteDevice(address))
            socket = sock
            val out = sock.outputStream
            var i = 0
            while (i < data.size) {
                val n = minOf(512, data.size - i)
                out.write(data, i, n)
                out.flush()
                i += n
                delay(15)
            }
            delay(700L + data.size / 6)
            null
        } catch (e: SecurityException) {
            "Bluetooth permission denied"
        } catch (e: Exception) {
            "Printer not connected. Switch ON the printer and try again"
        } finally {
            try { socket?.close() } catch (e: Exception) { }
        }
    }

    // ---------- Thermal bill (ESC/POS) ----------

    fun escPosBill(b: Bill, items: List<BillItem>, s: ShopSettings): ByteArray {
        val w = if (s.paper == 80) 48 else 32
        val e = Esc(w)
        val t = calcTotals(items, b.discount)
        val due = (b.total - b.paid).coerceAtLeast(0.0)
        val shop = s.shopName.ifBlank { "Mauli Auto Parts" }

        e.cmd(0x1B, 0x40).center().bold(true)
        if (shop.length <= w / 2) e.big(0x11).line(shop) else e.big(0x01).wrap(shop)
        e.big(0x00).bold(false)
        if (s.tagline.isNotBlank()) e.wrap(s.tagline)
        if (s.address.isNotBlank()) e.wrap(s.address)
        if (s.phone.isNotBlank()) e.line("Ph: ${s.phone}")
        if (s.showGst && s.gstin.isNotBlank()) e.line("GSTIN: ${s.gstin}")
        e.line().bold(true).line(docTitle(b, s)).bold(false)

        e.left().dash()
        e.lr("No: ${billCode(b)}", SimpleDateFormat("dd/MM/yy hh:mm a", Locale.US).format(Date(b.date)))
        if (b.customer.isNotBlank()) e.wrap("Name: ${b.customer}")
        if (b.phone.isNotBlank()) e.line("Mob : ${b.phone}")
        if (b.vehicleNo.isNotBlank()) {
            e.wrap("Veh : ${vehicleLabel(b.vehicleNo)}" + (if (b.model.isNotBlank()) " ${b.model}" else ""))
        }
        if (b.km.isNotBlank()) e.line("KM  : ${b.km}")

        e.dash().bold(true).lr("Item", "Amount").bold(false).dash()
        items.forEachIndexed { i, item ->
            e.wrap("${i + 1}. ${item.name}")
            e.lr("   ${item.qty} x ${f2(item.price)}", f2(item.qty * item.price))
        }
        e.dash()
        e.lr("Sub Total", f2(b.subTotal))
        if (b.discount > 0) e.lr("Discount", "-" + f2(b.discount))
        e.bold(true).big(0x01).lr("TOTAL", "Rs." + f2(b.total)).big(0x00).bold(false)
        if (!b.isEstimate) {
            e.lr(if (b.payMode == "Udhaar") "Paid" else "Paid (${b.payMode})", f2(b.paid))
            if (due > 0.009) e.bold(true).lr("Balance Due", f2(due)).bold(false)
        }
        if (s.showGst) {
            e.dash()
            e.lr("Taxable Value", f2(t.taxable))
            e.lr("CGST", f2(t.tax / 2))
            e.lr("SGST", f2(t.tax / 2))
        }
        e.dash().center()
        if (showUpiQr(b, s)) {
            val amt = if (due > 0.009) due else b.total
            e.bold(true).line("Scan & Pay Rs.${f2(amt)}").bold(false)
            e.raw(qrRaster(upiLink(s, amt, billCode(b)), if (w == 48) 300 else 240, if (w == 48) 576 else 384))
            e.line("UPI: ${s.upi}")
        }
        if (b.isEstimate) e.line("* Estimate only - not a bill *")
        if (s.footer.isNotBlank()) e.wrap(s.footer)
        e.feed(4)
        if (w == 48) e.cmd(0x1D, 0x56, 0x42, 0x00)
        return e.bytes()
    }

    fun testPage(s: ShopSettings): ByteArray {
        val w = if (s.paper == 80) 48 else 32
        val e = Esc(w)
        e.cmd(0x1B, 0x40).center().bold(true).big(0x11).line("TEST PRINT").big(0x00).bold(false)
        e.line(s.shopName).line("$w letters per line").left().dash()
        e.line("1234567890".repeat(5).take(w))
        e.lr("Left side", "Right side")
        e.center().line("Printer is working!")
        e.feed(4)
        if (w == 48) e.cmd(0x1D, 0x56, 0x42, 0x00)
        return e.bytes()
    }

    // ---------- WhatsApp text bill ----------

    fun billText(b: Bill, items: List<BillItem>, s: ShopSettings): String {
        val due = (b.total - b.paid).coerceAtLeast(0.0)
        val sb = StringBuilder()
        sb.append("*${s.shopName}*\n")
        if (s.phone.isNotBlank()) sb.append("Ph: ${s.phone}\n")
        sb.append(if (b.isEstimate) "Estimate" else "Bill").append(": ${billCode(b)}  |  ${fmtDate(b.date)}\n")
        if (b.vehicleNo.isNotBlank()) {
            sb.append("Vehicle: ${vehicleLabel(b.vehicleNo)}")
            if (b.model.isNotBlank()) sb.append(" (${b.model})")
            sb.append("\n")
        }
        sb.append("\n")
        items.forEachIndexed { i, it ->
            sb.append("${i + 1}. ${it.name}\n     ${it.qty} x ${rs(it.price)} = ${rs(it.qty * it.price)}\n")
        }
        sb.append("\n")
        if (b.discount > 0) sb.append("Sub Total: ${rs(b.subTotal)}\nDiscount: -${rs(b.discount)}\n")
        sb.append("*Total: ${rs(b.total)}*\n")
        if (!b.isEstimate) {
            sb.append("Paid: ${rs(b.paid)}\n")
            if (due > 0.009) sb.append("*Balance Due: ${rs(due)}*\n")
            if (due > 0.009 && s.upi.isNotBlank()) sb.append("\nPay by UPI: ${s.upi}\n")
        }
        if (s.footer.isNotBlank()) sb.append("\n${s.footer}")
        return sb.toString()
    }

    // ---------- A4 PDF invoice ----------

    fun billPdf(context: Context, b: Bill, items: List<BillItem>, s: ShopSettings, parts: List<Part> = emptyList()): File {
        val navy = Color.parseColor("#1E3A8A")
        val orange = Color.parseColor("#F97316")
        val ink = Color.parseColor("#0F172A")
        val grey = Color.parseColor("#64748B")
        val light = Color.parseColor("#C7D2FE")
        val soft = Color.parseColor("#F1F5F9")
        val navySoft = Color.parseColor("#E8EDFB")
        val zebra = Color.parseColor("#FAFBFD")
        val green = Color.parseColor("#16A34A")

        val pw = 595
        val ph = 842
        val m = 36f
        val r = pw - m
        val gst = s.showGst
        val hsnOf = parts.associate { it.id to it.hsn }
        val t = calcTotals(items, b.discount)
        val due = (b.total - b.paid).coerceAtLeast(0.0)
        val title = docTitle(b, s)

        fun tp(size: Float, color: Int = ink, bold: Boolean = false, right: Boolean = false) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
                typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }

        fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

        val linePaint = Paint().apply { color = Color.parseColor("#E2E8F0"); strokeWidth = 0.7f }

        val doc = PdfDocument()
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var c: Canvas
        var y = 0f

        fun startPage() {
            if (pageNo > 0) doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
            c = page.canvas
            val first = pageNo == 1
            val band = if (first) 106f else 58f
            c.drawRect(0f, 0f, pw.toFloat(), band, fill(navy))
            c.drawRect(0f, band, pw.toFloat(), band + 4f, fill(orange))
            if (first) {
                c.drawText(fit(s.shopName, tp(22f, bold = true), 330f), m, 40f, tp(22f, Color.WHITE, true))
                var ty = 57f
                if (s.tagline.isNotBlank()) {
                    c.drawText(fit(s.tagline, tp(10.5f), 330f), m, ty, tp(10.5f, light))
                    ty += 14f
                }
                wrapText(s.address, tp(9.5f), 330f).take(2).filter { it.isNotBlank() }.forEach {
                    c.drawText(it, m, ty, tp(9.5f, light))
                    ty += 12f
                }
                val contact = listOfNotNull(
                    if (s.phone.isNotBlank()) "Ph: ${s.phone}" else null,
                    if (gst && s.gstin.isNotBlank()) "GSTIN: ${s.gstin}" else null
                ).joinToString("    ")
                if (contact.isNotBlank()) c.drawText(contact, m, ty, tp(9.5f, Color.WHITE, true))
                c.drawText(title, r, 40f, tp(18f, Color.WHITE, true, true))
                c.drawText("No: ${billCode(b)}", r, 60f, tp(11f, Color.WHITE, false, true))
                c.drawText(fmtDateTime(b.date), r, 76f, tp(9.5f, light, false, true))
            } else {
                c.drawText(fit(s.shopName, tp(15f, bold = true), 300f), m, 36f, tp(15f, Color.WHITE, true))
                c.drawText("$title ${billCode(b)} (continued)", r, 36f, tp(10f, light, false, true))
            }
            y = band + 22f
        }

        startPage()

        // Customer & vehicle box
        val boxH = 62f
        c.drawRoundRect(RectF(m, y, r, y + boxH), 10f, 10f, fill(soft))
        c.drawText("BILL TO", m + 14f, y + 18f, tp(8.5f, grey, true))
        c.drawText(fit(b.customer.ifBlank { "Walk-in Customer" }, tp(13f, bold = true), 230f), m + 14f, y + 37f, tp(13f, ink, true))
        if (b.phone.isNotBlank()) c.drawText("Mobile: ${b.phone}", m + 14f, y + 52f, tp(9.5f, grey))
        val vx = pw / 2f + 30f
        c.drawText("VEHICLE", vx, y + 18f, tp(8.5f, grey, true))
        c.drawText(if (b.vehicleNo.isBlank()) "-" else vehicleLabel(b.vehicleNo), vx, y + 37f, tp(13f, ink, true))
        val vSub = listOf(b.model, if (b.km.isNotBlank()) "${b.km} km" else "").filter { it.isNotBlank() }.joinToString("   |   ")
        if (vSub.isNotBlank()) c.drawText(fit(vSub, tp(9.5f), r - vx - 10f), vx, y + 52f, tp(9.5f, grey))
        y += boxH + 16f

        // Items table
        val xNo = m + 8f
        val xItem = m + 30f
        val itemW = if (gst) 205f else 280f
        val xHsn = 282f
        val xGst = 362f
        val xQty = if (gst) 405f else 395f
        val xRate = 475f
        val xAmt = r - 8f

        fun tableHead() {
            c.drawRoundRect(RectF(m, y, r, y + 24f), 6f, 6f, fill(navySoft))
            val ty = y + 15.5f
            c.drawText("#", xNo, ty, tp(8.5f, navy, true))
            c.drawText("ITEM / PART", xItem, ty, tp(8.5f, navy, true))
            if (gst) {
                c.drawText("HSN", xHsn, ty, tp(8.5f, navy, true))
                c.drawText("GST", xGst, ty, tp(8.5f, navy, true, true))
            }
            c.drawText("QTY", xQty, ty, tp(8.5f, navy, true, true))
            c.drawText("RATE", xRate, ty, tp(8.5f, navy, true, true))
            c.drawText("AMOUNT", xAmt, ty, tp(8.5f, navy, true, true))
            y += 30f
        }
        tableHead()

        val namePaint = tp(10.5f, ink, true)
        items.forEachIndexed { i, item ->
            val lines = wrapText(item.name, namePaint, itemW)
            val hasNo = item.partNo.isNotBlank()
            val rowH = 6f + lines.size * 14f + (if (hasNo) 12f else 0f) + 8f
            if (y + rowH > ph - 50f) { startPage(); tableHead() }
            if (i % 2 == 1) c.drawRect(m, y - 4f, r, y + rowH - 4f, fill(zebra))
            val base = y + 10f
            c.drawText("${i + 1}", xNo, base, tp(10f, grey))
            var ly = base
            lines.forEach { l -> c.drawText(l, xItem, ly, namePaint); ly += 14f }
            if (hasNo) c.drawText("Part No: ${item.partNo}", xItem, ly - 2f, tp(8.5f, grey))
            if (gst) {
                val hsn = item.partId?.let { hsnOf[it] }.orEmpty()
                if (hsn.isNotBlank()) c.drawText(hsn, xHsn, base, tp(9.5f, ink))
                c.drawText("${num(item.gst)}%", xGst, base, tp(10f, ink, false, true))
            }
            c.drawText("${item.qty}", xQty, base, tp(10f, ink, false, true))
            c.drawText(rs(item.price), xRate, base, tp(10f, ink, false, true))
            c.drawText(rs(item.qty * item.price), xAmt, base, tp(10f, ink, true, true))
            y += rowH
            c.drawLine(m, y - 4f, r, y - 4f, linePaint)
        }

        // Totals (right) + amount in words & UPI QR (left)
        y += 8f
        val showQr = showUpiQr(b, s)
        val rows = mutableListOf("Sub Total" to rs(b.subTotal))
        if (b.discount > 0) rows.add("Discount" to "- " + rs(b.discount))
        if (gst) {
            rows.add("Taxable Value" to rs(t.taxable))
            rows.add("CGST" to rs(t.tax / 2))
            rows.add("SGST" to rs(t.tax / 2))
        }
        val rightH = rows.size * 18f + 48f + (if (b.isEstimate) 0f else 40f)
        val leftH = 56f + (if (showQr) 130f else 0f)
        if (y + maxOf(rightH, leftH) + 110f > ph) startPage()
        val top = y
        val lx = r - 210f
        var ry = top + 12f
        rows.forEach { (k, v) ->
            c.drawText(k, lx, ry, tp(10f, grey))
            c.drawText(v, xAmt, ry, tp(10f, ink, false, true))
            ry += 18f
        }
        c.drawRoundRect(RectF(lx - 10f, ry - 6f, r, ry + 26f), 8f, 8f, fill(navy))
        c.drawText("TOTAL", lx, ry + 15f, tp(11f, Color.WHITE, true))
        c.drawText(rs(b.total), xAmt, ry + 16f, tp(14f, Color.WHITE, true, true))
        ry += 46f
        if (!b.isEstimate) {
            c.drawText(if (b.payMode == "Udhaar") "Paid" else "Paid (${b.payMode})", lx, ry, tp(10f, grey))
            c.drawText(rs(b.paid), xAmt, ry, tp(10f, ink, true, true))
            ry += 18f
            val dueColor = if (due > 0.009) orange else green
            c.drawText(if (due > 0.009) "Balance Due" else "Fully Paid", lx, ry, tp(10.5f, dueColor, true))
            c.drawText(rs(due), xAmt, ry, tp(10.5f, dueColor, true, true))
        }

        var wy = top + 12f
        c.drawText("AMOUNT IN WORDS", m, wy, tp(8.5f, grey, true))
        wy += 15f
        wrapText(amountInWords(b.total), tp(10f, ink, true), 270f).forEach {
            c.drawText(it, m, wy, tp(10f, ink, true))
            wy += 13f
        }
        if (showQr) {
            val amt = if (due > 0.009) due else b.total
            wy += 10f
            c.drawBitmap(qrBitmap(upiLink(s, amt, billCode(b)), 360), null, RectF(m, wy, m + 110f, wy + 110f), null)
            c.drawText("Scan & Pay ${rs(amt)}", m + 122f, wy + 44f, tp(11.5f, navy, true))
            c.drawText("UPI: ${s.upi}", m + 122f, wy + 61f, tp(9.5f, grey))
            c.drawText("GPay  |  PhonePe  |  Paytm  |  BHIM", m + 122f, wy + 76f, tp(8.5f, grey))
        }

        // Footer
        val fy = ph - 52f
        c.drawLine(m, fy - 30f, r, fy - 30f, linePaint)
        if (s.footer.isNotBlank()) c.drawText(fit(s.footer, tp(10f, bold = true), 300f), m, fy - 8f, tp(10f, navy, true))
        val note = when {
            b.isEstimate -> "This is an estimate only. Prices may change."
            gst -> "All prices are inclusive of GST."
            else -> ""
        }
        if (note.isNotEmpty()) c.drawText(note, m, fy + 8f, tp(8.5f, grey))
        c.drawText(fit("For ${s.shopName}", tp(10f, bold = true), 220f), r, fy - 8f, tp(10f, ink, true, true))
        c.drawText("Authorised Signatory", r, fy + 22f, tp(8.5f, grey, false, true))
        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "${billCode(b)}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** Loads a bill from the database and makes its PDF (runs in background). */
    suspend fun pdfFor(context: Context, dao: AppDao, billId: Long, s: ShopSettings): File? = withContext(Dispatchers.IO) {
        val b = dao.bill(billId) ?: return@withContext null
        billPdf(context, b, dao.items(billId), s, dao.allParts())
    }

    /** Prints the PDF on any WiFi / USB printer, or saves it, using Android's print screen. */
    fun printPdf(context: Context, file: File, jobName: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback?.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                try {
                    val fd = destination?.fileDescriptor ?: throw IllegalStateException("No printer output")
                    FileInputStream(file).use { input -> FileOutputStream(fd).use { output -> input.copyTo(output) } }
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                }
            }
        }
        manager.print(jobName, adapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }

    // ---------- Sharing ----------

    private fun start(context: Context, intent: Intent) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun shareFile(context: Context, file: File, mime: String = "application/pdf", title: String = "Share") {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        start(context, Intent.createChooser(send, title))
    }

    /** Opens WhatsApp (Business first, then normal) with the PDF ready to send. */
    fun whatsappFile(context: Context, file: File, phone: String = "") {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val digits = phone.filter { it.isDigit() }.takeLast(10)
        for (pkg in listOf("com.whatsapp.w4b", "com.whatsapp")) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                setPackage(pkg)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (digits.length == 10) putExtra("jid", "91$digits@s.whatsapp.net")
            }
            try {
                start(context, send)
                return
            } catch (e: ActivityNotFoundException) {
            }
        }
        shareFile(context, file)
    }

    /** Opens the customer's WhatsApp chat directly with the bill text typed in. */
    fun whatsappText(context: Context, phone: String, text: String) {
        val digits = phone.filter { it.isDigit() }.takeLast(10)
        val url = (if (digits.length == 10) "https://wa.me/91$digits" else "https://wa.me/") + "?text=" + Uri.encode(text)
        try {
            start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            shareText(context, text)
        }
    }

    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        start(context, Intent.createChooser(send, "Share"))
    }

    fun dial(context: Context, phone: String) {
        try {
            start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (e: ActivityNotFoundException) {
        }
    }
}
