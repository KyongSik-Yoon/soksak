#!/usr/bin/env python3
"""Export a Whisper fine-tune from Hugging Face to the ONNX pair sherpa-onnx compiles for the Qualcomm NPU.

sherpa-onnx's scripts/whisper/qnn/export_onnx.py only takes OpenAI model names, and the runtime reads
the model size from the tensor names (turbo-mel, turbo-tokens, ...). So this keeps `--model <name>` and
replaces what whisper.load_model returns with the fine-tuned weights, remapped from Hugging Face's
layout. Made for FUTO's ACFT whisper-small (removed from the app 2026-11-11); any Whisper fine-tune
whose architecture matches an OpenAI size goes through the same way.

Usage: npu_acft_export.py <sherpa's export_onnx.py> <hf repo> <openai size>
  e.g. npu_acft_export.py export_onnx.py MahmoudAshraf/acft-whisper-large-v3-turbo turbo
(writes <size>-{encoder,decoder}.onnx and <size>-tokens.txt to the current directory)
"""
import re
import runpy
import sys

import torch
import whisper
from transformers import WhisperForConditionalGeneration
from whisper.model import ModelDimensions, Whisper

# Applied in order to each Hugging Face key; the result is the OpenAI key.
RENAMES = [
    (r"^model\.", ""),
    (r"\.layers\.", ".blocks."),
    (r"^encoder\.embed_positions\.weight$", "encoder.positional_embedding"),
    (r"^decoder\.embed_positions\.weight$", "decoder.positional_embedding"),
    (r"^decoder\.embed_tokens\.", "decoder.token_embedding."),
    (r"^encoder\.layer_norm\.", "encoder.ln_post."),
    (r"^decoder\.layer_norm\.", "decoder.ln."),
    (r"\.self_attn_layer_norm\.", ".attn_ln."),
    (r"\.encoder_attn_layer_norm\.", ".cross_attn_ln."),
    (r"\.self_attn\.", ".attn."),
    (r"\.encoder_attn\.", ".cross_attn."),
    (r"\.k_proj\.", ".key."),
    (r"\.q_proj\.", ".query."),
    (r"\.v_proj\.", ".value."),
    (r"\.out_proj\.", ".out."),
    (r"\.fc1\.", ".mlp.0."),
    (r"\.fc2\.", ".mlp.2."),
    (r"\.final_layer_norm\.", ".mlp_ln."),
]

# <|startoftranscript|><|ko|><|transcribe|><|notimestamps|>, by vocabulary size: large-v3 and
# turbo added a language, which moved the task tokens up by one.
CHECK_TOKENS = {51865: [50258, 50264, 50359, 50363], 51866: [50258, 50264, 50360, 50364]}


def openai_key(hf_key: str) -> str:
    for pattern, replacement in RENAMES:
        hf_key = re.sub(pattern, replacement, hf_key)
    return hf_key


def load_acft(repo: str) -> Whisper:
    hf = WhisperForConditionalGeneration.from_pretrained(repo).eval()
    c = hf.config
    assert c.vocab_size in CHECK_TOKENS, c.vocab_size  # multilingual only
    model = Whisper(
        ModelDimensions(
            n_mels=c.num_mel_bins,
            n_audio_ctx=c.max_source_positions,
            n_audio_state=c.d_model,
            n_audio_head=c.encoder_attention_heads,
            n_audio_layer=c.encoder_layers,
            n_vocab=c.vocab_size,
            n_text_ctx=c.max_target_positions,
            n_text_state=c.d_model,
            n_text_head=c.decoder_attention_heads,
            n_text_layer=c.decoder_layers,
        )
    ).eval()
    # proj_out shares the token embedding's weights; OpenAI's decoder multiplies by it directly.
    state = {openai_key(k): v for k, v in hf.state_dict().items() if k != "proj_out.weight"}
    model.load_state_dict(state, strict=True)

    # The phone is the first place the compiled model can run, so prove the remap here.
    with torch.no_grad():
        mel = torch.randn(1, c.num_mel_bins, 3000)
        tokens = torch.tensor([CHECK_TOKENS[c.vocab_size]])
        expected = hf(input_features=mel, decoder_input_ids=tokens).logits
        actual = model.decoder(tokens, model.encoder(mel))
    diff = (expected - actual).abs().max().item()
    print(f"{repo}: max logit difference from Hugging Face {diff:.2e}")
    assert diff < 1e-2, diff
    return model


def main() -> None:
    export_script, repo, size = sys.argv[1:4]
    acft = load_acft(repo)
    whisper.load_model = lambda *args, **kwargs: acft
    sys.argv = [export_script, "--model", size]
    runpy.run_path(export_script, run_name="__main__")


if __name__ == "__main__":
    main()
