package com.vigyan.scanner

/**
 * Release tags are "v1.0.<build number>" and the app's versionCode is that same build number
 * (see .github/workflows/build-apk.yml), so a tag tells us whether a release is newer.
 * Pure Kotlin, unit-tested.
 */
object Versions {

    fun codeFromTag(tag: String): Int? = Regex("""(\d+)\s*$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun isNewer(tag: String, installedCode: Int): Boolean = (codeFromTag(tag) ?: 0) > installedCode
}
