package com.vigyan.scanner

/** Kinds of documents the app recognises, with the folder each one is sorted into. */
enum class DocType(val label: String, val folder: String) {
    AADHAAR("Aadhaar card", "Aadhaar cards"),
    PAN("PAN card", "PAN cards"),
    MARKSHEET_10("10th marksheet", "Marksheets"),
    MARKSHEET_12("12th marksheet", "Marksheets"),
    TC("Transfer certificate", "Certificates"),
    CLC("Leaving certificate", "Certificates"),
    CASTE("Caste certificate", "Certificates"),
    INCOME("Income certificate", "Certificates"),
    RESIDENCE("Residence certificate", "Certificates"),
    BIRTH("Birth certificate", "Certificates"),
    MIGRATION("Migration certificate", "Certificates"),
    CHARACTER("Character certificate", "Certificates"),
    FEE_RECEIPT("Fee receipt", "Receipts"),
    BANK("Bank passbook", "Bank passbooks"),
    ADMISSION_FORM("Admission form", "Admission forms"),
}

/**
 * Guesses what a scanned document is from its OCR text, by scoring tell-tale words
 * ("Unique Identification Authority", "Council of Higher Secondary Education", "Transfer
 * Certificate" …). Returns null when nothing is clear enough. Pure Kotlin, unit-tested.
 */
object DocClassifier {

    private class Rule(val type: DocType, val weight: Int, val pattern: Regex)

    private fun r(type: DocType, weight: Int, p: String) = Rule(type, weight, Regex(p, RegexOption.IGNORE_CASE))

    private const val MARKS = """mark\s*-?\s*sheet|statement\s+of\s+marks|marks\s+(?:secured|obtained)"""

    private val RULES = listOf(
        r(DocType.AADHAAR, 3, """unique\s+identification\s+authority|\buidai\b"""),
        r(DocType.AADHAAR, 2, """\baadha+r\b"""),
        r(DocType.AADHAAR, 2, """(?<!\d)[2-9]\d{3}\s\d{4}\s\d{4}(?!\d)"""),
        r(DocType.AADHAAR, 1, """government\s+of\s+india|\bvid\s*:|enrol+ment\s+no"""),

        r(DocType.PAN, 3, """income\s+tax\s+department|permanent\s+account\s+number"""),
        r(DocType.PAN, 2, """(?<![A-Z0-9])[A-Z]{5}\d{4}[A-Z](?![A-Z0-9])"""),

        r(DocType.MARKSHEET_10, 3, """board\s+of\s+secondary\s+education|high\s+school\s+certificate\s+exam|secondary\s+school\s+(?:certificate\s+)?exam"""),
        r(DocType.MARKSHEET_10, 2, """annual\s+high\s+school|\bclass\s*(?:x|10)\b|\bh\.?\s?s\.?\s?c\.?\b"""),
        r(DocType.MARKSHEET_10, 2, MARKS),

        r(DocType.MARKSHEET_12, 3, """council\s+of\s+higher\s+secondary\s+education|senior\s+(?:school\s+)?secondary|higher\s+secondary\s+(?:certificate\s+)?exam"""),
        r(DocType.MARKSHEET_12, 2, """\bclass\s*(?:xii|12)\b|\+\s?2\b|\bchse\b"""),
        r(DocType.MARKSHEET_12, 2, MARKS),

        r(DocType.TC, 5, """transfer\s+certificate"""),
        r(DocType.TC, 3, """school\s+leaving\s+certificate"""),
        r(DocType.CLC, 5, """college\s+leaving\s+certificate"""),
        r(DocType.CLC, 2, """leaving\s+certificate"""),

        r(DocType.CASTE, 5, """caste\s+certificate"""),
        r(DocType.CASTE, 2, """scheduled\s+(?:caste|tribe)|socially\s+and\s+educationally\s+backward|other\s+backward\s+class"""),
        r(DocType.CASTE, 1, """\bcaste\b"""),

        r(DocType.INCOME, 5, """income\s+certificate"""),
        r(DocType.INCOME, 2, """annual\s+(?:family\s+)?income"""),

        r(DocType.RESIDENCE, 5, """residen(?:ce|t|tial)\s+certificate|domicile"""),
        r(DocType.RESIDENCE, 2, """permanent(?:ly)?\s+resid"""),

        r(DocType.BIRTH, 5, """birth\s+certificate"""),
        r(DocType.BIRTH, 3, """registration\s+of\s+births"""),

        r(DocType.MIGRATION, 5, """migration\s+certificate"""),

        r(DocType.CHARACTER, 5, """character\s+certificate|conduct\s+certificate"""),
        r(DocType.CHARACTER, 2, """good\s+moral\s+character"""),

        r(DocType.FEE_RECEIPT, 4, """(?:fee|fees|money)\s+receipt"""),
        r(DocType.FEE_RECEIPT, 2, """receipt\s+no|received\s+with\s+thanks|tuition\s+fee"""),
        r(DocType.FEE_RECEIPT, 1, """\brupees\b|amount\s+paid"""),

        r(DocType.BANK, 3, """pass\s?book"""),
        r(DocType.BANK, 2, """\bifsc\b"""),
        r(DocType.BANK, 1, """\ba/?c\s+no|account\s+no|\bbranch\b"""),

        r(DocType.ADMISSION_FORM, 4, """admission\s+form|application\s+(?:form\s+)?for\s+admission"""),
        r(DocType.ADMISSION_FORM, 1, """signature\s+of\s+the\s+(?:applicant|candidate|parent|guardian)"""),
    )

    fun classify(text: String): DocType? {
        if (text.isBlank()) return null
        val scores = mutableMapOf<DocType, Int>()
        for (rule in RULES) if (rule.pattern.containsMatchIn(text)) scores.merge(rule.type, rule.weight, Int::plus)
        val best = scores.maxByOrNull { it.value } ?: return null
        return best.key.takeIf { best.value >= 3 }
    }
}
