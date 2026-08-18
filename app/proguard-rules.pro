# ===== P1-30 R8 keep 规则（只含实证必要项）=====
# 勘察结论（2026-06-11，逐个打开 gradle 缓存内 AAR/jar 验证）：
# 以下栈全部自带 consumer rules，随依赖自动生效，绝不在此重复手写（版本随 catalog 升级，
# 此处仅举证当前栈，规则本身与版本无关）：
#   Hilt 2.60 / Room 2.8.4 / kotlinx-serialization 1.11.0（含 R8 full-mode 专用规则）/
#   Glance 1.1.1 / WorkManager 2.11.2 / media3 1.10.1 / CameraX 1.6.1 / okhttp 5.4.0 /
#   coroutines 1.11.0 / tink-android（security-crypto 传递依赖）。
# ZXing core 3.5.4 = 纯 Java 零反射，无需规则。
# 工程自身代码零字符串反射、零 getIdentifier（已 grep 实证），无需 app 侧 keep。

# --- ONNX Runtime（com.microsoft.onnxruntime:onnxruntime-android:1.24.3）---
# AAR 内完全没有 proguard.txt、classes.jar 也无内嵌 META-INF 规则（实证）。
# libonnxruntime4j_jni.so 经 JNI FindClass/GetMethodID 按名回构 Java 类
# （strings 实证 .so 内硬编码 ai/onnxruntime/{OnnxTensor,TensorInfo,OrtException,
#   OnnxValue,OnnxMap,OnnxSequence,OnnxSparseTensor,MapInfo,NodeInfo,SequenceInfo,
#   OnnxModelMetadata} 共 11 个类名字符串）。类名/构造器/字段名必须原样保留，
# 否则向量记忆(bge)初始化 OrtEnvironment 即崩。官方亦推荐整包 keep。
-keep class ai.onnxruntime.** { *; }

# --- sherpa-onnx 1.13.3（本地 app/libs/sherpa-onnx-1.13.3.aar）---
# AAR 的 proguard.txt 存在但为 0 字节空文件＝无 consumer rules（实证）。
# libsherpa-onnx-jni.so 经 JNI 按名回构结果类（strings 实证：OnlineRecognizerResult/
#   OfflineRecognizerResult/SpeechSegment/WaveData 等），并经 GetFieldID 按名读 *Config
# 类字段（工程实际走 OnlineRecognizer/OnlineRecognizerConfig 等导入类）。
# 整包仅 com.k2fsa.sherpa.onnx 一个包、classes.jar 约 233KB，整包 keep 最安全。
-keep class com.k2fsa.sherpa.onnx.** { *; }
