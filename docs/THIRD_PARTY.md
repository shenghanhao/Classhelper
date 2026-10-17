# Third-party components / 第三方清单

Application license does not replace component licenses. Preserve upstream copyright headers and license texts when redistributing source/APKs. Bundled texts are in `class-memory/android/app/src/main/assets/licenses/` and are included in the APK.

| Component | Stated license / evidence |
| --- | --- |
| sherpa-onnx Kotlin JNI declarations and native JNI runtime | Apache-2.0, [v1.13.7](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.7); declarations retain their upstream headers. |
| ONNX Runtime native runtime | MIT, runtime string identifies 1.27.1; [license](https://github.com/microsoft/onnxruntime/blob/v1.27.1/LICENSE), [third-party notices](https://github.com/microsoft/onnxruntime/blob/v1.27.1/ThirdPartyNotices.txt) both bundled. Its included components may use additional licenses. |
| Default X-ASR model | Original [X-ASR-zh-en model card](https://huggingface.co/GilgameshWind/X-ASR-zh-en) declares Apache-2.0. [Conversion archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2) README identifies the [original project](https://github.com/Gilgamesh-J/X-ASR). |
| Optional Zipformer bilingual model | [Original model card](https://huggingface.co/csukuangfj/k2fsa-zipformer-chinese-english-mixed) declares Apache-2.0. See bundled sherpa notice for source and hashes. |
| Optional Qwen3-ASR/VAD/tokenizer | Not redistributed in this default APK; check the exact artifact's separate license before distributing a variant. |
| AndroidX/Compose/Room | Apache-2.0; versions pinned in `app/gradle.lockfile`, [AndroidX overview](https://source.android.com/docs/setup/about/licenses). |
| Kotlin and kotlinx.coroutines | Apache-2.0, [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt), [coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt). |
| OkHttp / Okio | Apache-2.0, [OkHttp](https://github.com/square/okhttp/blob/master/LICENSE.txt), [Okio](https://github.com/square/okio/blob/master/LICENSE.txt). Legacy network adapter is not activated by the public UI. |
| Gradle wrapper | Apache-2.0, [Gradle](https://github.com/gradle/gradle/blob/master/LICENSE). |
| org.json test utility | Its own [JSON license](https://github.com/stleary/JSON-java/blob/master/LICENSE); JVM tests only, not an additional bundled Android runtime dependency. |

The Compose UI icons are simple geometric artwork in this source; no Morphicons asset/runtime is bundled. No GPL AulaLogger code is intentionally included. This statement describes implementation provenance, not a legal guarantee of non-infringement or an audit of every upstream training source.

For release, retain license/NOTICE assets in the APK, do not strip upstream headers, and review dependency changes. This register covers the chosen distribution; changing native builds/models may require new source offers/notices and license review. Do not assume every artifact published under a project shares the same license.

中文：原创App许可不覆盖第三方。保留上游版权、完整许可、NOTICE；模型许可与运行库许可分别核对。当前默认APK使用X-ASR；更换Qwen/VAD或原生库后要重新核验，不可笼统声称所有模型都是同一许可。许可元数据只是上游的声明，不构成对训练数据权利的独立保证。
