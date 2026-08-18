package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.prompt.notification.NotificationTemplateGenerator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * 通知文案生成后台任务（P6.1c-ii）。两条触发口（对齐 iOS）：
 * - **建角色**（[enqueueForCharacter]，带 characterId）：为新角色生成一套通知文案（LLM，失败回退默认文案）。
 * - **启动补生成**（无 characterId）：对仍在用默认文案的角色重生成（24h 节流，对齐 iOS
 *   `AppBootstrapService.checkAndRegenerateDefaultNotificationTemplates` + `regenerateIfUsingDefaults`）。
 *
 * 生成逻辑全在 [NotificationTemplateGenerator]（已就绪）；本 worker 只负责「何时、为谁」调度。
 */
@HiltWorker
class NotificationTemplateWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val characterRepository: CharacterRepository,
    private val apiConfigRepository: ApiConfigRepository,
    private val templateGenerator: NotificationTemplateGenerator,
    private val templateDao: NotificationTemplateDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val config = apiConfigRepository.resolveConfigValues(ApiFunction.NOTIFICATION_TEMPLATE)
        val characterId = inputData.getString(KEY_CHARACTER_ID)
        if (characterId != null) {
            // 建角色：强制生成（config 为 null 时 generateAndSave 自动存默认文案）。
            characterRepository.get(characterId)?.let { character ->
                templateGenerator.generateAndSave(character, characterRepository.currentRelationship(characterId), config)
            }
        } else {
            regenerateDefaultsIfDue(config)
        }
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "通知文案生成 worker 异常，将重试", e)
        Result.retry()
    }

    /** 对在用默认文案的角色补生成（24h 节流，避免每次启动都扫）。 */
    private suspend fun regenerateDefaultsIfDue(config: com.situ.aichat.data.remote.llm.ApiConfigValues?) {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        if (now - last < TWENTY_FOUR_HOURS_MS) {
            Log.d(TAG, "距上次补生成检查未满 24h，跳过")
            return
        }
        // config 为 null（未配置 API）时补生成无意义（只会重存默认）→ 等配置好后再扫。
        if (config == null) return
        characterRepository.observeAll().first().forEach { character ->
            // 主动通知真实感改造 D-8：翻新条件从「仍用默认」扩为「仍用默认 **或** 池龄 >30 天」——
            // 短句池是到点现做失败时的应急兜底，月级新鲜度对低频消费足够；不做「关系升级/记忆合并标脏」钩子
            // （那要侵入两个无关模块），时效重烤零侵入。
            val stale = shouldRefreshByAge(templateDao.oldestCreatedAt(character.uuid), now)
            if (templateGenerator.isUsingDefaultTemplates(character.uuid) || stale) {
                templateGenerator.generateAndSave(character, characterRepository.currentRelationship(character.uuid), config)
            }
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
    }

    companion object {
        const val TAG = "NotifTemplateWorker"
        const val UNIQUE_REGENERATE_DEFAULTS = "notif_template_regenerate_defaults"
        const val KEY_CHARACTER_ID = "characterId"
        private const val PREFS = "notif_template_maintenance"
        private const val KEY_LAST_CHECK = "last_regenerate_check"
        private const val TWENTY_FOUR_HOURS_MS = 24L * 60 * 60 * 1000

        /** 模板池时效上限：池龄超此值即重烤一轮（图纸 §9 锁定 30 天）。 */
        private const val TEMPLATE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * 池龄是否已超 [TEMPLATE_MAX_AGE_MS]（D-8 时效翻新）。池空（null）→ 不触发（交给「仍用默认」那一路）。
         * 纯函数（internal 供单测，免 work-testing 依赖）。
         */
        internal fun shouldRefreshByAge(oldestCreatedAt: Long?, now: Long): Boolean =
            oldestCreatedAt != null && now - oldestCreatedAt > TEMPLATE_MAX_AGE_MS

        /** 建角色后入队：为该角色生成通知文案（不强制联网，离线则存默认文案）。 */
        fun enqueueForCharacter(context: Context, characterId: String) {
            val request = OneTimeWorkRequest.Builder(NotificationTemplateWorker::class.java)
                .setInputData(workDataOf(KEY_CHARACTER_ID to characterId))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("notif_template_$characterId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
