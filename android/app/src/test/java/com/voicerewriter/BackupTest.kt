package com.voicerewriter

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupTest {
    private val vocab = """[{"canonical":"Claude","aliases":["클로드"],"source":"manual"},""" +
        """{"canonical":"worktree","aliases":["워크트리"],"source":"learned","learnedAliases":["워크트리"]}]"""

    private val mine = Settings(
        sttModel = "medium-npu", quietMode = true, autoStopSeconds = 3.0, promptExample = "예시 문장",
        uiLanguage = "ko", githubToken = "github_pat_SECRET", sttKey = "sk-SECRET",
        pcSend = PcLink.BOTH, pcHost = "100.68.1.2", pcType = true, pcCode = "SECRETCODE",
    )

    @Test fun `round trip keeps settings and the dictionary`() {
        val text = Backup.encode(mine, vocab)
        val r = Backup.decode(text, Settings())
        assertEquals("medium-npu", r.settings.sttModel)
        assertEquals(true, r.settings.quietMode)
        assertEquals(3.0, r.settings.autoStopSeconds, 0.0)
        assertEquals("예시 문장", r.settings.promptExample)
        assertEquals("ko", r.settings.uiLanguage)
        assertEquals(PcLink.BOTH, r.settings.pcSend)
        assertEquals("100.68.1.2", r.settings.pcHost)
        assertEquals(true, r.settings.pcType)
        val arr = JSONArray(r.vocabJson)
        assertEquals(2, arr.length())
        assertEquals("워크트리", arr.getJSONObject(1).getJSONArray("learnedAliases").getString(0))
    }

    @Test fun `secrets never reach the file and the restoring install keeps its own`() {
        val text = Backup.encode(mine, vocab)
        assertFalse(text.contains("SECRET"))
        val here = Settings(githubToken = "github_pat_HERE", sttKey = "sk-HERE", pcCode = "HERECODE23")
        val r = Backup.decode(text, here)
        assertEquals("github_pat_HERE", r.settings.githubToken)
        assertEquals("sk-HERE", r.settings.sttKey)
        assertEquals("HERECODE23", r.settings.pcCode)
    }

    @Test fun `no dictionary yet restores none`() {
        assertNull(Backup.decode(Backup.encode(mine, null), Settings()).vocabJson)
    }

    @Test fun `other files are refused`() {
        assertThrows(IllegalArgumentException::class.java) { Backup.decode("[1,2]", Settings()) }
        assertThrows(IllegalArgumentException::class.java) { Backup.decode("""{"format":"other"}""", Settings()) }
        assertThrows(IllegalArgumentException::class.java) {
            Backup.decode("""{"format":"soksak-backup","version":99}""", Settings())
        }
    }
}
