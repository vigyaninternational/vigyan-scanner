package com.vigyan.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The college's own marks for documents: a letterhead band, an "ATTESTED / TRUE COPY" stamp, the
 * college seal and the principal's signature. The seal and signature are transparent PNGs made
 * with the signature cut-out tool; the text is set in College stamp & signature.
 */
class Branding(private val context: Context) {

    private val prefs = context.getSharedPreferences("branding", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "branding").apply { mkdirs() }

    val signatureFile get() = File(dir, "signature.png")
    val sealFile get() = File(dir, "seal.png")

    var collegeName: String
        get() = prefs.getString("college", null) ?: "VIGYAN INTERNATIONAL JUNIOR COLLEGE"
        set(v) = prefs.edit().putString("college", v.trim()).apply()

    var address: String
        get() = prefs.getString("address", null) ?: "Koraput, Odisha"
        set(v) = prefs.edit().putString("address", v.trim()).apply()

    var signatory: String
        get() = prefs.getString("signatory", null) ?: "Principal"
        set(v) = prefs.edit().putString("signatory", v.trim()).apply()

    fun hasSignature() = signatureFile.exists()
    fun hasSeal() = sealFile.exists()

    fun saveImage(file: File, bitmap: Bitmap) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Draws the chosen stamps at the bottom-right of a page (on a copy). The page keeps its size,
     * so the searchable text still lines up.
     */
    fun stamp(page: Bitmap, attested: Boolean, seal: Boolean, signature: Boolean): Bitmap {
        if (!attested && !(seal && hasSeal()) && !(signature && hasSignature())) return page
        val out = page.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val blockW = w * 0.36f
        val right = w * 0.96f
        val left = right - blockW
        var y = out.height * 0.97f // work upwards from the bottom

        val ink = Color.rgb(20, 40, 140)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textAlign = Paint.Align.CENTER }
        val cx = left + blockW / 2

        text.textSize = w * 0.018f
        text.typeface = Typeface.DEFAULT
        c.drawText(collegeName, cx, y, text)
        y -= w * 0.024f
        text.textSize = w * 0.022f
        text.typeface = Typeface.DEFAULT_BOLD
        c.drawText(signatory, cx, y, text)
        y -= w * 0.01f

        if (signature && hasSignature()) {
            BitmapFactory.decodeFile(signatureFile.path)?.let { sig ->
                val maxW = blockW * 0.8f
                val maxH = w * 0.08f
                val s = minOf(maxW / sig.width, maxH / sig.height)
                val sw = sig.width * s
                val sh = sig.height * s
                c.drawBitmap(sig, null, RectF(cx - sw / 2, y - sh, cx + sw / 2, y), Paint(Paint.FILTER_BITMAP_FLAG))
                y -= sh + w * 0.008f
                sig.recycle()
            }
        }

        if (attested) {
            val date = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())
            val boxH = w * 0.075f
            val box = RectF(left + blockW * 0.08f, y - boxH, right - blockW * 0.08f, y)
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(180, 20, 30); style = Paint.Style.STROKE; strokeWidth = w * 0.004f
            }
            c.drawRoundRect(box, w * 0.008f, w * 0.008f, border)
            val red = Paint(text).apply { color = Color.rgb(180, 20, 30); typeface = Typeface.DEFAULT_BOLD; textSize = w * 0.026f }
            c.drawText("ATTESTED - TRUE COPY", box.centerX(), box.top + boxH * 0.45f, red)
            red.textSize = w * 0.018f
            red.typeface = Typeface.DEFAULT
            c.drawText("Date: $date", box.centerX(), box.top + boxH * 0.82f, red)
            y = box.top - w * 0.008f
        }

        if (seal && hasSeal()) {
            BitmapFactory.decodeFile(sealFile.path)?.let { s ->
                val size = w * 0.16f
                val k = minOf(size / s.width, size / s.height)
                val sw = s.width * k
                val sh = s.height * k
                val sx = left - sw * 0.75f
                val sy = out.height * 0.97f - sh
                c.drawBitmap(s, null, RectF(sx, sy, sx + sw, sy + sh), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 225 })
                s.recycle()
            }
        }
        return out
    }

    /** Letterhead band [width] pixels wide: logo, college name and address, and a rule below. */
    fun header(width: Int): Bitmap {
        val h = (width * 0.15f).toInt()
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val pad = width * 0.04f
        val logoSize = h * 0.8f
        BitmapFactory.decodeResource(context.resources, R.drawable.logo_vigyan)?.let { logo ->
            val k = logoSize / maxOf(logo.width, logo.height)
            val lw = logo.width * k
            val lh = logo.height * k
            c.drawBitmap(logo, null, RectF(pad, (h - lh) / 2, pad + lw, (h + lh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            logo.recycle()
        }
        val textLeft = pad + logoSize + width * 0.025f
        val available = width - textLeft - pad
        val cx = textLeft + available / 2
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 60, 30); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
            textSize = width * 0.042f
            while (measureText(collegeName) > available && textSize > 10f) textSize *= 0.95f
        }
        c.drawText(collegeName, cx, h * 0.48f, name)
        val addr = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(60, 60, 60); textAlign = Paint.Align.CENTER; textSize = width * 0.022f
            while (measureText(address) > available && textSize > 8f) textSize *= 0.95f
        }
        c.drawText(address, cx, h * 0.72f, addr)
        val rule = Paint().apply { color = Color.rgb(200, 30, 120); strokeWidth = h * 0.03f }
        c.drawLine(pad, h * 0.93f, width - pad, h * 0.93f, rule)
        return bmp
    }
}
