package com.situ.aichat.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** 应用级聊天回合作用域限定符（健康线 2-5b）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ChatTurnScope

/**
 * 聊天回合的应用级作用域（健康线 2-5b·用户拍板 2026-07-03「IM 语义」）：AI 回合与其收尾维护
 * （嵌入/记忆/成长/节拍触发）不再绑定聊天页 viewModelScope——**退出会话后回合在后台继续**，回复照常
 * 落库 → 会话列表未读红点（App 真在后台时另有本地通知），重进会话由 RecoveryClaimTracker 占坑互斥防双答。
 *
 * - `Main.immediate`：保留原「job 把手同步赋值 / 无挂起点原子置 isSending」语义（控制器并发闸依赖）。
 * - `SupervisorJob`：单回合异常绝不放倒全局作用域。
 * - 进程死亡仍会丢在途回合——由既有恢复体系兜底（冷启/回前台扫描 + 进会话 autoRecover），语义不变。
 */
@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopeModule {

    @Provides
    @Singleton
    @ChatTurnScope
    fun provideChatTurnScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
