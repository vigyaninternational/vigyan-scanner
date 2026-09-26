package com.vigyan.scanner

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** File names: templates like "{name}_{date}", person file names (RAHUL_KUMAR) and no duplicates. */
object Naming {

    const val DEFAULT = "{name}"

    /** Ready-made templates for Settings. */
    val PRESETS = listOf("{name}", "{name}_{date}", "{date}_{name}", "{folder}_{name}", "{ref}_{name}")

    fun describe(template: String) = template
        .replace("{name}", "Name").replace("{date}", "Date").replace("{folder}", "Folder").replace("{ref}", "Ref no.")

    /**
     * Fills a template. An empty part is dropped with its separator, so "{ref}_{name}" without
     * a reference number gives just the name.
     */
    fun apply(template: String, name: String, ref: String = "", folder: String = "", created: Long = System.currentTimeMillis()): String {
        val values = mapOf(
            "name" to name.trim(),
            "ref" to ref.trim(),
            "folder" to folder.trim(),
            "date" to SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(created)),
        )
        var t = template.ifBlank { DEFAULT }
        for ((k, v) in values) {
            if (v.isEmpty()) t = t.replace(Regex("""\{$k\}[_\- ]|[_\- ]\{$k\}|\{$k\}"""), "")
        }
        for ((k, v) in values) t = t.replace("{$k}", v)
        return t.trim().ifBlank { name.trim() }
    }

    /** "Rahul kumar" (+ "1234") → "RAHUL_KUMAR" ("RAHUL_KUMAR_1234"). */
    fun personFile(person: String, ref: String = ""): String {
        val base = person.trim().uppercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), "_").trim('_')
        val r = ref.trim().replace(Regex("""[^\p{L}\p{N}-]+"""), "_").trim('_')
        return listOf(base, r).filter { it.isNotEmpty() }.joinToString("_").ifBlank { "DOCUMENTS" }
    }

    /** [name], or "name (2)", "name (3)"… when it is already taken (ignoring case). */
    fun unique(name: String, taken: Collection<String>): String {
        val set = taken.map { it.lowercase() }.toSet()
        if (name.lowercase() !in set) return name
        var n = 2
        while ("$name ($n)".lowercase() in set) n++
        return "$name ($n)"
    }
}

/** Date filter for the scan list. */
enum class DateRange(val label: String) {
    ANY("Any time"),
    TODAY("Today"),
    WEEK("Last 7 days"),
    MONTH("Last 30 days"),
    YEAR("This year"),
    OLDER("Older");

    fun matches(time: Long, now: Long = System.currentTimeMillis()): Boolean {
        val day = 86_400_000L
        fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }
        return when (this) {
            ANY -> true
            TODAY -> cal(time).let { a -> cal(now).let { b -> a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) } }
            WEEK -> now - time < 7 * day
            MONTH -> now - time < 30 * day
            YEAR -> cal(time).get(Calendar.YEAR) == cal(now).get(Calendar.YEAR)
            OLDER -> cal(time).get(Calendar.YEAR) < cal(now).get(Calendar.YEAR)
        }
    }
}
