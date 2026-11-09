// Vendored from k2-fsa/sherpa-onnx@347ec76 sherpa-onnx/kotlin-api/QnnConfig.kt (Apache-2.0).
// Must match the libsherpa-onnx-jni.so the CI sherpa-qnn job builds from the same commit.
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
    var htpPerfMode: Boolean = false,
)
