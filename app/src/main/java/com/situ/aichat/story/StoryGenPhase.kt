package com.situ.aichat.story

/**
 * 故事生成的真实阶段（灵动岛卷一图纸 §3.1·锁定 5 值）。
 *
 * 与旧「假进度定时器」的区别：每个值都由**真实事件**驱动，绝不靠定时爬升——
 * [PREPARING] 起步即写、[WRITING] 由首个有效流式 preview 推导、[FINALIZING]/[ARCHIVING]
 * 由 [StoryGenerationService] 的 onPhase 回调发射、[DONE] 由成功路写入。
 *
 * 显示侧只有 4 段（[ARCHIVING] 与 [DONE] 同占第 4 段），映射口径**唯一**收口在
 * [StoryProgressModel]——药丸 / 阅读器遮罩 / 书架卡片三处消费同一份。
 */
enum class StoryGenPhase { PREPARING, WRITING, FINALIZING, ARCHIVING, DONE }
