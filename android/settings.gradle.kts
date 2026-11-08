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
        // Vendored sherpa-onnx AAR (Moonshine / SenseVoice). No official Maven artifact; CI
        // downloads the release AAR into app/libs (gitignored).
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "OpenWispr"
include(":app")
include(":lib")
