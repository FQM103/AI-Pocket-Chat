package com.situ.aichat.ui.worldbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.WorldBookSummary
import com.situ.aichat.data.worldbook.WorldBookParseException
import com.situ.aichat.data.worldbook.WorldBookRepository
import com.situ.aichat.data.worldbook.WorldBookTemplate
import com.situ.aichat.data.worldbook.WorldBookTemplates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 导入反馈：成功弹层 / 失败弹窗二选一；[Failure.parseMessage]=null 表示文件读不出来（非解析失败）。 */
sealed interface WorldBookImportFeedback {
    data class Success(val result: WorldBookRepository.ImportResult) : WorldBookImportFeedback
    data class Failure(val parseMessage: String?) : WorldBookImportFeedback
}

/**
 * 设定集书架（WB7a·契约 §12.2）：书列表实时流 + SAF 导入/导出编排 + 新建。
 * 数据面只经 [WorldBookRepository]（分层铁律：UI 绝不碰 DAO）。
 */
@HiltViewModel
class WorldBookShelfViewModel @Inject constructor(
    private val repository: WorldBookRepository,
) : ViewModel() {

    val books: StateFlow<List<WorldBookSummary>> = repository.observeBookSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 预置模板（WB8·静态内容；「从模板开始」区块空时隐藏由 UI 判空）。 */
    val templates: List<WorldBookTemplate> = WorldBookTemplates.all

    private val _importFeedback = MutableStateFlow<WorldBookImportFeedback?>(null)
    val importFeedback: StateFlow<WorldBookImportFeedback?> = _importFeedback.asStateFlow()

    /** 导出结果一次性事件（null=空闲；true/false=成败，UI 弹 Toast 后 [consumeExportResult]）。 */
    private val _exportResult = MutableStateFlow<Boolean?>(null)
    val exportResult: StateFlow<Boolean?> = _exportResult.asStateFlow()

    /** SAF 选中文件后：读文本（IO 调度由调用方包好）→ 仓库导入；解析失败的人话原文直显（契约 §12.6）。 */
    fun import(fallbackName: String, readText: suspend () -> String?) {
        viewModelScope.launch {
            val text = readText()
            if (text.isNullOrBlank()) {
                _importFeedback.value = WorldBookImportFeedback.Failure(parseMessage = null)
                return@launch
            }
            _importFeedback.value = try {
                WorldBookImportFeedback.Success(repository.importFromJson(text, fallbackName))
            } catch (e: WorldBookParseException) {
                WorldBookImportFeedback.Failure(e.message)
            }
        }
    }

    fun dismissImportFeedback() {
        _importFeedback.value = null
    }

    /** 导出：书 → JSON 文本 → SAF 目标（写入回调由 UI 提供，返回是否成功）。 */
    fun exportBook(bookUuid: String, writeText: suspend (String) -> Boolean) {
        viewModelScope.launch {
            val json = repository.exportBookAsJson(bookUuid)
            _exportResult.value = json != null && writeText(json)
        }
    }

    fun consumeExportResult() {
        _exportResult.value = null
    }

    fun createBook(name: String, description: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            onCreated(repository.createBook(name.trim(), description.trim()))
        }
    }

    /** 模板一键复制成「我的书」，回调新书 uuid（UI 直接跳书详情）。 */
    fun copyTemplate(template: WorldBookTemplate, onCopied: (String) -> Unit) {
        viewModelScope.launch { onCopied(repository.copyTemplate(template)) }
    }

    fun updateBookMeta(bookUuid: String, name: String, description: String) {
        viewModelScope.launch { repository.updateBookMeta(bookUuid, name.trim(), description.trim()) }
    }

    fun setBookEnabled(bookUuid: String, enabled: Boolean) {
        viewModelScope.launch { repository.setBookEnabled(bookUuid, enabled) }
    }

    fun setBookGlobal(bookUuid: String, isGlobal: Boolean) {
        viewModelScope.launch { repository.setBookGlobal(bookUuid, isGlobal) }
    }

    fun deleteBook(bookUuid: String) {
        viewModelScope.launch { repository.deleteBook(bookUuid) }
    }
}
