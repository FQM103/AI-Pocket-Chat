package com.situ.aichat.ui.story

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTheme
import kotlinx.coroutines.launch

/**
 * 导演台（故事二期卷三·图纸 §4.4·mockup 屏 4·提案 §9.3 / D-12）：原「写一句」升级成**一个面板两栏正交**。
 *
 * - **栏 A 剧情走向** = 自由输入，**双路**（图纸 2026-08-06「已存走向」§4.4）：末章还没答过时照旧走
 *   [StoryReaderViewModel.submitChoice] 的**创建路**（反悔窗 → commitUserChoice，逐字节零变）；已答过则进
 *   **编辑模式**——预填 [savedDirection]、保存 = 覆盖直写（[StoryReaderViewModel.overwriteDirection]，不进反悔窗）、
 *   另给「撤回走向」。治的是 `submitChoice` 那道「已答即 return」的静默丢弃门（重开面板改走向，字被无声吃掉）。
 * - **栏 B 本章节拍** = 卷一建的 `pendingChapterBeats` 白盒化，预填 AI 预排、可改可清空；改过即
 *   「已由你修改·最高优先」，清空保存 = 本章自由发挥。
 *
 * 两栏**各自独立提交、互不阻塞**（都填 = 两条写各发一次）——所以走向写失败也不会把节拍一起吞掉。
 *
 * **配色有意用 App 主题而非阅读器心情层**（同 [StoryCustomChoiceSheet] 现行做法）：sheet 浮在系统层，
 * 拿心情色会与主题背景打架。草稿只活在本面板里（关闭即弃·不做进程死亡恢复·同卷二 J7 口径）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryDirectorSheet(
    beats: String?,
    beatsUserEdited: Boolean,
    /** 已存的亲笔走向（预填栏 A）；哨兵态与未答态一律 null =「准创建」：预填空、无 tag、无撤回。 */
    savedDirection: String?,
    /** 末章 `userChoice` 是否非空——**决定保存路由**（true = 覆盖写口，含 [savedDirection] 为 null 的哨兵态）。 */
    directionCommitted: Boolean,
    onSubmitFlow: (String) -> Unit,
    onOverwriteDirection: (String) -> Unit,
    onWithdrawDirection: () -> Unit,
    onSaveBeats: (String) -> Unit,
    onRestoreAiBeats: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialBeats = remember(beats) { beats.orEmpty() }
    var flowText by remember { mutableStateOf(savedDirection.orEmpty()) }
    var beatsText by remember { mutableStateOf(initialBeats) }
    // 防重入（卷二 StoryFieldEditorViewModel 同款）：写口是 fire-and-forget，连点会发两遍。
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmWithdraw by remember { mutableStateOf(false) }

    // ⚠️ 三个 dirty 判据一律写成**函数**、点到才算，绝不缓存成捕获值（装机实测踩坑·图纸 §11 D-7）：
    // 局部 fun 的函数引用（`::save`）在重组之间被 Compose 判为「参数没变」→ 整个按钮被跳过更新 →
    // 里面捕获的 `val beatsDirty` 永远停在首帧的 false，用户改了字照样一个字节都不写库（静默失效）。
    // 写成函数后读的是 MutableState 的当下值，与是谁持有这个闭包无关。
    // 编辑模式下「非空」还不够：与已存走向逐字相同 = 没改，纯关面板不写库（清空则交撤回按钮，见 hint）。
    fun flowDirty() = flowText.isNotBlank() && (savedDirection == null || flowText.trim() != savedDirection.trim())
    fun beatsDirty() = beatsText.trim() != initialBeats.trim()
    fun dirty() = flowDirty() || beatsDirty()

    /** 保存分派（§4.4）：哪栏变了发哪条；栏 A 的**路由**看 [directionCommitted]——已答覆盖直写，没答走创建路。 */
    fun save() {
        if (saving) return
        saving = true
        if (flowDirty()) {
            if (directionCommitted) onOverwriteDirection(flowText.trim()) else onSubmitFlow(flowText.trim())
        }
        if (beatsDirty()) onSaveBeats(beatsText)
        onDismiss()
    }

    AppSheet(
        onDismissRequest = { if (dirty()) confirmDiscard = true else onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.story_director_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.text.primary,
            )
            Text(
                stringResource(R.string.story_director_sub),
                fontSize = 13.sp,
                color = colors.text.secondary,
            )

            // 栏 A · 剧情走向（非空即最高优先走向）。已存走向时 label 行挂「已保存 · 待生成」tag（照栏 B 镜像）。
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.story_director_flow_label), fontSize = 13.sp, color = colors.text.secondary)
                    if (savedDirection != null) {
                        HubTagChip(stringResource(R.string.story_continue_direction_tag), highlighted = true)
                    }
                }
                AppTextArea(
                    value = flowText,
                    onValueChange = { flowText = it },
                    placeholder = stringResource(R.string.story_director_flow_hint),
                    minHeight = 96.dp,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 清空栏 A 保存 ≠ 撤回（那只是「没改」）——把取消走向的正确出口指出来。
                if (savedDirection != null) {
                    Text(
                        stringResource(R.string.story_director_flow_saved_hint),
                        fontSize = 10.5.sp,
                        color = colors.text.tertiary,
                    )
                }
            }

            // 栏 B · 本章节拍（底稿 = AI 预排；改过即最高优先·徽标复用书页档案卡②同两个谓词）。
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.story_director_beats_label), fontSize = 13.sp, color = colors.text.secondary)
                    HubTagChip(
                        stringResource(
                            if (beatsUserEdited) R.string.story_hub_tag_user_edited else R.string.story_hub_tag_ai_planned,
                        ),
                        highlighted = beatsUserEdited,
                    )
                }
                AppTextArea(
                    value = beatsText,
                    onValueChange = { beatsText = it },
                    placeholder = stringResource(R.string.story_director_beats_hint),
                    minHeight = 120.dp,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.story_director_beats_hint2),
                    fontSize = 10.5.sp,
                    color = colors.text.tertiary,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // 撤回走向（只在真有已存走向时出现·哨兵态不给：撤 SKIP 会造出「选择重开 + 结局意图仍挂」的幽灵组合）。
                // 破坏性动作走 quiet 档不占主 CTA（房规 PITFALLS 1d）。
                if (savedDirection != null) {
                    AppButton(
                        onClick = { if (!saving) confirmWithdraw = true },
                        style = AppButtonStyle.Text,
                        enabled = !saving,
                    ) { Text(stringResource(R.string.story_director_withdraw)) }
                }
                // 只在「已由你修改」时出现：没改过的书按它等于原地踏步，摆出来只会让人以为自己改坏了什么。
                if (beatsUserEdited) {
                    AppButton(
                        onClick = { if (!saving) { saving = true; onRestoreAiBeats(); onDismiss() } },
                        style = AppButtonStyle.Tonal,
                        enabled = !saving,
                    ) { Text(stringResource(R.string.story_director_restore_ai)) }
                }
                Spacer(Modifier.weight(1f))
                AppButton(onClick = { save() }, style = AppButtonStyle.Primary, enabled = !saving) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    // dirty 返回确认（§4.4·复用卷二统一编辑页的三条词条，不新增）：放弃则一个字节都不写库。
    // 「继续编辑」要把已被拖走的 sheet 重新升起来——ModalBottomSheet 在 onDismissRequest 时已自行隐去。
    if (confirmDiscard) {
        val keepEditing = { confirmDiscard = false; scope.launch { sheetState.show() }; Unit }
        AppDialog(
            onDismissRequest = keepEditing,
            title = stringResource(R.string.story_field_discard_title),
            confirmText = stringResource(R.string.story_field_discard_yes),
            onConfirm = { confirmDiscard = false; onDismiss() },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.story_field_discard_no),
            onDismiss = keepEditing,
        )
    }

    // 撤回确认（§4.4）：一点即永久删掉手写文本、无从找回 → 照 dirty 弃改确认同族加一道 Danger 闸。
    // 「继续编辑」同样要把已被拖走的 sheet 重新升起来（逐字照上面的 keepEditing 姿势）。
    if (confirmWithdraw) {
        val keepEditing = { confirmWithdraw = false; scope.launch { sheetState.show() }; Unit }
        AppDialog(
            onDismissRequest = keepEditing,
            title = stringResource(R.string.story_director_withdraw_title),
            body = stringResource(R.string.story_director_withdraw_body),
            confirmText = stringResource(R.string.story_director_withdraw_confirm),
            onConfirm = { confirmWithdraw = false; saving = true; onWithdrawDirection(); onDismiss() },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.story_field_discard_no),
            onDismiss = keepEditing,
        )
    }
}
