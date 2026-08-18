package com.situ.aichat.data.repository

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.util.AvatarStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 原子删除角色：先经 [CharacterDeletionCleaner] 清散落关联数据、再删角色本体、最后删头像文件（1:1 iOS 删序），
 * 跑在 **app 级 scope** 上。
 *
 * **为什么不放 ViewModel**：联系人页的 `ContactsViewModel` 随导航离开 Contacts 标签销毁、`viewModelScope` 即取消。
 * 删除链「清散落数据（朋友圈/日记/通知台账/逐会话媒体文件…，可能耗时）→ 删角色行 → 删头像」若在 cleanup 跑了一半、
 * `characterRepo.delete` 之前被取消，会留下「部分清理但仍存在」的半残角色（如孤儿朋友圈帖永留 feed）。故用
 * `@Singleton` + `SupervisorJob` scope 保证删除一旦发起就跑完，不随页面离屏中断（同 [ConversationDeletionService]）。
 */
@Singleton
class CharacterDeletionService @Inject constructor(
    private val deletionCleaner: CharacterDeletionCleaner,
    private val characterRepo: CharacterRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 删角色（fire-and-forget；先清散落关联数据，再删角色本体，最后删头像文件——iOS 顺序）。 */
    fun delete(character: CharacterEntity) {
        scope.launch {
            // 必须在 characterRepo.delete 之前：先清散落关联数据，再删角色本体（iOS 顺序）。
            deletionCleaner.cleanup(character)
            characterRepo.delete(character.uuid)
            withContext(Dispatchers.IO) { AvatarStore.delete(character.avatarPath) }
        }
    }
}
