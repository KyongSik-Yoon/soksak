#!/usr/bin/env python3
"""Keep quantized weights whose block count isn't a multiple of 64 off the OpenCL GPU.

ggml-opencl converts each quantized weight on upload with a kernel launched over one work item
per block in work groups of 64, with no bounds check. LLM shapes always divide evenly; Whisper's
token embedding (n_vocab = 51866 rows) does not, and the upload crashed the app natively while
loading Large v3 Turbo (last log line: whisper_model_load ... n_langs = 100). Such a weight stays
in a CPU buffer instead; ggml runs the ops that use it on the CPU.
"""
import sys

path = sys.argv[1]
src = open(path).read()
anchor = """static bool weight_buft_supported(const whisper_hparams & hparams, ggml_tensor * w, ggml_op op, ggml_backend_buffer_type_t buft, ggml_backend_dev_t dev) {
    bool op_supported = true;
"""
guard = anchor + """
    // openwispr: see scripts/patch-whisper-gpu.py
    if ((ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU || ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_IGPU) &&
        ggml_is_quantized(w->type) && (ggml_nelements(w) / ggml_blck_size(w->type)) % 64 != 0) {
        return false;
    }
"""
if "openwispr: see scripts/patch-whisper-gpu.py" in src:
    print("already patched"); sys.exit(0)
assert src.count(anchor) == 1, "whisper.cpp changed; update the patch"
open(path, "w").write(src.replace(anchor, guard))
print("patched", path)
