# Local model setup / 本地模型准备

Weights are deliberately excluded from Git. Download only from the linked upstreams after checking licenses. Keep native libraries and JNI declarations compatible; this source bundles the existing arm64 runtime and its license notices. Do not execute unknown downloaded programs.

## X-ASR (default)

Official conversion archive:

[sherpa-onnx X-ASR 480 ms INT8](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2)

Extract the archive's model directory under `class-memory/android/app/.asr-models/xasr/`. Expected directory:

`sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05/`

Required: `encoder.int8.onnx`, `decoder.onnx`, `joiner.int8.onnx`, `tokens.txt`. Custom hotwords additionally require `bpe.vocab`, exported from the **same archive's** `bpe.model` (never copy a vocabulary from another model).

From `class-memory/android/`, substitute the actual matching model directory:

```sh
python3 tools/export_xasr_vocab.py \
  --model app/.asr-models/xasr/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05/bpe.model \
  --tokens app/.asr-models/xasr/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05/tokens.txt \
  --output app/.asr-models/xasr/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05/bpe.vocab
./gradlew :app:assembleDebug
```

The exporter refuses to overwrite an existing vocabulary. Review archive contents and extract only into a new dedicated model directory. With `-PasrHotwordProfile=none`, vocabulary export is not needed and custom hotwords are unavailable.

## Zipformer

[Official bilingual streaming archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2)

Place its matched files in `android/app/src/main/assets/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/` relative to `class-memory/`: `encoder-epoch-99-avg-1.int8.onnx`, `decoder-epoch-99-avg-1.onnx`, `joiner-epoch-99-avg-1.int8.onnx`, `tokens.txt`, `bpe.vocab`. Required filenames are enforced by Gradle. Build with `-PasrModel=zipformer -PasrHotwordProfile=none`.

## Qwen3-ASR

Follow the [official sherpa-onnx Qwen3-ASR model documentation](https://k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html). Use the matching 0.6B INT8 model directory `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` under `android/app/.asr-models/qwen3/`: `conv_frontend.onnx`, `encoder.int8.onnx`, `decoder.int8.onnx`, `silero_vad.onnx` and tokenizer files `vocab.json`, `merges.txt`, `tokenizer_config.json`. Build with `-PasrModel=qwen3 -PasrHotwordProfile=none`.

This release does **not** redistribute Qwen/VAD weights or assert their licenses are identical to X-ASR. Review the exact upstream model/VAD/tokenizer terms before building/distributing that variant.

## Reproducibility / 可复现说明

`app/build.gradle.kts` is authoritative for model IDs, directories and required files. It refuses missing/empty required files. Runtime supports one selected model per APK, not three concurrent microphone consumers. Git does not contain large weights; an ordinary clone needs this model-setup step. Dependencies are locked; use normal Gradle resolution on a new machine. `--offline` is only for a machine whose dependencies are already cached.

中文：模型权重不进Git。只从上游下载、核对许可后放进上述对应目录，不混用编码器/解码器/词表。默认X-ASR需同包导出的bpe.vocab才能自定义热词；首次clone须先准备模型。Qwen离线分段与流式模型行为不同，发布其变体前需另查模型、VAD和分词器许可。详细文件与构建参数以Gradle为准。
