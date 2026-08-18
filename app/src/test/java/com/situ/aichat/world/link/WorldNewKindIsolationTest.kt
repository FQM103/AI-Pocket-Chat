package com.situ.aichat.world.link

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.cast.WorldFirstMeetService
import com.situ.aichat.world.cast.WorldRecruitService
import com.situ.aichat.world.live.WorldEavesdropService
import com.situ.aichat.world.travel.WorldTravelService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W12 新事件 kind 隔离 T2-7（Robolectric·图纸 §7·E12）：`eavesdrop`/`referral`（及 `visit`/`recruit`）**不进** [WorldMemoryScribe]
 * 的 [WorldMemoryScribe.MEMORY_KINDS] → 零记忆派生；且新 kind ≠ 唯一触发到达通知的 `visit` kind → 通知侧零推送（结构隔离·§11）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldNewKindIsolationTest {

    private lateinit var db: AppDatabase
    private lateinit var scribe: WorldMemoryScribe

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        scribe = WorldMemoryScribe(db.worldSocialDao(), db.characterDao(), db.worldMemoryDao())
        db.characterDao().upsert(CharacterEntity(uuid = "a", name = "甲", creationDate = 0L))
        db.characterDao().upsert(CharacterEntity(uuid = "b", name = "乙", creationDate = 0L))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `新 kind 不进 MEMORY_KINDS_且非通知 visit kind`() {
        assertFalse("偷听非记忆 kind", WorldEavesdropService.EAVESDROP_KIND in WorldMemoryScribe.MEMORY_KINDS)
        assertFalse("引荐非记忆 kind", WorldRecruitService.REFERRAL_KIND in WorldMemoryScribe.MEMORY_KINDS)
        assertFalse("招募非记忆 kind", WorldRecruitService.RECRUIT_KIND in WorldMemoryScribe.MEMORY_KINDS)
        assertFalse("到达非记忆 kind", WorldTravelService.VISIT_KIND in WorldMemoryScribe.MEMORY_KINDS)
        // 通知侧：唯一触发到达通知的 world_event kind = visit（决策 33·经 depart/invite 排期）；新 kind ≠ visit → 零推送。
        assertNotEquals(WorldTravelService.VISIT_KIND, WorldEavesdropService.EAVESDROP_KIND)
        assertNotEquals(WorldTravelService.VISIT_KIND, WorldRecruitService.REFERRAL_KIND)
        // 初遇上限常量在场（防误改）。
        assertEquals(2, WorldFirstMeetService.MAX_REPLIES)
    }

    @Test
    fun `scribe 对偷听_引荐 kind 关系事件零派生`() = runBlocking {
        val pairKey = WorldIds.pairKey("a", "b")
        db.worldSocialDao().upsertEvent(relEvent("ev-eaves", pairKey, WorldEavesdropService.EAVESDROP_KIND, 100L))
        db.worldSocialDao().upsertEvent(relEvent("ev-ref", pairKey, WorldRecruitService.REFERRAL_KIND, 200L))
        assertEquals("零新记忆条", 0, scribe.scribeSince(0))
        assertTrue("记忆库空", db.worldMemoryDao().getAll().isEmpty())
    }

    private fun relEvent(uuid: String, pairKey: String, kind: String, at: Long) =
        WorldRelationshipEventEntity(uuid = uuid, pairKey = pairKey, actorId = "a", targetId = "b", kindRaw = kind, arcId = null, summary = "x", happenedAt = at, settledAt = at)
}
