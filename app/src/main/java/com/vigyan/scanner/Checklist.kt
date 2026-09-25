package com.vigyan.scanner

/** Marks a document handed in on paper (no scan kept). */
const val PAPER = "paper"

/** One student's admission documents: document name → scan id (or [PAPER]). */
data class ChecklistStudent(
    val id: String,
    val name: String,
    val mobile: String = "",
    val klass: String = "",
    val docs: Map<String, String> = emptyMap(),
)

/** Pure checklist rules (unit-tested). */
object Checklist {

    val DEFAULT_TYPES = listOf(
        "Aadhaar card",
        "10th marksheet",
        "School leaving certificate / TC",
        "Character certificate",
        "Caste certificate",
        "Residence certificate",
        "Income certificate",
        "Bank passbook",
        "Passport photos",
    )

    /** A document counts only if handed in on paper or its scan still exists. */
    fun has(student: ChecklistStudent, type: String, scanIds: Set<String>): Boolean {
        val v = student.docs[type] ?: return false
        return v == PAPER || v in scanIds
    }

    fun missing(student: ChecklistStudent, types: List<String>, scanIds: Set<String>) =
        types.filter { !has(student, it, scanIds) }

    /** Which checklist item a sorted document fills, if any. */
    fun typeFor(doc: DocType, types: List<String>): String? {
        val key = when (doc) {
            DocType.AADHAAR -> "aadhaar"
            DocType.MARKSHEET_10 -> "10th"
            DocType.TC, DocType.CLC -> "leaving"
            DocType.CHARACTER -> "character"
            DocType.CASTE -> "caste"
            DocType.RESIDENCE -> "residence"
            DocType.INCOME -> "income"
            DocType.BANK -> "bank"
            DocType.MIGRATION -> "migration"
            DocType.BIRTH -> "birth"
            else -> return null
        }
        return types.firstOrNull { it.lowercase().contains(key) }
    }

    /** WhatsApp message to the parent listing what is still to be submitted. */
    fun reminder(student: ChecklistStudent, missing: List<String>, college: String): String =
        buildString {
            append("Dear Parent,\n\n")
            append("For the admission of ${student.name}")
            if (student.klass.isNotBlank()) append(" (${student.klass})")
            append(", the following documents are still to be submitted to the college office:\n\n")
            missing.forEachIndexed { i, d -> append("${i + 1}. $d\n") }
            append("\nPlease submit them at the earliest.\n\n- $college")
        }

    /** WhatsApp wants the number with country code, digits only. */
    fun whatsAppNumber(mobile: String): String? {
        val d = mobile.filter(Char::isDigit)
        return when {
            d.length == 10 -> "91$d"
            d.length == 12 && d.startsWith("91") -> d
            d.length == 11 && d.startsWith("0") -> "91" + d.drop(1)
            else -> null
        }
    }
}
