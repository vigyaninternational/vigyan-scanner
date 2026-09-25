package com.vigyan.scanner

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.util.zip.GZIPInputStream

/**
 * Reads the QR code printed on Aadhaar cards and e-Aadhaar letters. The data there is exactly
 * what UIDAI holds, so it beats reading the printed text.
 *
 * - Old cards: an XML QR (`<PrintLetterBarcodeData uid=".." name=".." …/>`), which includes the
 *   Aadhaar number.
 * - Secure QR (2019 onwards): a long number. Turned into bytes and un-gzipped, it gives fields
 *   separated by byte 255. It has no full Aadhaar number (only the last 4 digits).
 *
 * Returns FormExtractor field keys, or null when [raw] is not an Aadhaar QR.
 */
object AadhaarQr {

    fun parse(raw: String): Map<String, String>? {
        val s = raw.trim()
        return when {
            s.contains("PrintLetterBarcodeData") -> parseXml(s)
            s.length > 100 && s.all(Char::isDigit) -> runCatching { parseSecure(s) }.getOrNull()
            else -> null
        }?.filterValues { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
    }

    private fun parseXml(s: String): Map<String, String> {
        val a = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)')""").findAll(s)
            .associate { it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] }).trim() }
        val out = mutableMapOf<String, String>()
        a["name"]?.let { out["name"] = it }
        a["uid"]?.filter(Char::isDigit)?.takeIf { it.length == 12 }?.let { out["aadhaar"] = it.chunked(4).joinToString(" ") }
        gender(a["gender"])?.let { out["gender"] = it }
        (a["dob"]?.let { FormExtractor.normalizeDate(it) } ?: a["yob"])?.let { out["dob"] = it }
        (a["gname"]?.takeIf { it.isNotBlank() } ?: careOf(a["co"]))?.let { out["father"] = it }
        address(listOf("house", "street", "lm", "loc", "vtc", "po", "subdist", "dist", "state", "pc").map { a[it] })
            ?.let { out["address"] = it }
        a["pc"]?.takeIf { it.length == 6 }?.let { out["pin"] = it }
        return out
    }

    private fun parseSecure(s: String): Map<String, String> {
        val bytes = BigInteger(s).toByteArray()
        val data = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        val parts = mutableListOf<String>()
        var start = 0
        for (i in data.indices) {
            if (data[i] == 255.toByte()) {
                parts += String(data, start, i - start, Charsets.ISO_8859_1)
                start = i + 1
                if (parts.size > 20) break // the rest is the photo and signature
            }
        }
        // Version 2+ QRs start with "V2", "V3"…; older ones start straight with the indicator.
        val f = if (parts.firstOrNull()?.matches(Regex("""V\d+""")) == true) parts.drop(1) else parts
        fun field(i: Int) = f.getOrNull(i)?.trim().orEmpty()
        val out = mutableMapOf<String, String>()
        out["name"] = field(2)
        out["dob"] = FormExtractor.normalizeDate(field(3)) ?: field(3)
        gender(field(4))?.let { out["gender"] = it }
        careOf(field(5))?.let { out["father"] = it }
        // district, landmark, house, location, pincode, post office, state, street, sub-district, vtc
        address(listOf(field(8), field(13), field(7), field(9), field(15), field(11), field(14), field(6), field(12), field(10)))
            ?.let { out["address"] = it }
        field(10).takeIf { it.length == 6 && it.all(Char::isDigit) }?.let { out["pin"] = it }
        return out
    }

    private fun gender(g: String?) = when (g?.trim()?.uppercase()?.firstOrNull()) {
        'M' -> "MALE"
        'F' -> "FEMALE"
        'T', 'O' -> "OTHER"
        else -> null
    }

    /** "S/O: Suresh Kumar" → "Suresh Kumar". W/O (husband) is left out. */
    private fun careOf(co: String?): String? {
        val m = Regex("""^\s*([SDC])\s*/\s*O\s*[:.\-]?\s*(.+)$""", RegexOption.IGNORE_CASE).find(co ?: return null) ?: return null
        return m.groupValues[2].trim().trimEnd(',').takeIf { it.isNotBlank() }
    }

    private fun address(parts: List<String?>): String? =
        parts.mapNotNull { it?.trim()?.trim(',')?.takeIf(String::isNotBlank) }
            .distinctBy { it.lowercase() }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
}
