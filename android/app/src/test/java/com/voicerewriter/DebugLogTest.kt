package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

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

    private fun at(year: Int, month: Int, day: Int, hour: Int) =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, 0, 0) }.timeInMillis

    private fun stamped(time: String) = "$time.000  7286 19286 F DEBUG   : backtrace:"

    @Test fun `lines older than a day are dropped`() {
        val old = stamped("11-08 14:18:10")
        val recent = stamped("11-09 06:00:00")
        assertEquals(listOf(recent), DebugLog.relevant(listOf(old, recent), nowMs = at(2026, 11, 10, 5)))
    }

    @Test fun `the day before New Year still counts as recent`() {
        val old = stamped("12-30 23:00:00")
        val recent = stamped("12-31 23:00:00")
        val today = stamped("01-01 02:00:00")
        assertEquals(listOf(recent, today), DebugLog.relevant(listOf(old, recent, today), nowMs = at(2027, 1, 1, 3)))
    }

    @Test fun `only focus changes into text fields stay`() {
        val outside = line("D", "OpenWisprA11y", "fieldFocus editable=false ime=false host=com.anthropic.claude")
        val field = line("D", "OpenWisprA11y", "fieldFocus editable=true ime=true host=com.anthropic.claude")
        assertEquals(listOf(field), DebugLog.relevant(listOf(outside, field)))
    }

    @Test fun `the tensor listing goes and the NPU timings stay`() {
        val src = "/home/runner/work/openWispr/openWispr/s/sherpa-onnx/csrc"
        val timing = listOf(
            line("W", "sherpa-onnx", "$src/qnn/offline-whisper-model-qnn.cc:Run:254"),
            line("W", "sherpa-onnx", "QNN timing us: encoder 370776 transpose 100011 | steps 38"),
            line("W", "sherpa-onnx", "QNN profile decoder: QNN (execute) time#400/u1 = 58530 (x1)"),
        )
        val dump = listOf(
            line("W", "sherpa-onnx", "$src/qnn/qnn-model.cc:InitInputTensors:634"),
            line("W", "sherpa-onnx", "input 49"),
            line("W", "sherpa-onnx", "$src/qnn/utils.cc:PrintTensor:493"),
            line("W", "sherpa-onnx", "  id: 49"),
            line("W", "sherpa-onnx", "    encodingDefinition: 0x7fffffff"),
            line("W", "sherpa-onnx", " memType raw data size: 1835008"),
            line("W", "sherpa-onnx", "$src/qnn/qnn-model.cc:InitOutputTensors:655"),
            line("W", "sherpa-onnx", "output 47"),
        )
        assertEquals(timing, DebugLog.relevant(dump + timing))
    }

    @Test fun `NPU diagnostics are off unless asked for`() {
        assertEquals(false, Settings().npuDiagnostics)
    }

    @Test fun `only the newest lines are kept`() {
        val lines = (1..5).map { line("I", "StreamingWhisper", "piece $it") }
        assertEquals(lines.takeLast(2), DebugLog.relevant(lines, max = 2))
    }
}
