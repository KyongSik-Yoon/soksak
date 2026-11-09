// AGP 9.5 needs Gradle 9.6+ (see gradle/wrapper/gradle-wrapper.properties) and compiles Kotlin
// itself, so there is no org.jetbrains.kotlin.android plugin; the Compose plugin's version below
// is what sets the Kotlin version.
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
