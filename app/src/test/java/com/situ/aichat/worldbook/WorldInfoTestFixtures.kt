package com.situ.aichat.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.local.entity.WorldBookTimedStateEntity
import com.situ.aichat.data.worldbook.encodeStringList

/** WB3 引擎测试共用构造器（同包顶层，供三个测试文件复用）。 */

internal fun wbBook(
    uuid: String = "b1",
    name: String = "书-$uuid",
    global: Boolean = false,
    enabled: Boolean = true,
) = WorldBookEntity(uuid = uuid, name = name, isGlobal = global, enabled = enabled)

internal fun wbEntry(
    uuid: String,
    book: String = "b1",
    keys: List<String> = emptyList(),
    secondary: List<String> = emptyList(),
    logic: Int = 0,
    content: String = "内容-$uuid",
    comment: String = uuid,
    constant: Boolean = false,
    vectorized: Boolean = false,
    selective: Boolean = true,
    enabled: Boolean = true,
    order: Int = 100,
    position: Int = 1,
    depth: Int = 4,
    role: Int = 0,
    probability: Int = 100,
    useProbability: Boolean = true,
    scanDepth: Int? = null,
    caseSensitive: Boolean? = null,
    matchWholeWords: Boolean? = null,
    excludeRecursion: Boolean = false,
    preventRecursion: Boolean = false,
    delayUntilRecursion: Int = 0,
    groupName: String = "",
    groupOverride: Boolean = false,
    groupWeight: Int = 100,
    useGroupScoring: Boolean? = null,
    sticky: Int? = null,
    cooldown: Int? = null,
    delay: Int? = null,
    ignoreBudget: Boolean = false,
) = WorldBookEntryEntity(
    uuid = uuid,
    bookUuid = book,
    keysJson = encodeStringList(keys),
    secondaryKeysJson = encodeStringList(secondary),
    selectiveLogic = logic,
    content = content,
    comment = comment,
    constant = constant,
    vectorized = vectorized,
    selective = selective,
    enabled = enabled,
    insertionOrder = order,
    position = position,
    depth = depth,
    role = role,
    probability = probability,
    useProbability = useProbability,
    scanDepth = scanDepth,
    caseSensitive = caseSensitive,
    matchWholeWords = matchWholeWords,
    excludeRecursion = excludeRecursion,
    preventRecursion = preventRecursion,
    delayUntilRecursion = delayUntilRecursion,
    groupName = groupName,
    groupOverride = groupOverride,
    groupWeight = groupWeight,
    useGroupScoring = useGroupScoring,
    sticky = sticky,
    cooldown = cooldown,
    delay = delay,
    ignoreBudget = ignoreBudget,
)

internal fun wbInput(
    entries: List<WorldBookEntryEntity>,
    messages: List<String>,
    books: List<WorldBookEntity> = listOf(wbBook()),
    settings: WorldInfoSettings = WorldInfoSettings(),
    messageCount: Int = messages.size,
    timed: List<WorldBookTimedStateEntity> = emptyList(),
    vector: Set<String> = emptySet(),
) = WorldInfoActivationInput(
    books = books,
    entries = entries,
    messages = messages.map { ScanMessage(it) },
    conversationMessageCount = messageCount,
    conversationUuid = "conv1",
    timedStates = timed,
    vectorMatchedEntryUuids = vector,
    settings = settings,
)

/** 恒返回 [value]（钳到界内）的随机源——概率/抽签的确定性测试。 */
internal fun rngConst(value: Int) = WorldInfoRng { bound -> minOf(value, bound - 1) }

internal val RNG_ZERO = rngConst(0)

internal fun activate(input: WorldInfoActivationInput, rng: WorldInfoRng = RNG_ZERO) =
    WorldInfoActivator(rng).activate(input)

internal fun WorldInfoActivationResult.activatedTitles(): Set<String> =
    diagnostics.activated.map { it.title }.toSet()

internal fun WorldInfoActivationResult.droppedTitles(): Set<String> =
    diagnostics.droppedByBudget.map { it.title }.toSet()

internal fun WorldInfoActivationResult.allText(): String =
    listOf(before, after, suffix).joinToString("\n") + atDepth.joinToString("\n") { it.content }
