package com.situ.aichat.diagnostics.perf

/**
 * 待采清单的一项（图纸 §3.6 逐字锁定字段）。
 * [label] 由纯函数填的是**稳定 id**（[PerfChecklist] 里的常量）；界面与报告各自换成本地化文案再显示。
 */
data class ChecklistItem(
    val label: String,
    val collected: Int,
    val required: Int,
    val done: Boolean,
)

/**
 * 待采清单判定（纯函数·图纸 §3.6：恰 6 项，required 分别 3 / 10 / 1 / 1 / 1 / 1）。
 *
 * 存在的意义是让用户知道「还差什么才算采全」——报告缺了哪一块，分析时就只能靠猜。
 */
object PerfChecklist {

    /** 6 项的稳定 id（顺序 = 界面与报告的呈现顺序）。 */
    const val ID_COLD_START = "cold_start"
    const val ID_FOREGROUND = "foreground"
    const val ID_SLIDER = "slider"
    const val ID_WORLD = "world_planet"
    const val ID_CALL = "voice_call"
    const val ID_BACKUP = "backup"

    /** 图纸 §9② 锁定的 required 值。 */
    const val REQUIRED_COLD_START = 3
    const val REQUIRED_FOREGROUND = 10
    const val REQUIRED_SLIDER = 1
    const val REQUIRED_WORLD = 1
    const val REQUIRED_CALL = 1
    const val REQUIRED_BACKUP = 1

    fun evaluate(samples: List<PerfSample>): List<ChecklistItem> {
        var coldStarts = 0
        var foregrounds = 0
        var sliderDrags = 0
        var planetSessions = 0
        var callSessions = 0
        var backupProbes = 0
        for (sample in samples) {
            when (sample) {
                is PerfSample.Foreground -> {
                    foregrounds++
                    // 冷启动 = 进程内第一趟回前台，由 PerfCollector 在 passes 里插的 cold_start 标记认（无需新样本类型）。
                    if (sample.passes.any { it.name == PerfPassNames.COLD_START }) coldStarts++
                }
                is PerfSample.SettingsWrite -> sliderDrags++
                is PerfSample.Frames -> when (sample.scene) {
                    PerfScenes.WORLD_PLANET -> planetSessions++
                    PerfScenes.VOICE_CALL -> callSessions++
                    else -> Unit
                }
                is PerfSample.BackupProbe -> backupProbes++
                is PerfSample.Health -> Unit
            }
        }
        return listOf(
            item(ID_COLD_START, coldStarts, REQUIRED_COLD_START),
            item(ID_FOREGROUND, foregrounds, REQUIRED_FOREGROUND),
            item(ID_SLIDER, sliderDrags, REQUIRED_SLIDER),
            item(ID_WORLD, planetSessions, REQUIRED_WORLD),
            item(ID_CALL, callSessions, REQUIRED_CALL),
            item(ID_BACKUP, backupProbes, REQUIRED_BACKUP),
        )
    }

    private fun item(id: String, collected: Int, required: Int) =
        ChecklistItem(label = id, collected = collected, required = required, done = collected >= required)
}
