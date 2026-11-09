// Vendored from k2-fsa/sherpa-onnx@347ec76 sherpa-onnx/kotlin-api/HomophoneReplacerConfig.kt (Apache-2.0).
// Must match the libsherpa-onnx-jni.so the CI sherpa-qnn job builds from the same commit.
package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
