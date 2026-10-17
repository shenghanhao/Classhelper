# Class Memory / 课堂记忆

**Language / 语言切换:** Home **⋯** → **Switch to English** in Chinese, or **切换 中文** in English. The choice is saved; the public build starts in English. UI language does not change the ASR model or translate recordings.

首页 **⋯**：中文界面选 **Switch to English**，英文界面选 **切换 中文**。选择会保存，公开版首次默认英文；切换界面语言不会更换识别模型或翻译老师的话。

## English — how to use

An experimental local Android transcription app. It saves final text, your questions/comments and explicitly captured photos, **not microphone audio files**. No account, online ASR or AI summarization is required.

1. Get permission from the institution, instructor and relevant participants **before** recording or photographing. Do not use it where recording is prohibited.
2. Install the test APK from **Releases → Assets**. Android 10+ and arm64 are required. Public app ID: `com.classmemory.app.publish`, isolated from private/development installations; their data is not migrated.
3. Tap **Start recording**, allow microphone access and watch provisional text become saved segments. Interim text can change; it is not the final transcript.
4. Add a **Question** or **Comment**, or **Capture board**. Each gets a classroom-relative timestamp. Expand the board drawer to view/zoom/reset photos or delete with confirmation.
5. Tap **End class** at the top and confirm. Open history to rename/classify a classroom, mark it organized or append another completed note. Filter the timeline by **All**, **Board photos**, or **Questions & comments**.
6. Export **Markdown** for text/annotations or **Notebook (.ipynb)** for text with embedded photos. Sharing is manual; a recipient app/cloud may upload the shared file. Check sensitive content and permissions first.
7. Debug/test APKs provide **⋯ → Import test audio**. Select authorized WAV/M4A etc.; decoding produces 16 kHz mono PCM for local ASR, without creating an app-owned audio copy. The original file is not deleted. Reports include duration, RTF, backlog and memory.

Hotwords start disabled and empty. Create your own course/library in Settings, import/export its JSON, then select it on Home. Use a small relevant vocabulary. The public build includes **no private course schedule, classroom audio/transcript or starter vocabulary**.

Experimental software: no guarantee of accuracy, real-time speed or device compatibility. It is not a substitute for recording permission or a reliable basis for critical decisions. See [licensing and responsible use](docs/LICENSING_AND_RESPONSIBLE_USE.md).

## English — build and switch local models

Open `class-memory/android/` in Android Studio. Use JDK 17+ (checked with JDK 21), Android SDK 36.1 and the checked-in Gradle wrapper. Machine configuration, keys and model weights are not published. First follow [model setup](docs/MODELS.md), then from that Android directory:

| Backend | Build command |
| --- | --- |
| X-ASR streaming, default | `./gradlew :app:assembleDebug` |
| Zipformer bilingual streaming | `./gradlew :app:assembleDebug -PasrModel=zipformer -PasrHotwordProfile=none` |
| Qwen3-ASR offline/VAD | `./gradlew :app:assembleDebug -PasrModel=qwen3 -PasrHotwordProfile=none` |

Model selection is **build-time**, not a runtime dropdown. Use matched encoder/decoder/joiner/tokenizer files. The same app architecture supports all three, but partial text, segmentation and latency differ; Qwen's offline segments do not behave like streaming partials. Validate on the target device with authorized input.

The default X-ASR build supports editable hotwords but initially uses none. The legacy `data_structure` build-profile ID is a capability flag, **not** an automatic personal course library.

Output: `class-memory/android/app/build/outputs/apk/debug/app-debug.apk`. This is a **Debug/test-signed APK**, not a Play Store release. Never commit APKs or signing keys. Keep a private release key before establishing a production update channel.

## 中文 — 如何使用

本项目是实验性的 Android 本地转写工具：保存最终文字、你添加的疑问/注释及主动拍摄的板书，不保存麦克风课堂录音文件。无需账号、在线识别或大模型总结。

1. 录音、拍摄前取得学校/机构、老师及有关参与者许可，禁止录制的场合不要使用。
2. 从 GitHub **Releases → Assets** 下载测试 APK，需要 Android 10+、arm64 手机。公开版包名 `com.classmemory.app.publish` 与自用版独立，不迁移自用数据。
3. 点击“开始记录”并允许麦克风权限；临时文字可能变化，以最终保存的内容为准。
4. 添加疑问、注释或拍摄板书，会自动附上课堂相对时间。展开板书预览可缩放、复位及二次确认删除。
5. 顶部“结束课堂”并确认，再从历史查看、改名、分类、标记整理或追加其他已结束笔记。可筛选所有、板书或疑问与注释。
6. Markdown导出文字与标注；Notebook导出时间线和内嵌图片。分享前检查隐私/版权，接收方软件或云服务可能上传文件。
7. Debug测试版的 **⋯ → 导入测试音频** 支持获授权的 WAV/M4A等输入，解码为16kHz单声道PCM后本地识别，不另存音频，不删除原文件；报告包含RTF等指标。

热词默认关闭、从空词库开始。设置中自建课程/词库并导入导出JSON，首页选择对应库。不要开启大量不相关词。公开版不附带个人课表、课堂资料或私人词库。

## 中文 — 构建与切换模型

Android Studio打开 `class-memory/android/`，准备JDK17+、Android SDK36.1，使用Gradle wrapper。先按[模型准备说明](docs/MODELS.md)放好权重，再用上表命令构建。

模型在**构建时**选择，不是在App内切换。默认X-ASR；Zipformer为双语流式；Qwen3-ASR为VAD分段离线识别，延迟/临时文字不同。更换模型前检查其许可并用获授权录音在目标手机测试。

更多：[项目结构](class-memory/ARCHITECTURE.md) · [许可与合理使用](docs/LICENSING_AND_RESPONSIBLE_USE.md) · [安全发布到GitHub](docs/PUBLISH_GUIDE.md) · [参与贡献](CONTRIBUTING.md)

## License

Original application code: [Apache-2.0](LICENSE). Third-party libraries/models retain their own licenses; see [NOTICE](NOTICE) and [details](docs/THIRD_PARTY.md). Intended-use guidance is not an extra restriction on the open-source license. No institution or model provider endorses this app.
