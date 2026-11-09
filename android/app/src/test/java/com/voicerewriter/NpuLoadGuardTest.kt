package com.voicerewriter

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertThrows
import org.junit.Test

class NpuLoadGuardTest {
    private val dir: File = Files.createTempDirectory("npu").toFile()

    @Test fun cleanLoadsKeepWorking() {
        repeat(3) { NpuLoadGuard(dir, 66).apply { begin(); end() } }
    }

    @Test fun aLoadThatKilledTheAppBlocksTheNextOne() {
        NpuLoadGuard(dir, 66).begin() // process "dies" here: no end()
        assertThrows(IllegalStateException::class.java) { NpuLoadGuard(dir, 66).begin() }
        // Still blocked on later launches of the same build.
        assertThrows(IllegalStateException::class.java) { NpuLoadGuard(dir, 66).begin() }
    }

    @Test fun aNewBuildTriesAgain() {
        NpuLoadGuard(dir, 66).begin()
        runCatching { NpuLoadGuard(dir, 66).begin() }
        NpuLoadGuard(dir, 67).apply { begin(); end() }
    }
}
