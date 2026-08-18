package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.prompt.PromptStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 发帖 reqContent 第三人称指名（图纸一·B5·T2-B5）。用**真实 zh-rCN 资源**（非哨兵）跑 [MomentPostPromptBuilder.build]，
 * 断言 `moment_post_req_content` 的 `%s` 被真实用户名替换：reqContent 段含 `和小明聊天`、不含旧 `和用户聊天`。
 * 覆盖真路径（资源 `%s` + `.format(userName)` 组合），补足哨兵测试（[MomentPostPromptBuilderTest]）够不着的实串。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class MomentPostReqContentNamingTest {

    @Test
    fun reqContent_usesRealUserName_notBareUser() {
        val strings = MomentPromptStrings.from(PromptStrings(RuntimeEnvironment.getApplication()))
        val out = MomentPostPromptBuilder.build(
            strings = strings,
            character = CharacterEntity(uuid = "c1", name = "小樱", creationDate = 0L, personalityDescription = "活泼"),
            hotInterestNames = emptyList(),
            personalityTraits = emptyList(),
            recentUserPosts = emptyList(),
            recentOwnContents = "",
            nowContext = "",
            schedulePrompt = "",
            userName = "小明",
        )
        assertTrue("reqContent 段用真实用户名", out.contains("和小明聊天"))
        assertFalse("不再是裸「和用户聊天」", out.contains("和用户聊天"))
    }
}
