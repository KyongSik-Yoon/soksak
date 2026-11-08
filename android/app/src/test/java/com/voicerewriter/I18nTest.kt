package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Test

class I18nTest {
    /** A Korean template must take exactly the arguments its English key does. */
    @Test fun placeholdersMatch() {
        for ((en, ko) in I18n.KO) {
            assertEquals("placeholders in '$en'", en.split("%s").size, ko.split("%s").size)
        }
    }

    @Test fun modeSwitchesLanguage() {
        val before = I18n.mode
        try {
            I18n.mode = "ko"; assertEquals("설정", tr("Settings"))
            assertEquals("빌드 7 업데이트가 있어요.", trf("Build %s is available.", 7))
            I18n.mode = "en"; assertEquals("Settings", tr("Settings"))
            assertEquals("Not in the table", tr("Not in the table"))
        } finally { I18n.mode = before }
    }
}
