package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notices screen is how the APK meets the MIT / Apache-2.0 / OFL attribution terms, so a
 * bundled component or a full license text silently dropping out of the file is a licence bug.
 */
class ThirdPartyNoticesTest {
    // Unit tests run with the module directory (android/app) as the working directory.
    private val notices = File("src/main/assets/${LicensesActivity.NOTICES_ASSET}").readText()

    @Test fun listsEveryBundledComponent() {
        for (name in listOf(
            "whisper.cpp", "ONNX Runtime", "Silero VAD", "Shizuku", "sherpa-onnx", "OkHttp",
            "AndroidX", "kotlinx.coroutines", "Apache Commons Compress", "Mulish", "IBM Plex Mono",
            "Qualcomm AI Runtime", "com.qualcomm.qti:qnn-runtime", "RohitAg13/openWispr",
        )) assertTrue("missing $name", notices.contains(name))
    }

    @Test fun carriesFullLicenseTexts() {
        assertTrue(notices.contains("Permission is hereby granted, free of charge"))
        assertTrue(notices.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION"))
        assertTrue(notices.contains("SIL OPEN FONT LICENSE Version 1.1"))
    }

    @Test fun splitsIntoParagraphs() {
        assertEquals(listOf("a\n  b", "c"), LicensesActivity.paragraphs("a\n  b\n\n \r\n\nc\n"))
    }
}
