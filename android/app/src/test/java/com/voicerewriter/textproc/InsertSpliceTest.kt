package com.voicerewriter.textproc

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertSpliceTest {
    @Test fun emptyFieldTakesTheTextAsIs() {
        assertEquals("조용한데 잘 되네." to 10, InsertSplice.at("", 0, 0, "조용한데 잘 되네."))
    }

    @Test fun aSecondTakeIsSetOffFromTheFirst() {
        val first = "모델을 시작하고 있어."
        val (text, cursor) = InsertSplice.at(first, first.length, first.length, "조용한데 잘 되네.")
        assertEquals("모델을 시작하고 있어. 조용한데 잘 되네.", text)
        assertEquals(text.length, cursor)
    }

    @Test fun noDoubleSpaceAfterASpaceOrANewLine() {
        assertEquals("앞 문장. 뒤 문장.", InsertSplice.at("앞 문장. ", 6, 6, "뒤 문장.").first)
        assertEquals("앞 문장.\n뒤 문장.", InsertSplice.at("앞 문장.\n", 6, 6, "뒤 문장.").first)
    }

    @Test fun insideBracketsOrQuotesNoSpaceIsAdded() {
        assertEquals("(참고)", InsertSplice.at("()", 1, 1, "참고").first)
    }

    @Test fun aWordAfterTheCursorIsSetOffToo() {
        val (text, cursor) = InsertSplice.at("앞 뒤", 2, 2, "가운데")
        assertEquals("앞 가운데 뒤", text)
        assertEquals(5, cursor)
    }

    @Test fun paddedGivesJustTheTextToCommit() {
        // The input-connection path knows only the characters next to the cursor.
        assertEquals(" 뒤 문장.", InsertSplice.padded(".", "", "뒤 문장."))
        assertEquals("뒤 문장.", InsertSplice.padded("", "", "뒤 문장."))
        assertEquals("뒤 문장.", InsertSplice.padded("\n", "", "뒤 문장."))
        assertEquals(" 가운데 ", InsertSplice.padded("앞", "뒤", "가운데"))
    }

    @Test fun aSelectionIsReplaced() {
        assertEquals("이건 새 글 이야", InsertSplice.at("이건 옛글 이야", 3, 5, "새 글").first)
    }
}
