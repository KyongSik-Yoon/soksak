#!/usr/bin/env bash
# Runs inside ghcr.io/k2-fsa/qnn-sdk:2.40 with the work directory mounted at /w. Compiles
# /w/onnx/{encoder,decoder}.onnx into QNN context binaries for the chip in $SOC (SM8850, SM8750,
# ... as scripts/qnn/device_info.py of sherpa-onnx names them; default SM8850) and packs
# $NAME/{encoder.bin,decoder.bin,tokens.txt} into $NAME.tar.bz2. Follows sherpa-onnx's
# export-whisper-qnn.yaml, minus the aarch64 libraries (the phone loads only the binaries).
# CONVERT_ARGS adds converter options: a large encoder needs "--float_bitwidth 16", because its
# float32 weights make a model library over 2GB, which x86-64 cannot link (relocation truncated).
# The NPU runs float16 either way.
set -ex
cd /w
pushd "$QNN_SDK_ROOT/bin" && source envsetup.sh && popd
for f in generate_config.py device_info.py; do
  curl -fsSL --retry 5 -o "$f" "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/$SHERPA/scripts/qnn/$f"
done
rm -rf non_quant model_libs out "$NAME"
mkdir -p non_quant out
for m in encoder decoder; do
  qnn-onnx-converter \
    --input_network "onnx/$m.onnx" \
    --output_path "non_quant/$m.cpp" \
    --act_bitwidth 16 \
    --bias_bitwidth 32 ${CONVERT_ARGS:-} > "convert-$m.txt" 2>&1 || { tail -60 "convert-$m.txt"; exit 1; }
  python3 generate_config.py --soc "${SOC:-SM8850}" --graph-name "$m" --output-dir "cfg-$m" --qnn-sdk-root "$QNN_SDK_ROOT"
  python3 "$QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-model-lib-generator" \
    -c "non_quant/$m.cpp" \
    -b "non_quant/$m.bin" \
    -o model_libs \
    -t x86_64-linux-clang > "lib-$m.txt" 2>&1 || { tail -60 "lib-$m.txt"; exit 1; }
  "$QNN_SDK_ROOT/bin/x86_64-linux-clang/qnn-context-binary-generator" \
    --backend "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so" \
    --model "model_libs/x86_64-linux-clang/lib$m.so" \
    --output_dir out \
    --binary_file "$m" \
    --config_file "cfg-$m/htp_backend_extensions.json" > "gen-$m.txt" 2>&1 || { tail -60 "gen-$m.txt"; exit 1; }
  tail -3 "gen-$m.txt"
  ls -la out
done
mkdir "$NAME"
mv out/encoder.bin out/decoder.bin "$NAME/"
cp onnx/tokens.txt "$NAME/"
tar cjf "$NAME.tar.bz2" "$NAME"
ls -la "$NAME" "$NAME.tar.bz2"
