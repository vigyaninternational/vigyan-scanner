package com.vigyan.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Stores the admission document checklist (files/checklist.json). */
class ChecklistRepository(context: Context) {

    private val file = File(context.filesDir, "checklist.json")

    class Data(val types: List<String>, val students: List<ChecklistStudent>)

    fun load(): Data = if (file.exists()) parse(file.readText()) else Data(Checklist.DEFAULT_TYPES, emptyList())

    fun save(data: Data) {
        val students = JSONArray()
        data.students.forEach { s ->
            students.put(
                JSONObject().put("id", s.id).put("name", s.name).put("mobile", s.mobile).put("class", s.klass)
                    .put("docs", JSONObject(s.docs)),
            )
        }
        file.writeText(JSONObject().put("types", JSONArray(data.types)).put("students", students).toString())
    }

    companion object {
        fun parse(json: String): Data = try {
            val j = JSONObject(json)
            val t = j.optJSONArray("types")
            val types = if (t != null) (0 until t.length()).map { t.getString(it) } else Checklist.DEFAULT_TYPES
            val s = j.optJSONArray("students") ?: JSONArray()
            val students = (0 until s.length()).map { i ->
                val o = s.getJSONObject(i)
                val d = o.optJSONObject("docs") ?: JSONObject()
                ChecklistStudent(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    mobile = o.optString("mobile"),
                    klass = o.optString("class"),
                    docs = d.keys().asSequence().associateWith { d.getString(it) },
                )
            }
            Data(types, students)
        } catch (e: Exception) {
            Data(Checklist.DEFAULT_TYPES, emptyList())
        }
    }
}
