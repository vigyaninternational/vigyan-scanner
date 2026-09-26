package com.vigyan.scanner

import java.util.Calendar

/**
 * "Scan to fill": picks form fields (name, father's name, date of birth, mobile, Aadhaar …) out of
 * OCR text. It looks for printed labels first ("Name :", "D.O.B", "Mobile No."), then falls back to
 * patterns (a 10-digit mobile, an email, a 12-digit Aadhaar number, an Aadhaar-card layout).
 * The user always checks and edits the result before saving it.
 *
 * Pure Kotlin (no Android), so it is covered by plain unit tests.
 */
object FormExtractor {

    /** [csvHeader] matches the Vigyan ERP student CSV import where a column exists there. */
    data class Field(val key: String, val label: String, val csvHeader: String, val multiLine: Boolean = false)

    val FIELDS = listOf(
        Field("name", "Name", "StudentName"),
        Field("father", "Father's / Guardian's name", "FatherName"),
        Field("mother", "Mother's name", "MotherName"),
        Field("dob", "Date of birth (dd/mm/yyyy)", "DOB(yyyy-MM-dd)"),
        Field("gender", "Gender", "Gender(MALE/FEMALE/OTHER)"),
        Field("mobile1", "Mobile", "MobileNo1"),
        Field("mobile2", "Other mobile", "MobileNo2"),
        Field("email", "Email", "Email"),
        Field("aadhaar", "Aadhaar no.", "AadhaarNo"),
        Field("roll", "Roll no.", "RollNo"),
        Field("admission", "Admission / Registration no.", "AdmissionNo"),
        Field("address", "Address", "Address", multiLine = true),
        Field("pin", "PIN code", "PinCode"),
        // From marksheets and pass certificates (10th, +2…).
        Field("school", "School / college", "SchoolName"),
        Field("exam", "Exam passed (and year)", "ExamPassed"),
        Field("marks", "Marks (subject-wise)", "Marks", multiLine = true),
        Field("total", "Total marks", "TotalMarks"),
        Field("percent", "Percentage", "Percentage"),
        Field("grade", "Grade / division", "Grade"),
    )

    private const val OPT_NO = """(?:\s*(?:no|number|num|code)\b\.?)?"""

    // "Father's occupation", "Mother tongue", "Father's mobile": not a name (the mobile label still counts).
    private const val NOT_A_NAME = """(?!['’]?s?\s*(?:tongue|occupation|profession|income|qualification|mobile|phone|contact|e-?mail|address|signature|annual))"""

    /** Label patterns per field. Order matters only for ties; contained matches are dropped. */
    private val LABELS: List<Pair<String, Regex>> = listOf(
        "father" to """father['’]?s?\s*/?\s*(?:guardian['’]?s?)?\s*name|name\s*of\s*(?:the\s*)?(?:father|guardian)|father['’]?s?$NOT_A_NAME|guardian['’]?s?\s*name|guardian['’]?s?$NOT_A_NAME|\bs\s*/\s*o\b|\bd\s*/\s*o\b|\bc\s*/\s*o\b""",
        "mother" to """mother['’]?s?\s*name|name\s*of\s*(?:the\s*)?mother|mother['’]?s?$NOT_A_NAME""",
        "name" to """(?:student['’]?s?|candidate['’]?s?|applicant['’]?s?|full)\s*name|name\s*of\s*(?:the\s*)?(?:student|candidate|applicant)|name""",
        "dob" to """date\s*of\s*birth|birth\s*date|d\s*\.?\s*o\s*\.?\s*b\b\.?|year\s*of\s*birth|born\s*on""",
        "grade" to """grade(?!\s*point)|division""",
        "school" to """(?:name\s*of\s*(?:the\s*)?)?(?:last\s+)?(?:school|college|institution)(?:\s*(?:last\s+)?(?:attended|name))?(?!\s*(?:code|leaving|certificate|examination|board))""",
        "gender" to """gender|sex""",
        "mobile" to """mobile$OPT_NO|mob\b\.?$OPT_NO|phone$OPT_NO|contact$OPT_NO|whats\s*app$OPT_NO""",
        "email" to """e\s*-?\s*mail(?:\s*id|\s*address)?""",
        "aadhaar" to """aadha+r$OPT_NO|uid$OPT_NO""",
        "roll" to """roll$OPT_NO""",
        "admission" to """admission$OPT_NO|reg(?:istration|d)?\.?\s*(?:no|number)\b\.?|enrol+ment$OPT_NO""",
        "address" to """(?:permanent|present|residential|correspondence|postal)?\s*address""",
        "pin" to """pin\s*code|pin\b|postal\s*code""",
    ).map { (k, p) -> k to Regex("""(?<![a-z])(?:$p)(?![a-z])""", RegexOption.IGNORE_CASE) }

    private val MOBILE = Regex("""(?<!\d)(?:\+?91[\s-]?|0)?([6-9]\d{4}[\s-]?\d{5})(?!\d)""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val AADHAAR = Regex("""(?<!\d)([2-9]\d{3})[\s-]?(\d{4})[\s-]?(\d{4})(?!\d)""")
    private val PIN = Regex("""(?<!\d)([1-9]\d{2})\s?(\d{3})(?!\d)""")
    private val GENDER_WORD = Regex("""(?<![a-z])(female|male|transgender)(?![a-z])""", RegexOption.IGNORE_CASE)
    private const val NOT_PERSON_WORDS = "school|college|institut|board|bank|branch|exam|course|subject|university|village|district|company"
    private val NOT_PERSON = Regex(NOT_PERSON_WORDS, RegexOption.IGNORE_CASE)
    private val NAME_OF_THING = Regex("""^\s*of\s+(?:the\s+)?(?:$NOT_PERSON_WORDS)""", RegexOption.IGNORE_CASE)
    private val GAP = Regex("""\s{3,}""")
    private val LEAD = Regex("""^[\s:;.\-–—=|>)]+""")

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private class Hit(val field: String, val start: Int, val end: Int)

    fun extract(text: String): Map<String, String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("--- Page") }
        val hitsPerLine = lines.map { labelHits(it) }
        val raw = mutableMapOf<String, MutableList<String>>()

        lines.forEachIndexed { i, line ->
            val hits = hitsPerLine[i]
            hits.forEachIndexed { h, hit ->
                val stop = if (h + 1 < hits.size) hits[h + 1].start else line.length
                var value = line.substring(hit.end, stop).replace(LEAD, "").trim()
                // "Name :" on one line and the value on the next.
                if (value.isEmpty() && i + 1 < lines.size && hitsPerLine[i + 1].isEmpty()) value = lines[i + 1]
                if (hit.field == "address") {
                    // An address usually runs over the next line or two.
                    var j = i + 1
                    if (value == lines.getOrNull(i + 1)) j++
                    while (j < lines.size && j <= i + 3 && hitsPerLine[j].isEmpty() && value.length < 160) {
                        value += ", " + lines[j]
                        j++
                    }
                }
                if (value.isNotEmpty()) raw.getOrPut(hit.field) { mutableListOf() } += value
            }
        }

        val out = linkedMapOf<String, String>()
        fun first(field: String, clean: (String) -> String?) {
            raw[field].orEmpty().firstNotNullOfOrNull { v -> clean(v)?.takeIf { it.isNotBlank() } }?.let { out[field] = it }
        }
        first("name", ::cleanName)
        first("father", ::cleanName)
        first("mother", ::cleanName)
        first("dob") { normalizeDate(it) ?: Regex("""(?<!\d)(19|20)\d{2}(?!\d)""").find(it)?.value }
        first("gender", ::cleanGender)
        first("email") { EMAIL.find(it)?.value }
        first("aadhaar") { v -> AADHAAR.find(v)?.let { "${it.groupValues[1]} ${it.groupValues[2]} ${it.groupValues[3]}" } }
        first("roll", ::cleanId)
        first("admission", ::cleanId)
        first("address") { v -> v.replace(GAP, ", ").trim(' ', ',').takeIf { it.length >= 4 } }
        first("pin") { v -> PIN.find(v)?.let { it.groupValues[1] + it.groupValues[2] } }
        first("school", ::cleanSchool)
        first("grade", ::cleanGrade)

        // Board pass certificates and marksheets word things differently (no "Name:" labels).
        certificate(lines, out)
        val marks = MarksheetExtractor.extract(text)
        if (marks.subjects.size >= 3) {
            out["marks"] = marks.subjects.joinToString("\n") { "${it.subject}: ${it.obtained}/${it.max}" }
            out["total"] = "${marks.total} / ${marks.max}"
            out["percent"] = marks.percentText()
        }

        // Mobiles: labelled ones first, then any other Indian mobile number in the text.
        val mobiles = LinkedHashSet<String>()
        raw["mobile"].orEmpty().forEach { v -> MOBILE.findAll(v).forEach { mobiles += digits(it.groupValues[1]) } }
        MOBILE.findAll(text).forEach { m ->
            val d = digits(m.groupValues[1])
            // Skip a 10-digit run that is really part of a 12-digit Aadhaar number.
            if (AADHAAR.findAll(text).none { a -> m.range.first >= a.range.first && m.range.last <= a.range.last }) mobiles += d
        }
        mobiles.elementAtOrNull(0)?.let { out["mobile1"] = it }
        mobiles.elementAtOrNull(1)?.let { out["mobile2"] = it }

        // Pattern fallbacks for fields with no label.
        if ("email" !in out) EMAIL.find(text)?.let { out["email"] = it.value }
        if ("aadhaar" !in out) {
            AADHAAR.findAll(text).firstOrNull { it.value.contains(Regex("""\d{4}[\s-]\d{4}[\s-]\d{4}""")) }
                ?.let { out["aadhaar"] = "${it.groupValues[1]} ${it.groupValues[2]} ${it.groupValues[3]}" }
        }
        if ("gender" !in out) GENDER_WORD.find(text)?.let { out["gender"] = cleanGender(it.value)!! }
        if ("pin" !in out) out["address"]?.let { a -> PIN.findAll(a).lastOrNull()?.let { out["pin"] = it.groupValues[1] + it.groupValues[2] } }

        // Aadhaar-card layout: the holder's name is the line just above the DOB line.
        if ("name" !in out) {
            val dobLine = lines.indices.indexOfFirst { i -> hitsPerLine[i].any { it.field == "dob" } }
            if (dobLine > 0) {
                (dobLine - 1 downTo maxOf(0, dobLine - 3)).map { lines[it] }
                    .firstOrNull { looksLikePersonName(it) }
                    ?.let { out["name"] = cleanName(it)!! }
            }
        }

        return FIELDS.mapNotNull { f -> out[f.key]?.let { f.key to it } }.toMap()
    }

    /** Converts a dd/mm/yyyy date to yyyy-MM-dd for the ERP CSV; blank if it isn't a full date. */
    fun toIsoDate(display: String): String {
        val m = Regex("""^(\d{2})/(\d{2})/(\d{4})$""").find(display.trim()) ?: return ""
        return "${m.groupValues[3]}-${m.groupValues[2]}-${m.groupValues[1]}"
    }

    /** Finds a date in [s] and returns it as dd/mm/yyyy, or null. */
    fun normalizeDate(s: String): String? {
        Regex("""(?<!\d)(\d{4})[/.\-](\d{1,2})[/.\-](\d{1,2})(?!\d)""").find(s)?.let { m ->
            return build(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        Regex("""(?<!\d)(\d{1,2})\s*[/.\-]\s*(\d{1,2})\s*[/.\-]\s*(\d{4}|\d{2})(?!\d)""").find(s)?.let { m ->
            return build(m.groupValues[1].toInt(), m.groupValues[2].toInt(), year(m.groupValues[3]))
        }
        Regex("""(?<!\d)(\d{1,2})(?:st|nd|rd|th)?[\s\-/.,]*([A-Za-z]{3,9})[\s\-/.,]*(\d{4})(?!\d)""").find(s)?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[2].take(3).lowercase()) + 1
            if (month > 0) return build(m.groupValues[1].toInt(), month, m.groupValues[3].toInt())
        }
        return null
    }

    private fun build(d: Int, m: Int, y: Int): String? {
        if (d !in 1..31 || m !in 1..12 || y !in 1900..2100) return null
        return "%02d/%02d/%04d".format(d, m, y)
    }

    private fun year(y: String): Int {
        if (y.length == 4) return y.toInt()
        val nowYY = Calendar.getInstance().get(Calendar.YEAR) % 100
        val v = y.toInt()
        return if (v > nowYY) 1900 + v else 2000 + v
    }

    /**
     * A blank field on a paper form: a printed label with nothing after it but a colon, dots or a
     * line ("Name : ________"). Returns the field key, or null when there is no label or the field
     * is already written in.
     */
    fun blankLabel(line: String): String? = blankLabelEnd(line)?.first

    /** Like [blankLabel], plus where the label (and any colon right after it) ends in [line]. */
    fun blankLabelEnd(line: String): Pair<String, Int>? {
        val hit = labelHits(line).lastOrNull() ?: return null
        val rest = line.substring(hit.end).replace(Regex("""[\s:;.,\-_–—=|/\\()\[\]]+"""), "")
        if (rest.length > 2) return null
        var end = hit.end
        while (end < line.length && (line[end] == ' ' || line[end] == ':' || line[end] == '-')) end++
        return (if (hit.field == "mobile") "mobile1" else hit.field) to end
    }

    private fun labelHits(line: String): List<Hit> {
        val all = LABELS.flatMap { (field, re) -> re.findAll(line).map { Hit(field, it.range.first, it.range.last + 1) } }
            .filter { it.end > it.start }
        // Drop a label found inside a longer one ("name" inside "father's name").
        val kept = all.filter { h ->
            all.none { o -> o !== h && o.start <= h.start && o.end >= h.end && (o.end - o.start) > (h.end - h.start) }
        }.distinctBy { it.start }
            // "School name", "Name of the institution": not the person's name.
            .filterNot { h -> h.field == "name" && (NOT_PERSON.containsMatchIn(line.substring(0, h.start)) || NAME_OF_THING.containsMatchIn(line.substring(h.end))) }
            // "(Mother)" in brackets marks the name before it; it is not a "Mother:" label.
            .filterNot { h -> line.substring(0, h.start).trimEnd().endsWith("(") && line.substring(h.end).trimStart().startsWith(")") }
        val sorted = kept.sortedBy { it.start }
        // A bare word like "name" deep inside a sentence is not a label: keep labels near the
        // start of the line, or those followed by a colon/dash.
        return sorted.filter { h ->
            h.start <= 4 || sorted.any { it !== h && it.end <= h.start } ||
                line.substring(h.end).trimStart().startsWith(":") || line.substring(h.end).trimStart().startsWith("-")
        }
    }

    // Script lettering is often misread ("Certifed thal", "Cerlified hat"), so this is loose.
    /**
     * Two readings of the same scan: [original] and one of a cleaned copy ([cleaned], only dark
     * ink kept). Names come from whichever has the fuller name (a patterned background often
     * breaks them up); marks from whichever found more subjects; the rest from the original.
     */
    fun merge(original: Map<String, String>, cleaned: Map<String, String>): Map<String, String> {
        fun words(v: String) = v.split(Regex("""\s+""")).count { w -> w.count(Char::isLetter) >= 2 }
        val marksA = original["marks"]?.lines()?.size ?: 0
        val marksB = cleaned["marks"]?.lines()?.size ?: 0
        return FIELDS.mapNotNull { f ->
            val a = original[f.key]?.takeIf { it.isNotBlank() }
            val b = cleaned[f.key]?.takeIf { it.isNotBlank() }
            val v = when {
                a == null -> b
                b == null -> a
                f.key in setOf("name", "father", "mother", "school") -> if (words(b) >= words(a)) b else a
                f.key in setOf("marks", "total", "percent") -> if (marksB >= marksA) b else a
                else -> a
            }
            v?.let { f.key to it }
        }.toMap()
    }

    private val CERTIFIED = Regex("""c[a-z]{2,9}\s+t?h[a-z]{1,3}(?![a-z])\s*[:\-]?\s*""", RegexOption.IGNORE_CASE)

    /** "(Mother)", "(Father)" beside a parent's name, allowing for a misread letter. */
    private val ROLE_WORD = Regex("""(?<![a-z])(m[oa]th[ae]r|f[ae]th[ae]r|guardian)(?![a-z])""", RegexOption.IGNORE_CASE)

    private val NOT_NAME_WORDS = setOf(
        "MOTHER", "FATHER", "GUARDIAN", "CERTIFIED", "THAT", "SON", "DAUGHTER", "WARD", "OF", "AND", "BORN", "ON",
        "FROM", "REGULAR", "PRIVATE", "EXAMINATION", "CERTIFICATE", "SCHOOL", "HIGH", "BOARD", "MR", "MRS", "MS", "SHRI", "SMT",
    )

    /**
     * The person's name written in capitals, even when reading split it into pieces
     * ("PRAGYNA   PARAMITA   NAYAK"): the longest run of capital-letter words in [text].
     */
    fun capsName(text: String): String? {
        val tokens = text.split(Regex("""[\s/,:;|()]+""")).filter { it.isNotBlank() }
        val runs = mutableListOf<List<String>>()
        var run = mutableListOf<String>()
        for (t in tokens) {
            val w = t.trim('.', '\'')
            if (w.isNotEmpty() && Regex("""[A-Z][A-Z.']*""").matches(t) && w !in NOT_NAME_WORDS) {
                run += t.trimEnd('.')
            } else {
                if (run.isNotEmpty()) runs += run
                run = mutableListOf()
            }
        }
        if (run.isNotEmpty()) runs += run
        val best = runs.filter { r -> r.size >= 2 || r[0].length >= 3 }
            .maxByOrNull { r -> r.size * 100 + r.sumOf { it.length } } ?: return null
        return best.joinToString(" ").takeIf { it.count(Char::isLetter) >= 3 }
    }

    /**
     * Parents found by the "(Mother)" / "(Father)" words beside them, for when "Son/Daughter of"
     * can't be read. The name is on the same row, or the row just above.
     */
    private fun parentsByRole(lines: List<String>, out: MutableMap<String, String>) {
        var firstRoleLine = -1
        for ((i, l) in lines.withIndex()) {
            val role = ROLE_WORD.findAll(l).lastOrNull() ?: continue
            // Only a marker at the end of the row ("… PARAJA (Mother)"); labels like "Mother's name :"
            // or "Mother tongue" are something else.
            if (l.substring(role.range.last + 1).trim().trimStart(')').isNotBlank()) continue
            val key = if (role.value.lowercase().startsWith("m")) "mother" else "father"
            if (firstRoleLine < 0) firstRoleLine = i
            if (key in out) continue
            val candidates = listOf(l.substring(0, role.range.first), lines.getOrNull(i - 1).orEmpty())
            val name = candidates.firstNotNullOfOrNull { c ->
                capsName(c)?.takeIf { it != out["name"] && it != out["mother"] && it != out["father"] && ROLE_WORD.find(c) == null }
            } ?: continue
            out[key] = name
        }
        // The student's name is usually the capitals just above the first parent.
        if ("name" !in out && firstRoleLine > 0) {
            (firstRoleLine - 1 downTo maxOf(0, firstRoleLine - 2)).firstNotNullOfOrNull { j ->
                capsName(lines[j])?.takeIf { it != out["mother"] && it != out["father"] }
            }?.let { out["name"] = it }
        }
    }
    private val CHILD_OF = Regex("""(?<![a-z])(?:son|daughter|ward)(?:\s*/\s*(?:son|daughter|ward))*\s+of(?![a-z])\s*[:\-]?\s*""", RegexOption.IGNORE_CASE)
    private val AND_LEAD = Regex("""^and(?![a-z])\s*""", RegexOption.IGNORE_CASE)
    private val ROLE = Regex("""\(?\s*(?<![a-z])(mother|father|guardian)(?![a-z])\s*\)?""", RegexOption.IGNORE_CASE)
    private val FROM_LEAD = Regex("""^from(?![a-z])\s*[:\-]?\s*""", RegexOption.IGNORE_CASE)
    private val EXAM = Regex(
        """(?:high\s+school\s+certificate|higher\s+secondary(?:\s+certificate)?|secondary\s+school(?:\s+certificate)?|senior\s+school\s+certificate|all\s+india\s+secondary\s+school|matriculation|intermediate|annual\s+secondary)\s+examination""",
        RegexOption.IGNORE_CASE,
    )
    private val YEAR = Regex("""(?<!\d)(19|20)\d{2}(?!\d)""")

    /**
     * Pass certificates (e.g. BSE Odisha 10th): "Certified that NAME", "Son/Daughter of X (Mother)",
     * "and Y (Father)", "born on 21/12/2008", "…Examination held … February - 2024", "from SCHOOL".
     */
    private fun certificate(lines: List<String>, out: MutableMap<String, String>) {
        if ("name" !in out) {
            for ((i, l) in lines.withIndex()) {
                val m = CERTIFIED.find(l)?.takeIf { it.range.first <= 4 } ?: continue
                val v = l.substring(m.range.last + 1).ifBlank { lines.getOrNull(i + 1).orEmpty() }
                val found = capsName(v) ?: if (l.contains("certif", ignoreCase = true)) cleanName(v) else null
                found?.let { out["name"] = it } ?: continue
                break
            }
        }

        if ("exam" !in out) {
            lines.firstNotNullOfOrNull { EXAM.find(it)?.value }?.let { exam ->
                val year = lines.firstNotNullOfOrNull { l ->
                    if (Regex("""held|month|examination|session""", RegexOption.IGNORE_CASE).containsMatchIn(l)) YEAR.find(l)?.value else null
                }
                out["exam"] = titleCase(exam) + (year?.let { " $it" } ?: "")
            }
        }

        if ("school" !in out) {
            for ((i, l) in lines.withIndex()) {
                val m = FROM_LEAD.find(l) ?: continue
                val before = (maxOf(0, i - 2) until i).joinToString(" ") { lines[it] }
                if (!Regex("""examination|passed|held|studied""", RegexOption.IGNORE_CASE).containsMatchIn(before)) continue
                cleanSchool(l.substring(m.range.last + 1))?.let { out["school"] = it }
                break
            }
        }

        val c = lines.indexOfFirst { CHILD_OF.containsMatchIn(it) }
        if (c < 0) {
            parentsByRole(lines, out)
            return
        }
        // The name is often the capital-letter line just above "Son/Daughter of".
        if ("name" !in out && c > 0) {
            Regex("""([A-Z][A-Z.' ]{3,}[A-Z])\s*$""").find(lines[c - 1])?.let { m -> cleanName(m.groupValues[1])?.let { out["name"] = it } }
        }
        // Parents, with "(Mother)" / "(Father)" beside them when the certificate says which is which.
        val parts = mutableListOf<Pair<String, String?>>()
        fun take(text: String, next: String?) {
            text.split(Regex("""\s+and\s+""", RegexOption.IGNORE_CASE)).forEach { piece ->
                val role = ROLE.find(piece)
                val namePart = if (role != null) piece.substring(0, role.range.first) else piece
                var r = role?.groupValues?.get(1)?.lowercase()
                if (r == null && next != null) r = ROLE.matchEntire(next.trim())?.groupValues?.get(1)?.lowercase()
                if (namePart.isNotBlank()) parts += namePart to r
            }
        }
        val m = CHILD_OF.find(lines[c])!!
        take(lines[c].substring(m.range.last + 1), lines.getOrNull(c + 1))
        for (j in c + 1..minOf(c + 2, lines.lastIndex)) {
            if (AND_LEAD.containsMatchIn(lines[j])) {
                take(AND_LEAD.replace(lines[j], ""), lines.getOrNull(j + 1))
                break
            }
        }
        val unknown = mutableListOf<String>()
        parts.forEach { (text, role) ->
            val n = capsName(text) ?: cleanName(text) ?: return@forEach
            when (role) {
                "mother" -> if ("mother" !in out) out["mother"] = n
                "father", "guardian" -> if ("father" !in out) out["father"] = n
                else -> unknown += n
            }
        }
        unknown.forEach { n -> if ("father" !in out) out["father"] = n else if ("mother" !in out) out["mother"] = n }
        parentsByRole(lines, out)
    }

    private fun titleCase(s: String) = s.lowercase().split(Regex("""\s+""")).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun cleanSchool(v: String): String? =
        v.split(GAP).firstOrNull { it.isNotBlank() }?.trim(' ', ':', '-', '.', ',')
            ?.takeIf { it.count(Char::isLetter) >= 4 }

    private fun cleanGrade(v: String): String? {
        val t = v.split(GAP).firstOrNull { it.isNotBlank() }?.trim(' ', ':', '-', '.') ?: return null
        return Regex("""^(?:[A-F][1-2]?[+]?|O|first|second|third|distinction|pass(?:ed)?)(?:\s+division)?$""", RegexOption.IGNORE_CASE)
            .find(t)?.value?.uppercase()
    }

    private fun cleanName(v: String): String? {
        val part = v.split(GAP).firstOrNull { it.isNotBlank() } ?: return null
        val cleaned = part.replace(Regex("""^(?:mr|mrs|ms|miss|shri|sri|smt|kumari|late)\.?\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[^A-Za-z .']"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.')
        return cleaned.takeIf { it.count(Char::isLetter) >= 2 }
    }

    private fun cleanGender(v: String): String? = when {
        Regex("""(?<![a-z])(female|f|girl)(?![a-z])""", RegexOption.IGNORE_CASE).containsMatchIn(v) -> "FEMALE"
        Regex("""(?<![a-z])(male|m|boy)(?![a-z])""", RegexOption.IGNORE_CASE).containsMatchIn(v) -> "MALE"
        Regex("""(?<![a-z])(other|transgender|t)(?![a-z])""", RegexOption.IGNORE_CASE).containsMatchIn(v) -> "OTHER"
        else -> null
    }

    private fun cleanId(v: String): String? =
        v.split(GAP).firstOrNull { it.isNotBlank() }
            ?.trim()?.split(Regex("""\s+"""))?.firstOrNull()
            ?.trim('.', ',', ':', ';')
            ?.takeIf { it.any(Char::isDigit) && it.length <= 25 }

    private fun looksLikePersonName(line: String): Boolean {
        val words = line.trim().split(Regex("""\s+"""))
        if (words.size !in 2..4) return false
        if (!words.all { w -> w.all { it.isLetter() || it == '.' } }) return false
        val lower = line.lowercase()
        return listOf("government", "india", "father", "mother", "address", "authority", "unique", "identification")
            .none { it in lower }
    }

    private fun digits(s: String) = s.filter(Char::isDigit)
}
