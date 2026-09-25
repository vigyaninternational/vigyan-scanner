package com.vigyan.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeFitTest {
    // Fake encoder: size grows with quality and with image area.
    private fun fake(scale: Float, q: Int) = ByteArray((scale * scale * q * 1000).toInt())

    @Test
    fun picksHighestQualityThatFits() {
        val r = SizeFit.fit(50_000, encode = ::fake)
        assertTrue(r.fits)
        assertEquals(1f, r.scale)
        assertEquals(50, r.quality)
    }

    @Test
    fun shrinksWhenLowQualityIsStillTooBig() {
        val r = SizeFit.fit(5_000, encode = ::fake)
        assertTrue(r.fits)
        assertTrue(r.scale < 1f)
        assertTrue(r.bytes.size <= 5_000)
    }

    @Test
    fun fixedSizeThatCannotFit() {
        val r = SizeFit.fit(5_000, allowShrink = false, encode = ::fake)
        assertEquals(false, r.fits)
    }
}

class CutoutTest {
    @Test
    fun paperBecomesTransparentAndInkIsCropped() {
        val w = 100
        val h = 60
        val px = IntArray(w * h) { 0xFFF4F1EA.toInt() } // off-white paper
        for (y in 20 until 30) for (x in 30 until 70) px[y * w + x] = 0xFF101060.toInt() // dark blue ink
        val out = Cutout.cut(Cutout.Image(px, w, h), 0.5f, Cutout.Ink.BLACK)!!
        assertTrue(out.width in 40..50)
        assertTrue(out.height in 10..20)
        assertEquals(0, out.pixels[0] ushr 24) // corner: transparent paper
        val centre = out.pixels[(out.height / 2) * out.width + out.width / 2]
        assertEquals(255, centre ushr 24)
        assertEquals(0x1A1A1A, centre and 0xFFFFFF)
    }

    @Test
    fun blankPaperHasNoInk() {
        val px = IntArray(50 * 50) { 0xFFFFFFFF.toInt() }
        assertNull(Cutout.cut(Cutout.Image(px, 50, 50)))
    }
}

class PassportMathTest {
    @Test
    fun cropHasPassportShapeAndStaysInside() {
        val c = PassportMath.crop(400, 300, 600, 550, 1000, 1400)
        val (l, t, w, h) = c.toList()
        assertEquals(35.0 / 45.0, w.toDouble() / h, 0.01)
        assertTrue(l >= 0 && t >= 0 && l + w <= 1000 && t + h <= 1400)
        // Face centred left-right, and the face box fits inside the crop.
        assertEquals(500.0, l + w / 2.0, 2.0)
        assertTrue(t < 300 && t + h > 550)
    }

    @Test
    fun faceAtTheTopEdgeIsClamped() {
        val (l, t, w, h) = PassportMath.crop(400, 10, 600, 260, 1000, 1400).toList()
        assertEquals(0, t)
        assertTrue(l + w <= 1000 && h <= 1400)
    }
}

class DocClassifierTest {
    @Test
    fun recognisesCommonDocuments() {
        assertEquals(DocType.AADHAAR, DocClassifier.classify("Government of India\nRavi Kumar\nDOB: 05/08/2009\nMALE\n2345 6789 0123\nAadhaar - Aam Aadmi ka Adhikar"))
        assertEquals(DocType.MARKSHEET_10, DocClassifier.classify("BOARD OF SECONDARY EDUCATION, ODISHA\nANNUAL HIGH SCHOOL CERTIFICATE EXAMINATION 2025\nMARK SHEET\nMATHEMATICS 100 30 88"))
        assertEquals(DocType.MARKSHEET_12, DocClassifier.classify("COUNCIL OF HIGHER SECONDARY EDUCATION, ODISHA\nStatement of Marks\nPhysics 70 55"))
        assertEquals(DocType.TC, DocClassifier.classify("DAV Public School\nTRANSFER CERTIFICATE\nName of the pupil: Ravi"))
        assertEquals(DocType.CASTE, DocClassifier.classify("Government of Odisha\nCASTE CERTIFICATE\nThis is to certify that ... belongs to Scheduled Tribe"))
        assertEquals(DocType.FEE_RECEIPT, DocClassifier.classify("MONEY RECEIPT\nReceipt No 1234\nReceived with thanks Rupees Two thousand"))
        assertEquals(DocType.PAN, DocClassifier.classify("INCOME TAX DEPARTMENT\nPermanent Account Number\nABCDE1234F"))
    }

    @Test
    fun unclearTextIsNotSorted() {
        assertNull(DocClassifier.classify("Notice: the college will remain closed on Friday."))
        assertNull(DocClassifier.classify(""))
    }
}

class MarksheetTest {
    private val rows = """
        BOARD OF SECONDARY EDUCATION, ODISHA
        Name of the Candidate   RAVI KUMAR SAHU
        Roll No   123456
        SUBJECT   FULL MARKS   PASS MARKS   MARKS SECURED
        FIRST LANGUAGE (ODIA)   100   30   78
        SECOND LANGUAGE (ENGLISH)   100   30   65   B1
        THIRD LANGUAGE (HINDI)   100   30   70
        MATHEMATICS   100   30   88   A1
        GENERAL SCIENCE   100   30   82
        SOCIAL SCIENCE   100   30   75
        GRAND TOTAL   600   458
    """.trimIndent()

    @Test
    fun readsSubjectsAndTotal() {
        val m = MarksheetExtractor.extract(rows)
        assertEquals(6, m.subjects.size)
        assertEquals(SubjectMark("First Language (Odia)", 100, 78), m.subjects[0])
        assertEquals(SubjectMark("Mathematics", 100, 88), m.subjects[3])
        assertEquals(458, m.total)
        assertEquals(600, m.maxTotal)
        assertEquals(458, m.printedTotal)
        assertEquals(600, m.printedMax)
        assertEquals("76.33", Merit.pct(m.percent))
    }

    @Test
    fun meritRanksWithTies() {
        val r = Merit.rank(
            listOf(
                Merit.Entry("a", "Asha", "1", "Gen", 450, 600),
                Merit.Entry("b", "Bina", "2", "SC", 540, 600),
                Merit.Entry("c", "Chinu", "3", "ST", 450, 600),
                Merit.Entry("d", "Dev", "4", "Gen", 300, 600),
            ),
        )
        assertEquals(listOf("Bina", "Asha", "Chinu", "Dev"), r.map { it.entry.name })
        assertEquals(listOf(1, 2, 2, 4), r.map { it.rank })
    }
}

class ChecklistTest {
    private val types = Checklist.DEFAULT_TYPES

    @Test
    fun missingDocuments() {
        val s = ChecklistStudent(
            "1", "Ravi", "9437012345", "+2 First Year",
            mapOf("Aadhaar card" to "scan1", "10th marksheet" to PAPER, "Caste certificate" to "deletedScan"),
        )
        val missing = Checklist.missing(s, types, setOf("scan1"))
        assertEquals(types.size - 2, missing.size)
        assertTrue("Caste certificate" in missing) // its scan was deleted
        val msg = Checklist.reminder(s, missing.take(2), "Vigyan International Jr. College")
        assertTrue(msg.contains("Ravi (+2 First Year)"))
        assertTrue(msg.contains("1. School leaving certificate / TC"))
    }

    @Test
    fun helpers() {
        assertEquals("919437012345", Checklist.whatsAppNumber("94370 12345"))
        assertEquals("919437012345", Checklist.whatsAppNumber("+91 94370-12345"))
        assertNull(Checklist.whatsAppNumber("12345"))
        assertEquals("Aadhaar card", Checklist.typeFor(DocType.AADHAAR, types))
        assertEquals("School leaving certificate / TC", Checklist.typeFor(DocType.TC, types))
        assertNull(Checklist.typeFor(DocType.PAN, types))
    }
}

class VersionsTest {
    @Test
    fun tagsAndCodes() {
        assertEquals(12, Versions.codeFromTag("v1.0.12"))
        assertEquals(8, Versions.codeFromTag("1.0.8"))
        assertNull(Versions.codeFromTag("latest"))
        assertTrue(Versions.isNewer("v1.0.9", 8))
        assertEquals(false, Versions.isNewer("v1.0.8", 8))
        assertEquals(false, Versions.isNewer("v1.0.7", 8))
    }
}
