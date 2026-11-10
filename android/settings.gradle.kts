pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central, tried before Central itself. Central hands some large
        // jars (the Kotlin compiler) off to GitHub release downloads, and when those returned 504
        // (2026-11-08) every CI build failed; the mirror serves the files directly.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // Vendored sherpa-onnx AAR (Moonshine / SenseVoice). No official Maven artifact; CI
        // downloads the release AAR into app/libs (gitignored).
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Soksak"
include(":app")
include(":lib")
