package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugLogTest {
    private fun line(level: String, tag: String, msg: String) = "11-09 23:51:02.642  7286 19286 $level $tag: $msg"

    @Test fun `drawing chatter goes and the app's own lines stay`() {
        val ours = line("I", "LocalSherpaStt", "medium-npu: 30.8s, decode [4276ms/53tok]")
        val out = DebugLog.relevant(
            listOf(
                line("I", "VRI[CompareActivity]@905736d", "ViewPostIme pointer 0"),
                line("I", "View    ", "setRequestedFrameRate frameRate=-4.0"),
                line("D", "RefBase ", "#00 pc 0000000000086e08"),
                ours,
            ),
        )
        assertEquals(listOf(ours), out)
    }

    @Test fun `FastRPC keeps only its errors`() {
        val routine = line("I", "com.voicerewriter.dev", "vendor/qcom/proprietary/adsprpc/src/rpcmem_android.c:650: done")
        val error = line("E", "com.voicerewriter.dev", "vendor/qcom/proprietary/adsprpc/src/fastrpc_apps_user.c:6242: Error 0xd")
        assertEquals(listOf(error), DebugLog.relevant(listOf(routine, error)))
    }

    @Test fun `crash lines and unparsable lines are never dropped`() {
        val lines = listOf(
            line("F", "libc", "Fatal signal 11 (SIGSEGV)"),
            line("E", "AndroidRuntime", "FATAL EXCEPTION: main"),
            "--------- beginning of crash",
        )
        assertEquals(lines, DebugLog.relevant(lines))
    }

    @Test fun `NPU diagnostics are off unless asked for`() {
        assertEquals(false, Settings().npuDiagnostics)
    }

    @Test fun `only the newest lines are kept`() {
        val lines = (1..5).map { line("I", "StreamingWhisper", "piece $it") }
        assertEquals(lines.takeLast(2), DebugLog.relevant(lines, max = 2))
    }
}
