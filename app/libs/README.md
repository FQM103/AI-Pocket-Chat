# 第三方原生库（app/libs/）

## sherpa-onnx-1.13.3.aar — 端侧语音识别 STT（P10.1d）

- **来源**：k2-fsa/sherpa-onnx 官方 release **v1.13.3**（2026-06 依赖升级期 1.13.2→1.13.3）
  - 原始 AAR：https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.3/sherpa-onnx-1.13.3.aar
- **许可**：Apache-2.0。无 GMS 依赖、国行可用、离线、免 key。
- **已裁剪**（57MB → 4.3MB）：本仓库内为官方包**精简版**，仅保留
  - `classes.jar`（Kotlin/Java API `com.k2fsa.sherpa.onnx.*`）
  - `jni/arm64-v8a/libsherpa-onnx-jni.so` + `jni/x86_64/libsherpa-onnx-jni.so`
  - 移除：`armeabi-v7a` / `x86`（对齐本项目 abiFilters）；`libsherpa-onnx-c-api.so` /
    `libsherpa-onnx-cxx-api.so`（NDK C/C++ 消费者用；Kotlin 仅 `System.loadLibrary("sherpa-onnx-jni")`，
    且该 jni 不 NEEDED 它们）；以及 **`libonnxruntime.so`**（见下）。
- **onnxruntime 复用**：`libsherpa-onnx-jni.so` 动态 `NEEDED libonnxruntime.so`。sherpa v1.13.3 自带的是
  **ORT 1.24.3**（1.13.2→1.13.3 升级核对：bundled ORT 版本未变，仍 1.24.3）；为共用一份，把项目里 `com.microsoft.onnxruntime:onnxruntime-android` 从 1.20.0
  **升到 1.24.3**（与 sherpa 对齐），并从本 AAR 删除其自带 `libonnxruntime.so`，让 sherpa 的 jni 与 bge 的
  `libonnxruntime4j_jni.so` 共用 microsoft 1.24.3 那份（sherpa jni 按 1.24 编译＝精确匹配；bge 4j_jni 按旧版
  编译但走 ORT 向后兼容）。**必须用 ≥1.24 的那份**——反过来（1.20）会让按 1.24 编译的 sherpa `GetApi()`
  取不到新 API 而崩溃。这样 APK 内只剩一份 `libonnxruntime.so`，无需 packaging pickFirst。
- **gradle 引用**：`implementation(files("libs/sherpa-onnx-1.13.3.aar"))`（见 `app/build.gradle.kts`）。
  `.gitignore` 的全局 `*.aar` 规则对本文件加了 `!app/libs/*.aar` 例外。

### 升级版本时的复刻精简步骤
```bash
curl -L -o sherpa-onnx-<v>.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v<v>/sherpa-onnx-<v>.aar
zip -d sherpa-onnx-<v>.aar \
  "jni/armeabi-v7a/*" "jni/x86/*" \
  "jni/arm64-v8a/libonnxruntime.so" "jni/arm64-v8a/libsherpa-onnx-c-api.so" "jni/arm64-v8a/libsherpa-onnx-cxx-api.so" \
  "jni/x86_64/libonnxruntime.so" "jni/x86_64/libsherpa-onnx-c-api.so" "jni/x86_64/libsherpa-onnx-cxx-api.so"
```

## STT 模型（`app/src/main/assets/models/stt/`）

- **识别模型**：`sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12`（流式 zipformer transducer，14000 小时中文）
  - https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12.tar.bz2
  - 打包（int8 组合，原长文件名已改短名）：`encoder.int8.onnx`(67M) + `decoder.onnx`(4.9M, fp32) + `joiner.int8.onnx`(1.0M) + `tokens.txt`
  - **通话流式** + **整段语音消息**（同一 `OnlineRecognizer` 跑完整 WAV）两路复用。
- **VAD**：`silero_vad.onnx`（silero 神经 VAD）
  - https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
- 模型为 k2-fsa 公开预训练模型；`androidResources { noCompress += "onnx" }` 保证运行时整块 mmap。
