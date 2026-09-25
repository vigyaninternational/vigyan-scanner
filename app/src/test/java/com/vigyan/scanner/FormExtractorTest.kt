package com.vigyan.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormExtractorTest {

    @Test
    fun admissionForm_labelsOnSameLine() {
        val text = """
            VIGYAN INTERNATIONAL JUNIOR COLLEGE
            ADMISSION FORM 2026-27
            1. Name of the Student : RAVI KUMAR SAHU
            2. Father's Name : Suresh Kumar Sahu
            3. Mother's Name : Smt. Gita Sahu
            4. Date of Birth : 05/08/2009
            5. Gender : Male
            6. Mobile No. : +91 98765 43210   WhatsApp No : 7008123456
            7. Email ID : ravi.sahu@gmail.com
            8. Aadhaar No : 2345 6789 0123
            9. Permanent Address : At/Po - Jeypore
            Dist - Koraput, Odisha 764001
            10. Roll No : 12345
        """.trimIndent()
        val f = FormExtractor.extract(text)
        assertEquals("RAVI KUMAR SAHU", f["name"])
        assertEquals("Suresh Kumar Sahu", f["father"])
        assertEquals("Gita Sahu", f["mother"])
        assertEquals("05/08/2009", f["dob"])
        assertEquals("MALE", f["gender"])
        assertEquals("9876543210", f["mobile1"])
        assertEquals("7008123456", f["mobile2"])
        assertEquals("ravi.sahu@gmail.com", f["email"])
        assertEquals("2345 6789 0123", f["aadhaar"])
        assertEquals("12345", f["roll"])
        assertEquals("764001", f["pin"])
        assertEquals("At/Po - Jeypore, Dist - Koraput, Odisha 764001", f["address"])
    }

    @Test
    fun valueOnNextLine() {
        val text = """
            Student Name
            Priya Das
            DOB
            12-Jan-2008
            Sex
            F
        """.trimIndent()
        val f = FormExtractor.extract(text)
        assertEquals("Priya Das", f["name"])
        assertEquals("12/01/2008", f["dob"])
        assertEquals("FEMALE", f["gender"])
    }

    @Test
    fun rowLayoutWithGaps() {
        // Ocr.rows joins separate pieces of text on one row with three spaces.
        val text = "Name   Anil Behera   Class   XI\nFather   Rabi Behera   Mobile   9437012345"
        val f = FormExtractor.extract(text)
        assertEquals("Anil Behera", f["name"])
        assertEquals("Rabi Behera", f["father"])
        assertEquals("9437012345", f["mobile1"])
    }

    @Test
    fun aadhaarCardLayout() {
        val text = """
            Government of India
            Sunita Nayak
            DOB: 23/11/2007
            FEMALE
            4521 7896 3210
        """.trimIndent()
        val f = FormExtractor.extract(text)
        assertEquals("Sunita Nayak", f["name"])
        assertEquals("23/11/2007", f["dob"])
        assertEquals("FEMALE", f["gender"])
        assertEquals("4521 7896 3210", f["aadhaar"])
        assertNull("Aadhaar digits must not be read as a mobile", f["mobile1"])
    }

    @Test
    fun occupationAndMotherTongueAreNotNames() {
        val f = FormExtractor.extract("Father's Occupation : Farmer\nMother Tongue : Odia\nFather's Mobile : 9437000001\nFather : Hari Das")
        assertEquals("Hari Das", f["father"])
        assertNull(f["mother"])
        assertEquals("9437000001", f["mobile1"])
    }

    @Test
    fun schoolNameIsNotThePersonsName() {
        val f = FormExtractor.extract("School Name: DAV Public School\nName: Mohan Rao")
        assertEquals("Mohan Rao", f["name"])
    }

    @Test
    fun dates() {
        assertEquals("01/02/2005", FormExtractor.normalizeDate("2005-02-01"))
        assertEquals("15/08/2006", FormExtractor.normalizeDate("15.8.2006"))
        assertEquals("03/03/2004", FormExtractor.normalizeDate("3rd March 2004"))
        assertNull(FormExtractor.normalizeDate("45/13/2004"))
        assertEquals("2009-08-05", FormExtractor.toIsoDate("05/08/2009"))
        assertEquals("", FormExtractor.toIsoDate("2009"))
    }
}
