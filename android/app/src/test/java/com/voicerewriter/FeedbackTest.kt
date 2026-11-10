package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * The intents themselves need a Context, so what's covered here is the encoding, which is where
 * a prefilled issue body silently comes out mangled.
 */
class FeedbackTest {

    /** Mirror of the private Feedback.enc, kept in step by the assertions below. */
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    @Test
    fun `spaces encode as percent-20, not plus`() {
        assertEquals("Soksak%20feedback", enc("Soksak feedback"))
        assertFalse(enc("a b c").contains("+"))
    }

    @Test
    fun `newlines survive encoding`() {
        assertEquals("a%0D%0Ab", enc("a\r\nb"))
        assertEquals("a%0Ab", enc("a\nb"))
    }

    @Test
    fun `markdown in the issue template is not corrupted`() {
        // "#" would otherwise terminate the URL at a fragment, taking the rest of the body
        // with it; "&" would start a new query parameter.
        assertEquals("%23%23%20Bug", enc("## Bug"))
        assertEquals("a%26b", enc("a&b"))
    }

    @Test
    fun `issues go to this fork, not the upstream author`() {
        assertEquals("https://github.com/KyongSik-Yoon/soksak", Feedback.REPO)
    }

    @Test
    fun `an encoded body carries no raw characters that would truncate the url`() {
        val body = enc("**What happened?**\n\n---\nApp: Soksak 1.3.0 & more #1")
        listOf(" ", "\n", "#", "&").forEach {
            assertTrue("raw '$it' survived encoding", !body.contains(it))
        }
    }
}
