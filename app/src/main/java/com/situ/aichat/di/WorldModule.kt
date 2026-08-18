package com.situ.aichat.di

import com.situ.aichat.world.WorldSettlementContributor
import com.situ.aichat.world.social.WorldRelationshipContributor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/**
 * 世界系统 DI（W2 图纸 §3.5）：声明结算贡献者的集合——[WorldSettlementCoordinator] 构造注入
 * `Set<WorldSettlementContributor>`，无 `@Multibinds` 声明时 Hilt 无法解析空集会编译失败。
 *
 * W4 起集合含 [WorldRelationshipContributor]（角色↔角色关系）；后续 W6 等各自内容贡献者以 `@IntoSet` 往这里挂。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorldModule {

    @Multibinds
    abstract fun settlementContributors(): Set<WorldSettlementContributor>

    /** W4 社交关系贡献者（第一个真实结算贡献者·契约 §8 / W4 图纸 §3.8）。 */
    @Binds
    @IntoSet
    abstract fun bindRelationshipContributor(impl: WorldRelationshipContributor): WorldSettlementContributor
}
