# Public snapshot checks / 公开快照检查

Preparation date: 2026-10-17. No repository creation, push, remote configuration, release publication, phone install or private-main merge was performed.

## Implemented

- English-first UI with persisted home-menu English/Chinese switch; application-owned labels/notifications/errors/export headings are bilingual. User text/course names/hotwords are not translated. Compact controls use constrained widths, wrapping or ellipsis.
- Separate public app ID `com.classmemory.app.publish`; no access/migration to private app data. Existing Room v6 schema/migrations are retained.
- Private instructions, old template, personal course/seed data, local/device reports and unused historical deployment are excluded from the public Git tree; local copies/private main are preserved. Public commit metadata is neutral and history is independent.
- Model weights are excluded from source Git; default X-ASR weights are packaged in the test APK. Native runtime and required licenses/notices remain available.
- Bilingual README, architecture, model setup, contribution/security and publication/permission/licensing guides are included. No automated merge/release workflow is enabled.

## Checks actually run

- Debug Kotlin source compilation passed.
- Scoped JVM tests: **50 passed, 0 failed, 0 skipped** across language text selection, Markdown/Notebook (including English output and embedded-image bytes), hotword codec/config/seed merge and UI presentation/filter/append logic.
- The first unit-test compilation found a legacy private-asset reference; it was replaced with a synthetic token/BPE intersection test, then one targeted retry passed. No clean/dependency rebuild/full-suite run was used.
- Default X-ASR Debug APK assembly passed. `cmp` confirms the delivered copy matches the build output; `apksigner` verifies its v2 test signature.
- APK package/name/arm64 architecture and permission/assets lists were inspected. No INTERNET permission or private seed/audio/classroom assets are packaged. Apache-2.0, X-ASR, sherpa and ONNX Runtime license/notices are packaged.
- Read-only staged Git privacy/size audit passed; targeted private-identity scans of tracked text, APK DEX strings and native libraries returned no matches. The final `publish` ref is additionally checked for a single independent root history.

## Artifact

`class-memory-public-xasr-0.1.0-test-20261017.apk`

- Size: 190485733 bytes.
- SHA-256: `ba4979e92a24a176a1df89c56e630697e29f7f7e5128e3357a542368148b8324`.
- Android10+, arm64-v8a, Debug/test signing; versionName0.1.0/versionCode1.

## Limits — not device or legal acceptance

No phone/emulator UI, persistence-across-restart, recording, ASR-quality or long-duration benchmark was run for this public package. Layout protections are source-level, not proof for every display/font scale. Confirm those on a test device before a public stable release.

Pattern scans are not proof that all sensitive/copyrighted material is absent. Third-party provenance/license notices were checked, not independently audited for training-data rights or every binary component. Disclaimers cannot grant recording rights or eliminate legal duties/claims.

中文：已完成源码、50项定向测试、APK构建/签名及发布树隐私检查；未安装手机、未做公开包真机采音/双语切换重启/视觉验收，也未作法律保证。当前为可供审核的实验测试版本，是否上传/发布由仓库所有者决定。
