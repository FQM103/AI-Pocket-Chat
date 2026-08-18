package com.situ.aichat.ui.backup

import com.situ.aichat.R
import com.situ.aichat.data.backup.ImportStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-19 策略→标签映射单测（分段钮与 stateDescription 共用此映射）。断言来源 = iOS Picker tag 序与文案
 * （CharacterBackupImportPreviewView.swift:116-118：创建副本=duplicate / 覆盖已有=overwrite / 跳过=skip）。
 */
class BackupStrategyLabelTest {

    @Test fun strategyLabelRes_mapsAllThree_matchingIosPickerTags() {
        assertEquals(R.string.backup_strategy_duplicate, strategyLabelRes(ImportStrategy.DUPLICATE))
        assertEquals(R.string.backup_strategy_overwrite, strategyLabelRes(ImportStrategy.OVERWRITE))
        assertEquals(R.string.backup_strategy_skip, strategyLabelRes(ImportStrategy.SKIP))
    }
}
