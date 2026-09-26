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

class PinHashTest {
    @Test
    fun hashing() {
        assertEquals(PinHash.hash("abc", "1234"), PinHash.hash("abc", "1234"))
        assertTrue(PinHash.hash("abc", "1234") != PinHash.hash("abd", "1234"))
        assertTrue(PinHash.valid("1234") && PinHash.valid("123456"))
        assertEquals(false, PinHash.valid("123"))
        assertEquals(false, PinHash.valid("12a4"))
    }
}

class OcrLayoutFallbackTest {
    @Test
    fun pagesWithoutPositionsUseTheirText() {
        val (_, rows) = OcrLayout.combine(listOf(PageOcr(100, 100, "ଓଡ଼ିଆ text", emptyList())))
        assertEquals("ଓଡ଼ିଆ text", rows)
    }
}

class QuickScanLogicTest {
    @Test
    fun autoShutterWaitsForStillThenForNewPage() {
        val s = QuickScanLogic.AutoShutter(stillMs = 800)
        var t = 0L
        fun frame(diff: Double) = s.onFrame(diff, 150.0, t).also { t += 100 }
        assertEquals(false, frame(2.0)) // still starts
        repeat(7) { assertEquals(false, frame(2.0)) }
        assertEquals(true, frame(2.0)) // 800 ms still → snap
        repeat(20) { assertEquals(false, frame(2.0)) } // same page: no second snap
        assertEquals(false, frame(40.0)) // page turned
        repeat(8) { assertEquals(false, frame(2.0)) }
        assertEquals(true, frame(2.0)) // next page
    }

    @Test
    fun darkViewNeverSnaps() {
        val s = QuickScanLogic.AutoShutter(stillMs = 100)
        repeat(20) { assertEquals(false, s.onFrame(1.0, 10.0, it * 100L)) }
    }

    @Test
    fun findsPaperOnDarkTable() {
        val w = 100
        val h = 140
        val lum = IntArray(w * h) { 60 } // table
        for (y in 20 until 120) for (x in 15 until 85) lum[y * w + x] = 220 // paper
        val b = QuickScanLogic.paperBox(lum, w, h)!!
        assertEquals(listOf(15, 20, 84, 119), b.toList())
    }

    @Test
    fun paperFillingTheFrameIsNotCropped() {
        val lum = IntArray(100 * 100) { 220 }
        for (i in 0 until 300) lum[i * 7 % lum.size] = 30 // some text
        assertNull(QuickScanLogic.paperBox(lum, 100, 100))
    }

    @Test
    fun levelsStretchPaperToWhite() {
        val lum = IntArray(1000) { if (it < 100) 40 else 190 }
        val (black, white) = QuickScanLogic.levels(lum)
        assertEquals(40, black)
        assertEquals(190, white)
    }
}

class DocDetectTest {
    private val w = 256
    private val h = 200
    // A tilted page, seen slightly at an angle.
    private val corners = floatArrayOf(40f, 30f, 200f, 45f, 185f, 170f, 25f, 155f)

    private fun inside(x: Float, y: Float): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val cross = (corners[j * 2] - corners[i * 2]) * (y - corners[i * 2 + 1]) - (corners[j * 2 + 1] - corners[i * 2 + 1]) * (x - corners[i * 2])
            val s = if (cross >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun scene(tableLum: Int, tableSat: Int): Pair<IntArray, IntArray> {
        val lum = IntArray(w * h)
        val sat = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * w + x
            if (inside(x.toFloat(), y.toFloat())) {
                // Paper with lines of text on it.
                lum[p] = if (y % 12 == 0 && x % 5 != 0) 50 else 225
                sat[p] = 8
            } else {
                lum[p] = tableLum
                sat[p] = tableSat
            }
        }
        return lum to sat
    }

    private fun assertCorners(q: FloatArray?) {
        requireNotNull(q) { "page not found" }
        for (i in 0 until 8) assertEquals("value $i", corners[i].toDouble(), q[i].toDouble(), 4.0)
    }

    @Test
    fun tiltedPageOnDarkTable() {
        val (lum, sat) = scene(tableLum = 45, tableSat = 5)
        assertCorners(DocDetect.findQuad(lum, sat, w, h))
    }

    @Test
    fun pageOnBrownWoodenTable() {
        // The table is nearly as bright as the paper but brown (colourful).
        val (lum, sat) = scene(tableLum = 175, tableSat = 110)
        assertCorners(DocDetect.findQuad(lum, sat, w, h))
    }

    @Test
    fun pageFillingThePhotoIsLeftAlone() {
        val lum = IntArray(w * h) { if (it % 97 == 0) 40 else 225 }
        assertNull(DocDetect.findQuad(lum, null, w, h))
    }

    @Test
    fun noPage() {
        assertNull(DocDetect.findQuad(IntArray(w * h) { 100 }, null, w, h))
    }

    @Test
    fun straightenedSize() {
        val (width, height) = DocDetect.outputSize(floatArrayOf(0f, 0f, 100f, 0f, 100f, 140f, 0f, 140f))
        assertEquals(100f, width)
        assertEquals(140f, height)
    }
}
