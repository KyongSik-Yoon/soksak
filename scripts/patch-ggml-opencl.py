#!/usr/bin/env python3
"""Initialise ggml-opencl's device context before reading its alignment / max buffer size.

ggml-opencl fills dev_ctx->backend_ctx lazily, on the first backend init or buffer allocation.
whisper.cpp asks the OpenCL buffer type for its alignment while laying out the model weights,
before either has happened, and the read through the still-null backend_ctx crashed the app
(SIGSEGV in ggml_backend_opencl_buffer_type_get_alignment, from whisper_model_load). ggml_cl_init
is idempotent and is exactly what alloc_buffer already calls first.
"""
import sys

path = sys.argv[1]
src = open(path).read()
if "openwispr: see scripts/patch-ggml-opencl.py" in src:
    print("already patched"); sys.exit(0)
pairs = [
    ("""static size_t ggml_backend_opencl_buffer_type_get_alignment(ggml_backend_buffer_type_t buffer_type) {
    ggml_backend_opencl_device_context * dev_ctx = (ggml_backend_opencl_device_context *) buffer_type->device->context;
    return dev_ctx->backend_ctx->alignment;
}""",
     """static size_t ggml_backend_opencl_buffer_type_get_alignment(ggml_backend_buffer_type_t buffer_type) {
    // openwispr: see scripts/patch-ggml-opencl.py
    return ggml_cl_init(buffer_type->device)->alignment;
}"""),
    ("""        ggml_backend_opencl_device_context * dev_ctx = (ggml_backend_opencl_device_context *) buffer_type->device->context;
        max_size = dev_ctx->backend_ctx->max_alloc_size;""",
     """        max_size = ggml_cl_init(buffer_type->device)->max_alloc_size;"""),
]
for old, new in pairs:
    assert src.count(old) == 1, "ggml-opencl.cpp changed; update the patch"
    src = src.replace(old, new)
open(path, "w").write(src)
print("patched", path)
