pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract (Odia text reading) is published on JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "Vigyan Scanner"
include(":app")
