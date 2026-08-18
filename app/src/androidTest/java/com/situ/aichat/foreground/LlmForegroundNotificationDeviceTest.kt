package com.situ.aichat.foreground

import android.app.Notification
import android.os.Build
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.situ.aichat.notification.NotificationChannels
import com.situ.aichat.R
import com.situ.aichat.story.StoryGenPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ⑤ Live Update 前台通知的仪器测试（**真机/模拟器**，对齐既有 androidTest 约定）。Robolectric 4.14 够不到 API 36
 * 的 `NotificationCompat.ProgressStyle` / `setRequestPromotedOngoing`，故在真 API 36 上端到端验证：进度态与通用态
 * 都能成功**构建 + 发布**到通知栏、且为常驻态、标题正确。这条路径就是故事生成时灵动岛进度药丸的渲染来源。
 *
 * （不断言「是否真被升格成药丸」——促发资格由系统/OEM 的 Live Updates 开关裁决，断言它会假阴；那一档留真机肉眼验。）
 */
@RunWith(AndroidJUnit4::class)
class LlmForegroundNotificationDeviceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)!!

    @Before
    fun setUp() {
        // 发通知需 POST_NOTIFICATIONS（API 33+ 运行时权限）：用仪器 UiAutomation 直接授予，免引入 GrantPermissionRule。
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        NotificationChannels.ensureCreated(context)
    }

    @Test
    fun 进度态_构建并发布为确定性进度的常驻通知() {
        val notif = LlmForegroundNotification.build(context, storyProgress())
        assertTrue("应为常驻(ongoing)", (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        nm.notify(TEST_ID, notif)
        try {
            val active = nm.activeNotifications.firstOrNull { it.id == TEST_ID }
            assertNotNull("进度通知应已发布到通知栏（API36 ProgressStyle 路径未崩）", active)
            assertEquals(
                "《测试故事》 第 3 章",
                active!!.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            )
        } finally {
            nm.cancel(TEST_ID)
        }
    }

    private fun storyProgress(overall: Double = 0.45) = ForegroundActivity.StoryProgress(
        storyId = "s1",
        overall = overall,
        genPhase = StoryGenPhase.WRITING,
        phaseLabel = "正在撰写正文…",
        shortLabel = "撰写",
        title = "测试故事",
        chapterNumber = 3,
    )

    @Test
    fun 无进度态_构建并发布为通用常驻通知() {
        val notif = LlmForegroundNotification.build(context, null)
        assertTrue("应为常驻(ongoing)", (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        nm.notify(TEST_ID_2, notif)
        try {
            assertNotNull("通用前台通知应已发布", nm.activeNotifications.firstOrNull { it.id == TEST_ID_2 })
        } finally {
            nm.cancel(TEST_ID_2)
        }
    }

    // ── API 36 专属面（Robolectric 够不到，只有真机/模拟器能验）──

    @Test
    fun API36_故事态_四段Segment与提升请求都真的进了通知() {
        assumeTrue("本例只在 API 36+ 有意义", Build.VERSION.SDK_INT >= 36)
        val notif = LlmForegroundNotification.build(context, storyProgress(overall = 0.45))
        val ex = notif.extras
        // 四段权重 15/60/17/8 必须真被系统收下（段数塌成 1 = 药丸退化成单条）。
        val segments = ex.getParcelableArrayList("android.progressSegments", android.os.Parcelable::class.java)
        assertNotNull("ProgressStyle 的 Segment 列表应存在", segments)
        assertEquals("必须是四段", 4, segments!!.size)
        assertEquals("短文案位应是二字阶段词、且绝不是百分比", "撰写", ex.getCharSequence("android.shortCriticalText")?.toString())
        nm.notify(TEST_ID, notif)
        try {
            assertNotNull("API36 富渲染路径不得崩", nm.activeNotifications.firstOrNull { it.id == TEST_ID })
        } finally {
            nm.cancel(TEST_ID)
        }
    }

    @Test
    fun API36_typing态_不确定进度且发得出去() {
        assumeTrue("本例只在 API 36+ 有意义", Build.VERSION.SDK_INT >= 36)
        val notif = LlmForegroundNotification.build(
            context,
            ForegroundActivity.Typing("小夏", avatarPath = null, conversationUuid = "c1"),
        )
        assertTrue("typing 必须是不确定进度", notif.extras.getBoolean("android.progressIndeterminate"))
        nm.notify(TEST_ID_2, notif)
        try {
            assertNotNull(nm.activeNotifications.firstOrNull { it.id == TEST_ID_2 })
        } finally {
            nm.cancel(TEST_ID_2)
        }
    }

    @Test
    fun 三态小图标都是单色剪影_不是彩色启动图标() {
        // 现状病根：mipmap/ic_launcher 是无 alpha 的 adaptive 图，系统按 alpha 取形 → 状态栏一坨白块。
        listOf(
            null to R.drawable.ic_notif_story,
            storyProgress() to R.drawable.ic_notif_story,
            ForegroundActivity.Typing("小夏", null, "c1") to R.drawable.ic_notif_typing,
        ).forEach { (activity, expected) ->
            assertEquals("$activity 的小图标须为剪影", expected, LlmForegroundNotification.build(context, activity).smallIcon.resId)
        }
    }

    private companion object {
        const val TEST_ID = 0x71357
        const val TEST_ID_2 = 0x71358
    }
}
