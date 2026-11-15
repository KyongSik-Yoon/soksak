#!/usr/bin/env python3
"""VoxLock v2 chunk 모델을 고정 청크(K=25) QNN HTP 호환 ONNX로 변환한다.

원본 `arbazsiddiqui/voxlock`의 `chunk.onnx`(가변 wav `[1, 'S']`)를
200ms 스트리밍 청크(`K=25`, `S=3584`)로 정적화하고 `onnxsim`으로 `Shape` 노드를 제거한 뒤,
QAIRT 2.40 `qnn-onnx-converter`가 요구하는 두 가지 그래프 패치를 적용한다:
1. 초기 상태가 없는 양방향 LSTM(`band_comm`) 6개에 zero tensor(`band_comm_zero_h/c`)를 연결해
   `rnn_translations.py`의 전역 `OPTIONAL_INPUTS` `IndexError`를 회피한다.
2. 단측 하한 `Clip(x, min, "")` 노드를 `Max(x, min)`으로 치환한다.
"""

import argparse
import os
import tempfile

import numpy as np
import onnx
from onnx import helper, numpy_helper
import onnxruntime as ort


def export_static_qnn(src_path: str, dst_path: str, enroll_path: str | None = None, k_frames: int = 25) -> None:
    try:
        from onnxsim import simplify
    except ImportError as exc:
        raise RuntimeError("onnxsim is required: pip install onnxsim") from exc

    s_len = (k_frames - 1) * 128 + 512
    model = onnx.load(src_path)
    for inp in model.graph.input:
        if inp.name == "wav":
            inp.type.tensor_type.shape.dim[0].dim_value = 1
            inp.type.tensor_type.shape.dim[1].dim_value = s_len
    for out in model.graph.output:
        if out.name == "frames":
            out.type.tensor_type.shape.dim[0].dim_value = 1
            out.type.tensor_type.shape.dim[1].dim_value = k_frames
            out.type.tensor_type.shape.dim[2].dim_value = 512

    simplified, ok = simplify(
        model,
        overwrite_input_shapes={
            "wav": [1, s_len],
            "enroll_n": [257, 376],
            "k": [32, 2, 64, 298],
            "v": [32, 2, 298, 64],
            "h": [6, 32, 256],
            "c": [6, 32, 256],
        },
    )
    if not ok:
        raise RuntimeError(f"onnxsim simplification failed for K={k_frames}")

    zeros = np.zeros((2, k_frames, 256), dtype=np.float32)
    zero_h_name = "band_comm_zero_h"
    zero_c_name = "band_comm_zero_c"
    simplified.graph.initializer.append(numpy_helper.from_array(zeros, zero_h_name))
    simplified.graph.initializer.append(numpy_helper.from_array(zeros, zero_c_name))

    patched_nodes = []
    for node in simplified.graph.node:
        if node.op_type == "Clip":
            if len(node.input) >= 2 and (len(node.input) == 2 or node.input[2] == ""):
                patched_nodes.append(
                    helper.make_node(
                        "Max",
                        inputs=[node.input[0], node.input[1]],
                        outputs=list(node.output),
                        name=node.name + "_max",
                    )
                )
                continue
        if node.op_type == "LSTM" and "band_comm" in node.name:
            while len(node.input) < 4:
                node.input.append("")
            if len(node.input) == 4:
                node.input.extend(["", zero_h_name, zero_c_name])
        patched_nodes.append(node)

    del simplified.graph.node[:]
    simplified.graph.node.extend(patched_nodes)
    onnx.checker.check_model(simplified)

    os.makedirs(os.path.dirname(os.path.abspath(dst_path)), exist_ok=True)
    onnx.save(simplified, dst_path)

    # 원본 동적 모델과 정적 패치 모델 간 수치 동등성 검증
    rng = np.random.default_rng(42)
    if enroll_path and os.path.isfile(enroll_path):
        enroll_sess = ort.InferenceSession(enroll_path, providers=["CPUExecutionProvider"])
        enroll_wav = rng.normal(0, 0.1, (1, 48000)).astype(np.float32)
        enroll_n, k_t, v_t = enroll_sess.run(None, {"enroll": enroll_wav})
    else:
        enroll_n = rng.normal(0, 0.2, (257, 376)).astype(np.float32)
        k_t = rng.normal(0, 0.2, (32, 2, 64, 298)).astype(np.float32)
        v_t = rng.normal(0, 0.2, (32, 2, 298, 64)).astype(np.float32)

    feeds = {
        "wav": rng.normal(0, 0.1, (1, s_len)).astype(np.float32),
        "enroll_n": enroll_n,
        "k": k_t,
        "v": v_t,
        "h": rng.normal(0, 0.05, (6, 32, 256)).astype(np.float32),
        "c": rng.normal(0, 0.05, (6, 32, 256)).astype(np.float32),
    }
    ref_out = ort.InferenceSession(src_path, providers=["CPUExecutionProvider"]).run(None, feeds)
    new_out = ort.InferenceSession(dst_path, providers=["CPUExecutionProvider"]).run(None, feeds)
    diffs = [float(np.max(np.abs(a - b))) for a, b in zip(ref_out, new_out)]
    print(f"Exported {dst_path} (K={k_frames}, S={s_len}): max_abs diffs = {diffs}")
    if max(diffs) >= 1e-5:
        raise AssertionError(f"Numerical mismatch after export: {diffs}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Export static K=25 VoxLock ONNX for QAIRT QNN compilation")
    parser.add_argument("--src", required=True, help="Source dynamic chunk.onnx path")
    parser.add_argument("--dst", required=True, help="Destination static patched ONNX path")
    parser.add_argument("--enroll", default="", help="Optional enroll.onnx path for realistic tensor verification")
    parser.add_argument("--k-frames", type=int, default=25, help="Fixed chunk frame count (default: 25)")
    args = parser.parse_args()
    export_static_qnn(args.src, args.dst, args.enroll or None, args.k_frames)


if __name__ == "__main__":
    main()
