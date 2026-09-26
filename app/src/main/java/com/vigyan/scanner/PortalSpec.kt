package com.vigyan.scanner

import org.json.JSONArray
import org.json.JSONObject

/**
 * A portal upload rule: a JPG (or PNG) of a fixed pixel size (0 = keep the shape) or a PDF,
 * between [minKb] and [maxKb]. The presets are common limits; always check the portal's own
 * instructions and use Custom when they differ.
 */
data class PortalSpec(
    val label: String,
    val pdf: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val minKb: Int = 0,
    val maxKb: Int,
    val gray: Boolean = false,
    val suffix: String = "photo",
    /** PNG instead of JPG (lossless: only the pixel size can bring it under the limit). */
    val png: Boolean = false,
) {
    companion object {
        val PRESETS = listOf(
            PortalSpec("Photo · 3.5×4.5 cm (413×531 px) · 20–50 KB", pdf = false, width = 413, height = 531, minKb = 20, maxKb = 50),
            PortalSpec("Photo · 200×230 px · under 50 KB", pdf = false, width = 200, height = 230, maxKb = 50),
            PortalSpec("Photo · under 100 KB (any size)", pdf = false, maxKb = 100),
            PortalSpec("Signature · 140×60 px · 10–20 KB", pdf = false, width = 140, height = 60, minKb = 10, maxKb = 20, suffix = "signature"),
            PortalSpec("Signature · 350×150 px · under 30 KB", pdf = false, width = 350, height = 150, maxKb = 30, suffix = "signature"),
            PortalSpec("Document image (JPG) · under 200 KB", pdf = false, maxKb = 200, suffix = "document"),
            PortalSpec("Document PDF · under 100 KB", pdf = true, maxKb = 100),
            PortalSpec("Document PDF · under 200 KB", pdf = true, maxKb = 200),
            PortalSpec("Document PDF · under 500 KB", pdf = true, maxKb = 500),
            PortalSpec("Document PDF · under 1 MB", pdf = true, maxKb = 1024),
            PortalSpec("Document PDF · under 2 MB", pdf = true, maxKb = 2048),
        )

        fun toJson(list: List<PortalSpec>): String = JSONArray(
            list.map { s ->
                JSONObject().put("label", s.label).put("pdf", s.pdf).put("width", s.width).put("height", s.height)
                    .put("minKb", s.minKb).put("maxKb", s.maxKb).put("gray", s.gray).put("suffix", s.suffix).put("png", s.png)
            },
        ).toString()

        fun fromJson(json: String?): List<PortalSpec> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val a = JSONArray(json)
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    PortalSpec(
                        label = o.getString("label"),
                        pdf = o.optBoolean("pdf"),
                        width = o.optInt("width"),
                        height = o.optInt("height"),
                        minKb = o.optInt("minKb"),
                        maxKb = o.optInt("maxKb", 100),
                        gray = o.optBoolean("gray"),
                        suffix = o.optString("suffix", "file"),
                        png = o.optBoolean("png"),
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
