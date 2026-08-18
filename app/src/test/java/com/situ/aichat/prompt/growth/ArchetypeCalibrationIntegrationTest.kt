package com.situ.aichat.prompt.growth

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.maintenance.MaintenanceThrottleStore
import com.situ.aichat.prompt.buildArchetypeRelationshipDescription
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * T4-A 等效自动化（图纸 §7·§4.15 确定性替代）：真 Room（Robolectric）+ 真 Calibrator + 真 Lexicon 走
 * **create「恋人」不动滑块 → 校准写 archetypeId + 抬地板 → 二维渲染恋人族 L1 无「生疏」** 的端到端链，
 * 补 T2-1（MockK DAO）与 T1-3（纯渲染）之间「真 DB 写入 + 仓库→校准器接线」的覆盖缺口。
 * 上下文日志页视觉 / 资料页雷达图形状 = 真机批（功能已此处证）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchetypeCalibrationIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CharacterRepository

    @Before fun setup() {
        val ctx = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val lexicon = RelationshipLexicon.fromRawText(File("src/main/assets/growth/relationship_lexicon.tsv").readText())
        val calibrator = RelationshipArchetypeCalibrator(
            db.characterDao(), CharacterWriteLock(), lexicon, MaintenanceThrottleStore(ctx), db.milestoneDao(),
            apkLastUpdateTime = { 0L }, // 集成测试不走启动扫快路
        )
        repo = CharacterRepository(db.characterDao(), db.milestoneDao(), db, mockk(relaxed = true), calibrator)
    }

    @After fun tearDown() = db.close()

    @Test fun `新建恋人不动滑块_校准落地并渲染恋人档`() = runBlocking {
        val uuid = repo.insert(
            CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L), // 关系质感全默认 INITIAL（未动滑块）
            initialRelationshipName = "恋人",
            relationshipSlidersTouched = false,
        )
        val saved = db.characterDao().getByUuid(uuid)!!

        // ① 校准写了原型 id
        assertEquals("LOVER", saved.relationshipArchetypeId)
        // ② 分数被抬到 LOVER 地板（INITIAL 各维 < 地板 → 抬齐；张力/依恋地板 0 → 保持初始 5）
        val q = saved.relationshipQuality
        val lover = RelationshipArchetype.byId("LOVER")!!
        assertTrue("熟悉抬到地板", q.familiarity >= lover.floors[0])
        assertTrue("信任抬到地板", q.trust >= lover.floors[1])
        assertTrue("亲近抬到地板", q.closeness >= lover.floors[2])
        assertEquals("熟悉恰为地板 55", 55, q.familiarity)
        assertEquals("信任恰为地板 30", 30, q.trust)

        // ③ 二维渲染：恋人族 L1（含患得患失），绝无「生疏 / 事务性」
        val rendered = buildArchetypeRelationshipDescription(q, lover, "小明")
        assertNotNull(rendered)
        assertTrue("应渲恋人族信任 L1", rendered.contains("患得患失"))
        assertFalse("绝无生疏", rendered.contains("生疏"))
        assertFalse("绝无事务性", rendered.contains("事务性"))
        assertTrue("段头逐字", rendered.startsWith("你和小明的互动方式："))
    }

    @Test fun `未识别名分_不写原型id渲染留白（存量未扫等价）`() = runBlocking {
        val uuid = repo.insert(
            CharacterEntity(uuid = "c2", name = "阿橙", creationDate = 0L),
            initialRelationshipName = "某种说不清的怪关系zzz", // 词表认不出 → null
            relationshipSlidersTouched = false,
        )
        val saved = db.characterDao().getByUuid(uuid)!!
        assertEquals(null, saved.relationshipArchetypeId)
    }
}
