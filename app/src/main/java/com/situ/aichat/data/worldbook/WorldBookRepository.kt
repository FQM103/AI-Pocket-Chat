package com.situ.aichat.data.worldbook

import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.data.local.dao.WorldBookSummary
import com.situ.aichat.data.local.entity.WorldBookBindingEntity
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界书数据仓库（WB6a·契约 §3.3/§9）——导入 / 导出 / 管理的**唯一入口**：
 * WB7 管理 UI 与 WB6b 备份集成都只经本仓库，界面绝不直碰 DAO（分层铁律）。
 * - 导入：酒馆双格式自动识别（[WorldBookCodec]），解析失败抛 [WorldBookParseException]（message 即人话，UI 直接展示）；
 * - 导出：酒馆独立世界书 JSON（生态通用语，round-trip 保障在 codec 层测试锁定）；
 * - 向量条目的嵌入不在导入时算——WB5 的按需懒补在首次聊天触达时自动完成（导入零等待）。
 */
@Singleton
class WorldBookRepository @Inject constructor(
    private val worldBookDao: WorldBookDao,
) {

    /** 导入结果摘要（UI 提示用）。 */
    data class ImportResult(
        val bookUuid: String,
        val bookName: String,
        val entryCount: Int,
        val skippedEntryCount: Int,
        val format: WorldBookCodec.WorldBookFormat,
    )

    /**
     * 从 JSON 文本导入一本世界书（新书新 uuid，不与既有书合并）。
     * @param fallbackName 书名兜底（通常传文件名去后缀）
     * @throws WorldBookParseException 解析失败（message 为人话）
     */
    suspend fun importFromJson(jsonText: String, fallbackName: String): ImportResult {
        val parsed = WorldBookCodec.parse(jsonText, fallbackName)
        worldBookDao.upsertBook(parsed.book)
        worldBookDao.upsertEntries(parsed.entries)
        return ImportResult(
            bookUuid = parsed.book.uuid,
            bookName = parsed.book.name,
            entryCount = parsed.entries.size,
            skippedEntryCount = parsed.skippedEntryCount,
            format = parsed.format,
        )
    }

    /** 导出为酒馆独立世界书 JSON；书不存在 → null。 */
    suspend fun exportBookAsJson(bookUuid: String): String? {
        val book = worldBookDao.getBook(bookUuid) ?: return null
        return WorldBookCodec.exportToStJson(book, worldBookDao.entriesForBook(bookUuid))
    }

    // MARK: - 管理（WB7 UI 的数据面）

    fun observeAllBooks(): Flow<List<WorldBookEntity>> = worldBookDao.observeAllBooks()

    fun observeBookSummaries(): Flow<List<WorldBookSummary>> = worldBookDao.observeBookSummaries()

    fun observeBook(bookUuid: String): Flow<WorldBookEntity?> = worldBookDao.observeBook(bookUuid)

    fun observeBoundBooks(characterUuid: String) = worldBookDao.observeBoundBooks(characterUuid)

    fun observeBoundCharacterUuids(bookUuid: String): Flow<List<String>> =
        worldBookDao.observeBoundCharacterUuids(bookUuid)

    fun observeEntriesForBook(bookUuid: String) = worldBookDao.observeEntriesForBook(bookUuid)

    suspend fun getBook(bookUuid: String): WorldBookEntity? = worldBookDao.getBook(bookUuid)

    /** 新建空书（WB7 书架「新建」/ 模板复制底座），返回新书 uuid。 */
    suspend fun createBook(name: String, description: String = ""): String {
        val book = WorldBookEntity(name = name, description = description)
        worldBookDao.upsertBook(book)
        return book.uuid
    }

    /**
     * 模板复制成「我的书」（WB8·契约 §12.2）：书与全部条目均取**全新 uuid**（模板原型的 uuid 是占位空串，
     * 重复复制得到互不相干的两本书）；uid/displayIndex 按模板顺序重排；嵌入两列置空交 WB5 懒补。
     * 复制后与模板零关联——随便改随便删，不做只读内置（D5 拍板）。
     */
    suspend fun copyTemplate(template: WorldBookTemplate): String {
        val book = WorldBookEntity(name = template.name, description = template.description)
        worldBookDao.upsertBook(book)
        val entries = template.entries.mapIndexed { index, proto ->
            proto.copy(
                uuid = UUID.randomUUID().toString(),
                bookUuid = book.uuid,
                uid = index,
                displayIndex = index,
                embedding = null,
                embeddingSignature = null,
            )
        }
        worldBookDao.upsertEntries(entries)
        return book.uuid
    }

    /** 改书名/简介（其余字段不动）。 */
    suspend fun updateBookMeta(bookUuid: String, name: String, description: String) {
        val book = worldBookDao.getBook(bookUuid) ?: return
        worldBookDao.upsertBook(
            book.copy(name = name, description = description, updatedAt = System.currentTimeMillis()),
        )
    }

    suspend fun updateBook(book: WorldBookEntity) =
        worldBookDao.upsertBook(book.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteBook(bookUuid: String) = worldBookDao.deleteBook(bookUuid)

    /** 开关不 bump updatedAt——书架按更新时间排序，拨个开关不该让卡片跳到最上面（WB7）。 */
    suspend fun setBookEnabled(bookUuid: String, enabled: Boolean) {
        val book = worldBookDao.getBook(bookUuid) ?: return
        worldBookDao.upsertBook(book.copy(enabled = enabled))
    }

    suspend fun setBookGlobal(bookUuid: String, isGlobal: Boolean) {
        val book = worldBookDao.getBook(bookUuid) ?: return
        worldBookDao.upsertBook(book.copy(isGlobal = isGlobal))
    }

    // MARK: - 条目（WB7 编辑器·经仓库单点）

    suspend fun getEntry(entryUuid: String): WorldBookEntryEntity? = worldBookDao.getEntry(entryUuid)

    /** 新条目草稿：uid / displayIndex 取书内 max+1（uid 供导出还原为 ST entries 键，displayIndex 供 UI 排序）。 */
    suspend fun newEntryDraft(bookUuid: String): WorldBookEntryEntity {
        val entries = worldBookDao.entriesForBook(bookUuid)
        return WorldBookEntryEntity(
            bookUuid = bookUuid,
            uid = (entries.maxOfOrNull { it.uid } ?: -1) + 1,
            displayIndex = (entries.maxOfOrNull { it.displayIndex } ?: -1) + 1,
        )
    }

    /**
     * 保存条目（新建或编辑）。热更新两约束（契约 §12.11）：
     * - 嵌入两列**永不用编辑期快照回写**——[WorldBookDao.updateEntryEmbedding] 是 targeted UPDATE，
     *   编辑期间向量服务可能刚写入新嵌入，整行回写快照会把它冲掉；已有条目一律以库内现值为准；
     * - 标题/内容变了 → 清嵌入两列，WB5 懒补在下次触达时用新文本重嵌——语义触发立即跟上新内容。
     */
    suspend fun saveEntry(entry: WorldBookEntryEntity) {
        val existing = worldBookDao.getEntry(entry.uuid)
        val toSave = when {
            existing == null -> entry.copy(embedding = null, embeddingSignature = null)
            existing.content != entry.content || existing.comment != entry.comment ->
                entry.copy(embedding = null, embeddingSignature = null)
            else -> entry.copy(embedding = existing.embedding, embeddingSignature = existing.embeddingSignature)
        }
        worldBookDao.upsertEntry(toSave)
        touchBook(entry.bookUuid)
    }

    suspend fun deleteEntry(entryUuid: String) {
        val entry = worldBookDao.getEntry(entryUuid) ?: return
        worldBookDao.deleteEntry(entryUuid)
        touchBook(entry.bookUuid)
    }

    /** 条目开关走 targeted UPDATE（不整行回写，理由同 [saveEntry]；也不 bump 书序）。 */
    suspend fun setEntryEnabled(entryUuid: String, enabled: Boolean) =
        worldBookDao.setEntryEnabled(entryUuid, enabled)

    /** 条目内容实变（新增/删除/改文）时把书的 updatedAt 顶上去——书架按更新时间排序。 */
    private suspend fun touchBook(bookUuid: String) {
        val book = worldBookDao.getBook(bookUuid) ?: return
        worldBookDao.upsertBook(book.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun bind(characterUuid: String, bookUuid: String) =
        worldBookDao.bind(WorldBookBindingEntity(characterUuid = characterUuid, bookUuid = bookUuid))

    suspend fun unbind(characterUuid: String, bookUuid: String) =
        worldBookDao.unbind(characterUuid, bookUuid)

    suspend fun boundBookUuids(characterUuid: String): List<String> =
        worldBookDao.boundBookUuids(characterUuid)

    fun observeBoundBookUuidsForCharacter(characterUuid: String): Flow<List<String>> =
        worldBookDao.observeBoundBookUuidsForCharacter(characterUuid)

    /**
     * 单选模式（WB7c 绑定 sheet 主脸「选一本世界观」）：把该角色的绑定收敛为至多一本
     * （解绑其余 → 绑指定；null = 全解绑）。仅在当前绑定 ≤1 本时由 UI 调用——多本叠加时
     * sheet 直接进多选模式逐本增删，不会静默丢叠加（契约 §12.5 实现口径）。
     */
    suspend fun setSoleBinding(characterUuid: String, bookUuid: String?) {
        val current = worldBookDao.boundBookUuids(characterUuid)
        current.filter { it != bookUuid }.forEach { worldBookDao.unbind(characterUuid, it) }
        if (bookUuid != null && bookUuid !in current) {
            worldBookDao.bind(WorldBookBindingEntity(characterUuid = characterUuid, bookUuid = bookUuid))
        }
    }
}
