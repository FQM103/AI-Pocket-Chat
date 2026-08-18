package com.situ.aichat.work

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 备份目录结构性不可用（子文件无法创建/打开——目录被删/移/无写权限）。worker 据此自动关闭而非无限重试。 */
class BackupFolderUnavailableException(message: String) : Exception(message)

/**
 * 自动备份的 SAF 目录操作（13.6c）：在用户选定的持久 tree URI 里建子文件、流式写入、按文件名标签轮转旧备份。
 *
 * 用原生 [DocumentsContract] + [Context.getContentResolver]（**不引 androidx.documentfile 依赖**）。文件名内嵌
 * `yyyyMMdd_HHmmss` 时间戳 + 媒体标签 → 字典序倒排即时间倒序，轮转只按文件名排序、绝不读盘。只动本 app 自己产出的
 * 备份（[PREFIX] 前缀），用户放在同目录的其他文件一律不碰。
 */
object AutoBackupFolder {
    private const val TAG = "AutoBackupFolder"

    /** 自动备份文件名前缀（与手动导出 `AIChat_backup_` 同前缀，但轮转只认本前缀，手动导出的也会一并纳入轮转）。 */
    const val PREFIX = "AIChat_backup_"

    /** 含媒体备份的文件名标签（夹在时间戳之后、`.zip` 之前）。 */
    const val MEDIA_TAG = "_media"

    private const val EXT = ".zip"

    /**
     * E2#2：写入中的临时文件后缀。写到 `<name>.part`、写完再 [DocumentsContract.renameDocument] 原子换正式名。
     * `.part` 不以 [EXT] 结尾 → [backupsToPrune]/[parseBackupFileName]/[selectRecentBackups] 全部按 `.zip`
     * 过滤，故进程死/断电留下的截断临时文件对所有「备份列表/轮转」逻辑**天然隐形**，绝不冒充最新备份、也不占轮转名额。
     * 用 octet-stream 建（避免 provider 据 zip MIME 给 `.part` 名强加 `.zip` 后缀）；rename 后查询按 `.zip` 名重新派生 MIME。
     */
    private const val TEMP_SUFFIX = ".part"
    private const val TEMP_MIME = "application/octet-stream"

    /**
     * 备份文件名：`AIChat_backup_<yyyyMMdd_HHmmss>[_media].zip`。[timestamp] 由调用方传入（worker 用真实时间）。
     * 用**秒**级精度（非分钟）：同一退避周期内的失败重试不会撞同名 → 不会触发 SAF 自动加「(1)」后缀破坏轮转的字典序。
     */
    fun fileName(timestamp: Long, includeMedia: Boolean): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
        return "$PREFIX$ts${if (includeMedia) MEDIA_TAG else ""}$EXT"
    }

    /** tree URI 是否仍持有可写持久授权（用户撤销 / 换机后会失效）。 */
    fun hasPersistedPermission(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    /**
     * 在 [treeUri] 下**原子地**产出名为 [name] 的 zip 备份（E2#2）：先建 `<name>.part` 临时文件流式写入，写完用
     * [DocumentsContract.renameDocument] 换成正式 [name]——读者要么看不到、要么看到完整文件，绝无截断 zip 冒充最新备份。
     * 阻塞 SAF I/O 切到 [Dispatchers.IO]。子文件**无法创建/打开**（目录被删/移/无写权限）→ 抛
     * [BackupFolderUnavailableException]（worker 据此自动关闭，不无限重试）；写入中途失败/换名失败 → **删掉临时文件**
     * 再抛出（进程死则临时文件残留，但 `.part` 对列表逻辑隐形，由 [cleanTempFiles] 开跑清扫）。
     */
    suspend fun writeBackup(context: Context, treeUri: Uri, name: String, write: suspend (OutputStream) -> Unit) =
        withContext(Dispatchers.IO) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val child = DocumentsContract.createDocument(context.contentResolver, parent, TEMP_MIME, name + TEMP_SUFFIX)
                ?: throw BackupFolderUnavailableException("无法在备份目录创建文件 $name$TEMP_SUFFIX")
            try {
                val out = context.contentResolver.openOutputStream(child)
                    ?: throw BackupFolderUnavailableException("无法打开备份文件输出流")
                out.use { write(it) }
                // 写完整后原子换正式名。renameDocument 在标准 ExternalStorageProvider 上可靠；返回 null（不支持/重名）
                // 视为失败：删临时文件并抛出（worker 退避重试），绝不把 .part 当成已完成备份留着。
                val renamed = DocumentsContract.renameDocument(context.contentResolver, child, name)
                    ?: throw BackupFolderUnavailableException("备份换名失败：$name")
                Log.d(TAG, "备份原子写完成：$name uri=$renamed")
            } catch (e: Throwable) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, child) }
                throw e
            }
        }

    /**
     * E2#2：清扫目录里残留的 `.part` 临时文件（上次写到一半进程死/断电留下的）。worker 开跑时调一次，幂等。
     * 只删本 app 备份的临时文件（[PREFIX] 前缀 + [TEMP_SUFFIX] 后缀），用户其他文件一律不碰。
     */
    fun cleanTempFiles(context: Context, treeUri: Uri) {
        listChildren(context, treeUri)
            .filter { it.second.startsWith(PREFIX) && it.second.endsWith(TEMP_SUFFIX) }
            .forEach { (docId, name) ->
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    .onFailure { Log.w(TAG, "清扫残留临时备份失败：$name", it) }
            }
    }

    /** 轮转：删掉超出保留份数的旧备份（文本留 [keepText] 新、媒体留 [keepMedia] 新）。其他文件不动。 */
    fun prune(context: Context, treeUri: Uri, keepText: Int, keepMedia: Int) {
        val children = listChildren(context, treeUri) // (docId, displayName)
        val pruneNames = backupsToPrune(children.map { it.second }, keepText, keepMedia).toHashSet()
        children.filter { it.second in pruneNames }.forEach { (docId, name) ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                .onFailure { Log.w(TAG, "轮转删除失败：$name", it) }
        }
    }

    private fun listChildren(context: Context, treeUri: Uri): List<Pair<String, String>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val result = ArrayList<Pair<String, String>>()
        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    result.add(id to name)
                }
            }
        }.onFailure { Log.w(TAG, "列目录失败", it) }
        return result
    }

    /**
     * 纯函数（可测）：从目录文件名挑出该删的备份。只认本 app 备份（[PREFIX] 前缀）；文本（无 [MEDIA_TAG]）留最近
     * [keepText] 份、媒体（含 [MEDIA_TAG]）留最近 [keepMedia] 份；其余文件不在返回里（不会被删）。
     * 文件名内嵌固定宽度时间戳 → `sortedDescending` 即时间倒序，`drop(keep)` 即多出来要删的。
     */
    internal fun backupsToPrune(names: List<String>, keepText: Int, keepMedia: Int): List<String> {
        val ours = names.filter { it.startsWith(PREFIX) && it.endsWith(EXT) }
        val media = ours.filter { it.contains(MEDIA_TAG) }.sortedDescending()
        val text = ours.filter { !it.contains(MEDIA_TAG) }.sortedDescending()
        return media.drop(keepMedia.coerceAtLeast(0)) + text.drop(keepText.coerceAtLeast(0))
    }

    // ── 导入区「最近备份」列表（15.2-P1·P1-8，安卓超越——iOS 备份纯手动无自动备份故无此入口） ──

    /** [parseBackupFileName] 的解析结果：文件名内嵌时间戳 + 是否含媒体标签。 */
    internal data class ParsedBackupName(val timestampMillis: Long, val includesMedia: Boolean)

    /** 备份目录里的一份可导入备份：[uri] 为 tree 内子文档 Uri，可直接 openInputStream 走既有导入链。 */
    data class BackupFileEntry(
        val uri: Uri,
        val displayName: String,
        val timestampMillis: Long,
        val includesMedia: Boolean,
    )

    /**
     * 纯函数（可测）：解析备份文件名。同时接受**秒级**（自动备份 [fileName]，核 15 位 `yyyyMMdd_HHmmss`）与
     * **分钟级**（手动导出 BackupScreen.exportFileName，核 13 位 `yyyyMMdd_HHmm`、永不带 [MEDIA_TAG]）两种——
     * 手动导出存进同目录会被轮转一并纳入，最近列表必须同样认得，否则两边口径不一致。非本 app 备份名 /
     * 时间戳非法（isLenient=false + 必须整核消费）→ null。
     */
    internal fun parseBackupFileName(name: String): ParsedBackupName? {
        if (!name.startsWith(PREFIX) || !name.endsWith(EXT)) return null
        var core = name.removePrefix(PREFIX).removeSuffix(EXT)
        val media = core.endsWith(MEDIA_TAG)
        if (media) core = core.removeSuffix(MEDIA_TAG)
        val pattern = when (core.length) {
            15 -> "yyyyMMdd_HHmmss"
            13 -> "yyyyMMdd_HHmm"
            else -> return null
        }
        val fmt = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val pos = ParsePosition(0)
        val date = fmt.parse(core, pos) ?: return null
        if (pos.index != core.length) return null
        return ParsedBackupName(date.time, media)
    }

    /**
     * 纯函数（可测）：从目录文件名挑出导入区要展示的最近 [limit] 份（时间倒序；同时间戳按名字倒序定序）。
     * **媒体份保底**：媒体备份每 ~7 天才一份（worker 节奏），最新媒体份常老于最近几份文本份——若它没进前
     * [limit]，追加到末尾（换机恢复最需要的恰是全量媒体份）。
     */
    internal fun selectRecentBackups(names: List<String>, limit: Int): List<String> {
        val parsed = names.mapNotNull { n -> parseBackupFileName(n)?.let { n to it } }
            .sortedWith(
                compareByDescending<Pair<String, ParsedBackupName>> { it.second.timestampMillis }
                    .thenByDescending { it.first },
            )
        val recent = parsed.take(limit.coerceAtLeast(0))
        val newestMedia = parsed.firstOrNull { it.second.includesMedia }
        val result = recent.toMutableList()
        if (newestMedia != null && recent.none { it.first == newestMedia.first }) result.add(newestMedia)
        return result.map { it.first }
    }

    /**
     * 列出备份目录里最近的可导入备份（P1-8）。授权丢失 / 目录被删 → [listChildren] 自带 runCatching 回空表，
     * 调用方整块隐藏即可（优雅降级，不预检 persistedUriPermissions——读路径直接试错更简单）。
     */
    suspend fun listBackupFiles(context: Context, treeUri: Uri, limit: Int = RECENT_LIMIT): List<BackupFileEntry> =
        withContext(Dispatchers.IO) {
            val children = listChildren(context, treeUri) // (docId, displayName)
            val docIdByName = children.associate { (id, name) -> name to id }
            selectRecentBackups(children.map { it.second }, limit).mapNotNull { name ->
                val docId = docIdByName[name] ?: return@mapNotNull null
                val parsed = parseBackupFileName(name) ?: return@mapNotNull null
                BackupFileEntry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                    displayName = name,
                    timestampMillis = parsed.timestampMillis,
                    includesMedia = parsed.includesMedia,
                )
            }
        }

    /** 导入区默认展示份数。 */
    const val RECENT_LIMIT = 5
}
