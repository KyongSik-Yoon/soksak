package com.voicerewriter

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The XML theme (Material3 DayNight) never asks for dark status bar icons, so a full-screen
 * activity that skips enableEdgeToEdge() keeps white icons on its light background and the clock
 * and battery vanish (seen on the licences and Compare screens, 2026-11-13). Every opaque activity
 * in the manifest must call it; the translucent sheets sit over another app and keep that app's bar.
 */
class SystemBarsTest {
    // Unit tests run with the module directory (android/app) as the working directory.
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun everyOpaqueActivitySetsItsSystemBarIcons() {
        val activities = Regex("""<activity\b(.*?)(/>|</activity>)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("no activities parsed", activities.size >= 5)
        for (attrs in activities) {
            if ("Translucent" in attrs) continue
            val name = Regex("""android:name="\.([A-Za-z]+)"""").find(attrs)!!.groupValues[1]
            val src = File("src/main/java/com/voicerewriter/$name.kt")
            assertTrue("$name.kt not found", src.isFile)
            // ISO-8859-1: LearnedVocabActivity.kt holds a NUL character, which is fine for a byte scan.
            assertTrue("$name never calls enableEdgeToEdge()", "enableEdgeToEdge(" in src.readText(Charsets.ISO_8859_1))
        }
    }
}
