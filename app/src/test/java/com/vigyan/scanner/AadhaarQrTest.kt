package com.vigyan.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.zip.GZIPOutputStream

class AadhaarQrTest {

    @Test
    fun oldXmlQr() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?> <PrintLetterBarcodeData uid="234567890123" name="Ravi Kumar Sahu" gender="M" yob="2009" co="S/O: Suresh Kumar Sahu" house="12" street="Main Road" loc="Bank Colony" vtc="Jeypore" po="Jeypore" dist="Koraput" state="Odisha" pc="764001" dob="05/08/2009"/>"""
        val f = AadhaarQr.parse(xml)!!
        assertEquals("Ravi Kumar Sahu", f["name"])
        assertEquals("2345 6789 0123", f["aadhaar"])
        assertEquals("MALE", f["gender"])
        assertEquals("05/08/2009", f["dob"])
        assertEquals("Suresh Kumar Sahu", f["father"])
        assertEquals("764001", f["pin"])
        assertEquals("12, Main Road, Bank Colony, Jeypore, Koraput, Odisha, 764001", f["address"])
    }

    @Test
    fun secureQr() {
        val fields = listOf(
            "V2", "3", "012320190805123456789", "Priya Das", "12-01-2008", "F", "D/O: Hari Das",
            "Koraput", "Near Temple", "45", "Station Road", "764020", "Koraput", "Odisha", "Gandhi Nagar", "Koraput", "Koraput",
        )
        val raw = ByteArrayOutputStream()
        fields.forEach { raw.write(it.toByteArray(Charsets.ISO_8859_1)); raw.write(255) }
        raw.write(byteArrayOf(1, 2, 3, 4)) // stands in for the photo and signature
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw.toByteArray()) }
        val number = BigInteger(1, gz.toByteArray()).toString()

        val f = AadhaarQr.parse(number)!!
        assertEquals("Priya Das", f["name"])
        assertEquals("12/01/2008", f["dob"])
        assertEquals("FEMALE", f["gender"])
        assertEquals("Hari Das", f["father"])
        assertEquals("764020", f["pin"])
        assertEquals("45, Gandhi Nagar, Near Temple, Station Road, Koraput, Odisha, 764020", f["address"])
        assertNull(f["aadhaar"])
    }

    @Test
    fun notAadhaar() {
        assertNull(AadhaarQr.parse("https://vigyan.example/fees"))
        assertNull(AadhaarQr.parse("1".repeat(150)))
    }
}
