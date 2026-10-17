# Architecture / 项目结构

Open `android/` in Android Studio. Source of truth: Gradle files, Kotlin types, Room migrations and tests. Private test histories and legacy online deployment are not part of this public distribution.

Paths under `android/app/src/main/java/com/classmemory/app/`:

| Path | Responsibility / 职责 |
| --- | --- |
| `MainActivity.kt`, `ui/` | Navigation, permissions, pages, photos / 导航、权限、页面 |
| `i18n/` | Observable persisted English/Chinese preference / 界面语言 |
| `service/`, `audio/` | AudioRecord foreground lifecycle; bounded PCM / 有界内存采音 |
| `asr/` | Local model adapters and custom hotwords / 模型与热词 |
| `data/` | Room entities and v1–v6 migrations / 数据与迁移 |
| `repository/` | Persistence, append, safe photo deletion / 持久化与文件 |
| `export/` | Markdown and embedded-image Notebook / 导出 |

`src/debug/.../benchmark/` contains file import/decoding/metrics. `src/test/` contains synthetic-data JVM tests. `src/main/java/com/k2fsa/sherpa/onnx/` contains upstream JNI declarations; preserve copyright notices. Native libraries live in `src/main/jniLibs/arm64-v8a/`.

AudioRecord → bounded in-memory PCM → selected local ASR → timestamped final segments → Room. Imported test input uses the same recognizer. Partial text is display-only and may change. Microphone audio is not written to files.

Photos are explicitly captured to app-private storage. Delete only validated IDs/paths, commit the database change before cleaning an unreferenced file. Photos, notes and text share classroom-relative time. Notebook images are embedded, not remote links.

`tr(chinese, english)` translates application-owned labels; user transcripts/course names/hotwords remain untouched. Switching language does not restart recording. Model selection uses Gradle properties, independent of UI language.

中文：音频只在有界内存和本地识别器流转，Room保存最终文字；照片由用户主动拍摄。文字、标注、板书按课堂相对时间排列。修改识别、迁移或文件操作应做针对性测试；JVM测试不能替代真机验收。
