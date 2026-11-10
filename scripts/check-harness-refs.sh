#!/usr/bin/env bash
# Verify that every file path and Kotlin symbol CLAUDE.md points at still exists, so the
# harness doc can't silently drift from the code. Run from the repo root; exits 1 on drift.
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
# Backticked paths with a slash or a known extension, e.g. `android/app/build.gradle.kts`, `AppUpdater.kt`.
for ref in $(grep -oE '`[A-Za-z0-9_./-]+\.(kt|kts|yml|sh|c|md|keystore)`' CLAUDE.md | tr -d '`' | sort -u); do
  # A repo-root path, or a path suffix such as `textproc/VocabCorrector.kt`.
  [ -e "$ref" ] && continue
  found=$( (git ls-files; git ls-files --others --exclude-standard) | grep -E "(^|/)${ref//./\\.}$" | head -1)
  [ -n "$found" ] || { echo "missing: $ref"; fail=1; }
done

# Symbols the doc relies on.
for sym in enterForeground micReady HeadlessDictation lastEditable particleHit buildPrompt localizeGlossary \
           audio_ctx_floor releaseTag update_paths AUTO_STOP_CHOICES TURBO_ACFT_Q4_MODEL SMALL_ACFT_MODEL isFlickUp showModelPicker badge noteTyping bench_models StreamingWhisper rawCopy pipelinedWhisper ShizukuInstaller uiLanguage QuietSpeechDetector forDictation quietMode HallucinationFilter tailEnd NpuSupport QNN_WHISPER MEDIUM_NPU_MODEL sherpa-qnn NpuLoadGuard libcdsprpc PcLink showPcResult gateFor; do
  git grep -q "$sym" -- android .github || { echo "missing symbol: $sym"; fail=1; }
done

[ $fail -eq 0 ] && echo "harness refs OK"
exit $fail
