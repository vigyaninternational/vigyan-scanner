package com.vigyan.scanner

import android.content.SharedPreferences

/** Choices from the Settings screen. */
data class AppSettings(
    /** "system", "light" or "dark". */
    val theme: String = "system",
    /** Size chosen first when saving a scan. */
    val quality: Quality = Quality.NORMAL,
    /** File name template, e.g. "{name}_{date}" (see Naming). */
    val nameTemplate: String = Naming.DEFAULT,
    /** Scan & merge by name: the usual order of documents, one per line (a guide while scanning). */
    val docOrder: String = "10th marksheet\n+2 marksheet / certificate\nCLC (College Leaving Certificate)\nAadhaar card",
    /** Folder picked for "Save to phone" (a document tree), or null for Download/Vigyan Scanner. */
    val saveTree: String? = null,
    /** Remind to back up after this many days (0 = never). */
    val backupDays: Int = 7,
    /** Look for app updates when the app starts. */
    val autoUpdate: Boolean = true,
    /** Quick scan starts with Auto capture on. */
    val quickAuto: Boolean = true,
    /** Quick scan starts with Enhance on. */
    val quickEnhance: Boolean = true,
    /** Warn about blurry, dark or glaring pages. */
    val qualityWarnings: Boolean = true,
) {
    fun save(p: SharedPreferences) {
        p.edit()
            .putString("set_theme", theme)
            .putString("set_quality", quality.name)
            .putString("set_name_template", nameTemplate)
            .putString("set_doc_order", docOrder)
            .putString("set_save_tree", saveTree)
            .putInt("set_backup_days", backupDays)
            .putBoolean("set_auto_update", autoUpdate)
            .putBoolean("set_quick_auto", quickAuto)
            .putBoolean("set_quick_enhance", quickEnhance)
            .putBoolean("set_quality_warnings", qualityWarnings)
            .apply()
    }

    val docOrderList get() = docOrder.lines().map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        fun load(p: SharedPreferences): AppSettings {
            val d = AppSettings()
            return AppSettings(
                theme = p.getString("set_theme", d.theme) ?: d.theme,
                quality = Quality.values().firstOrNull { it.name == p.getString("set_quality", null) } ?: d.quality,
                nameTemplate = p.getString("set_name_template", d.nameTemplate) ?: d.nameTemplate,
                docOrder = p.getString("set_doc_order", d.docOrder) ?: d.docOrder,
                saveTree = p.getString("set_save_tree", null),
                backupDays = p.getInt("set_backup_days", d.backupDays),
                autoUpdate = p.getBoolean("set_auto_update", d.autoUpdate),
                quickAuto = p.getBoolean("set_quick_auto", d.quickAuto),
                quickEnhance = p.getBoolean("set_quick_enhance", d.quickEnhance),
                qualityWarnings = p.getBoolean("set_quality_warnings", d.qualityWarnings),
            )
        }
    }
}
