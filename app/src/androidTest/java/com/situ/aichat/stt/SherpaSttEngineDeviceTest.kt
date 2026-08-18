package com.situ.aichat.stt

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device STT smoke test — deliverable 10.1d⑥ (verify Mandarin recognition). Pure JVM unit tests can't
 * exercise the native recognizer, so run this on an emulator/device:
 *
 *   ./gradlew :app:connectedDebugAndroidTest --tests "com.situ.aichat.stt.SherpaSttEngineDeviceTest"
 *
 * It proves the bundled model + `libsherpa-onnx-jni.so` + the shared `libonnxruntime.so` all load, and that
 * a real Mandarin clip is recognized into non-blank Chinese text. The clip is the
 * `multi-zh-hans-2023-12-12` dev sample DEV_T0000000002 (16 kHz mono); its exact transcript isn't bundled,
 * so the hard assertion is "non-blank, contains CJK" — eyeball the logged text (tag SttDeviceTest) for
 * actual accuracy, and bump the model tier only if it's poor.
 */
@RunWith(AndroidJUnit4::class)
class SherpaSttEngineDeviceTest {

    @Test
    fun loadsModelAndRecognizesMandarin() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = SherpaSttEngine(context)

        assertTrue("STT engine should load the bundled model from assets", engine.isAvailable)

        // 测试夹具 WAV 在 androidTest assets（test APK）→ 必须经 instrumentation context 读取；
        // appContext（被测 app）只见 app APK 的 assets（STT 模型在 app assets，引擎用 appContext 正确）。
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val samples = testAssets.open("stt_test/zh_dev_sample.wav").use {
            decodeWavPcm16ToFloat(it.readBytes())
        }
        assertNotNull("bundled test clip should decode", samples)

        val text = engine.transcribe(samples!!)
        Log.i("SttDeviceTest", "recognized: $text")

        assertNotNull("transcribe should return text when the engine is available", text)
        assertTrue(
            "expected a non-blank Chinese transcript, got: $text",
            text!!.any { it.code in 0x4E00..0x9FFF },
        )
    }
}
