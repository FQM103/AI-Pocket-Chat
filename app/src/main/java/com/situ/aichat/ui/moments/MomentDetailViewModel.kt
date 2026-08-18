package com.situ.aichat.ui.moments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.moments.MomentInteractionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 朋友圈详情（M06 7.2.8，对齐 iOS `MomentDetailView`）的 ViewModel。响应式观察单帖（含评论+点赞，AI 异步
 * 互动自动刷新）+ 角色字典 + 用户资料。用户提交评论 → 落库 + `scheduleAIReply`（已建，延迟 = `momentCommentDelay`）。
 */
@HiltViewModel
class MomentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val momentRepo: MomentRepository,
    private val settingsRepo: SettingsRepository,
    private val interactionService: MomentInteractionService,
    characterRepo: CharacterRepository,
    userProfileDao: UserProfileDao,
) : ViewModel() {

    val postUuid: String = savedStateHandle.get<String>(ARG_UUID).orEmpty()

    val post: StateFlow<MomentPostWithRelations?> =
        momentRepo.observePost(postUuid)
            .map { visiblePostOrNull(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val characters: StateFlow<Map<String, CharacterEntity>> =
        characterRepo.observeAll()
            .map { list -> list.associateBy { it.uuid } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val userProfile: StateFlow<UserProfileEntity?> =
        userProfileDao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 提交评论（对齐 iOS `submitComment`）：trim → 落 user 评论（带回复目标）→ 排 AI 延迟回复。
     * [replyToCommentUuid]/[replyToName] 为回复某条评论时的父评论与显示名（顶层评论传 null）。
     */
    fun submitComment(text: String, replyToCommentUuid: String?, replyToName: String?) {
        val content = text.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            val comment = momentRepo.addComment(
                postUuid = postUuid,
                content = content,
                authorType = MomentAuthorType.USER,
                characterUuid = null,
                replyToName = replyToName,
                parentCommentUuid = replyToCommentUuid,
            )
            val delayMinutes = settingsRepo.getAppSettings().momentCommentDelay
            interactionService.scheduleAIReply(comment.uuid, postUuid, delayMinutes)
        }
    }

    /** 删除评论（cascade 删其回复，对齐 iOS `modelContext.delete(comment)`）。 */
    fun deleteComment(uuid: String) {
        viewModelScope.launch { momentRepo.deleteComment(uuid) }
    }

    companion object {
        const val ARG_UUID = "uuid"
    }
}

/**
 * 详情页可见性收口：软删帖（用户已删，GC 前最多存活 ~30 天）等同「不存在」，映射为 null —— 让 UI 现有的
 * `p == null` 分支统一显示「已删除」占位，与列表/通知列表路径（DAO `isSoftDeleted = 0` 过滤）行为一致。
 *
 * 为何必须在此收口：系统通知深链（[com.situ.aichat.notification.Notifier.momentClickIntent]）携原始 uuid
 * 直跳详情，走的是**未过滤**的 `observePostWithRelations`（`SELECT … WHERE uuid = :uuid`），不经通知列表的
 * `findNonDeletedPostUuidByTimestamp`；不在此映射，已删帖会被完整重渲染（正文+图+评论）。纯函数便于单测。
 */
internal fun visiblePostOrNull(post: MomentPostWithRelations?): MomentPostWithRelations? =
    post?.takeUnless { it.post.isSoftDeleted }
