// Vendored from k2-fsa/sherpa-onnx@347ec76 sherpa-onnx/kotlin-api/FeatureConfig.kt (Apache-2.0).
// Must match the libsherpa-onnx-jni.so the CI sherpa-qnn job builds from the same commit.
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
