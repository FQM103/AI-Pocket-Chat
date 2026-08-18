package com.situ.aichat.economy

import android.util.Log
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import java.time.Instant
import java.time.Period
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 角色月薪推断（1:1 iOS `Services/CharacterSalaryInferenceService.swift`）。按角色身份（职业/性别/年龄/外貌/背景/
 * 性格/systemPrompt 截 500）调 LLM 推断合理月薪 → clamp[0,50000] → 写回 CharacterWallet（monthlySalary +
 * salaryInferred=true）。temperature 0.3 保证同角色多次推断稳定；LLM 失败静默（salaryInferred 保持 false，下次重试）。
 *
 * 复用 CHAT 路由（同 iOS：`APIFunctionRouter.assignedConfigID(.chat)` + fallback），config 由调用方解析后传入。
 * DeepSeek json_object 空响应等 200ms 重试 1 次（对齐项目其它后台分析服务）。
 */
@Singleton
class CharacterSalaryInferenceService @Inject constructor(
    private val contextLog: ContextLogService,
    private val currencyService: CurrencyService,
) {

    /** 推断月薪，clamp[0,50000]；任何失败返回 null（不抛）。 */
    suspend fun inferMonthlySalary(character: CharacterEntity, config: ApiConfigValues): Int? {
        val (system, user) = buildSalaryPrompt(character)
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = user),
        )
        var response = ""
        for (attempt in 1..2) {
            val buffer = try {
                contextLog.completion(
                    source = LogSource.SALARY_INFERENCE,
                    characterName = character.name,
                    config = config,
                    messages = messages,
                    temperature = 0.3,
                    responseFormat = ResponseFormatDto(type = "json_object"),
                )
            } catch (_: Exception) {
                return null
            }
            val candidate = MemoryService.strippingThinkingTags(buffer)
            if (candidate.isNotEmpty()) { response = candidate; break }
            if (attempt < 2) delay(200)
        }
        if (response.isEmpty()) {
            Log.w(TAG, "月薪推断·LLM 空响应 char=${character.name}")
            return null
        }
        val salary = parseSalary(response) ?: run {
            Log.w(TAG, "月薪推断·解析失败 char=${character.name} resp=${response.take(120)}")
            return null
        }
        return clampSalary(salary)
    }

    /** 推断并写回钱包（monthlySalary + salaryInferred=true）；失败静默保持原状（下次启动重试）。 */
    suspend fun inferAndWriteBack(character: CharacterEntity, config: ApiConfigValues, now: Long = System.currentTimeMillis()) {
        val salary = inferMonthlySalary(character, config) ?: return
        currencyService.setCharacterSalary(character.uuid, salary, now)
    }

    private companion object {
        const val TAG = "SalaryInference"
    }
}

// ── 纯函数（internal，便于单测；prompt 逐字对齐 iOS） ──────────────────────

const val SALARY_CEILING = 50000
const val SALARY_FLOOR = 0

private val salaryJson = Json { ignoreUnknownKeys = true; isLenient = true }

private val SALARY_SYSTEM_PROMPT = """
    你是一个角色经济设定助手。根据提供的角色信息推断 TA 的合理月薪(以虚拟金币为单位,可参考人民币等价),用于虚拟陪伴 app 的经济系统。

    ## 月薪档位锚点
    - 学生/实习生: 300-800
    - 普通白领/服务业(咖啡师/店员/老师等): 3000-8000
    - 高收入职业(高级工程师/医生/律师/管理层): 10000-18000
    - 富豪/精英(创始人/明星/富二代): 20000-35000
    - 极端富豪(亿万富翁/宇宙帝王等奇幻设定): 封顶 50000
    - 完全无收入(乞丐/流浪汉/完全依赖家人): 0

    ## 判断原则
    1. 职业是首要依据("程序员"默认 8000-10000,"咖啡师"3500-5000 等)
    2. 年龄修正(20 岁程序员可能实习 5000,35 岁资深程序员 15000+)
    3. 附加描述修正("程序员大厂P7" → 18000,"程序员小公司" → 6000-8000)
    4. 奇幻/非现实设定(吸血鬼/魔法师/时间旅行者等)按"TA 在现代社会大致对应的经济档位"推断
    5. 数值必须在 [0, 50000] 范围内

    ## 输出格式
    严格以 JSON 输出,不要任何其他文字、不要 markdown 代码块:
    {"monthlySalary": <整数>}
""".trimIndent()

/** 构建 system(锚点表，逐字 iOS) + user(角色描述) prompt。 */
internal fun buildSalaryPrompt(
    character: CharacterEntity,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): Pair<String, String> = SALARY_SYSTEM_PROMPT to buildCharacterDescription(character, now, zone)

/** 把角色身份信息拼成描述串喂 LLM（1:1 iOS buildCharacterDescription；半角冒号）。 */
internal fun buildCharacterDescription(
    character: CharacterEntity,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val lines = ArrayList<String>()
    lines.add("角色名:${character.name}")
    if (character.occupation.isNotEmpty()) lines.add("职业/身份:${character.occupation}")
    if (character.gender.isNotEmpty()) lines.add("性别:${character.gender}")
    // 年龄:固定年龄优先,无则根据生日推算
    if (character.ageModeRaw == "fixed" && character.fixedAge > 0) {
        lines.add("年龄:${character.fixedAge} 岁")
    } else {
        character.birthday?.let { bd ->
            val years = Period.between(
                Instant.ofEpochMilli(bd).atZone(zone).toLocalDate(),
                Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
            ).years
            if (years > 0) lines.add("年龄:$years 岁(按生日推算)")
        }
    }
    if (character.appearanceDescription.isNotEmpty()) lines.add("外貌:${character.appearanceDescription}")
    if (character.backstory.isNotEmpty()) lines.add("背景:${character.backstory}")
    if (character.personalityDescription.isNotEmpty()) lines.add("性格:${character.personalityDescription}")
    if (character.systemPrompt.isNotEmpty()) lines.add("补充设定:${character.systemPrompt.take(500)}")
    lines.add("")
    lines.add("请根据以上信息推断这个角色合理的月薪。")
    return lines.joinToString("\n")
}

/** 解析 LLM JSON 取 monthlySalary（1:1 iOS parseSalary：strip think → JSONExtractor 三层兜底 → Int 或 Double）。 */
internal fun parseSalary(response: String): Int? {
    val cleaned = MemoryService.strippingThinkingTags(response)
    val extracted = JSONExtractor.extract(cleaned)
    val candidates = if (extracted == cleaned) listOf(cleaned) else listOf(extracted, cleaned)
    for (candidate in candidates) {
        val prim = runCatching {
            salaryJson.parseToJsonElement(candidate).jsonObject["monthlySalary"]?.jsonPrimitive
        }.getOrNull() ?: continue
        prim.intOrNull?.let { return it }
        prim.doubleOrNull?.let { return it.toInt() }
    }
    return null
}

/** clamp 到 [0, 50000]（1:1 iOS clamp）。 */
internal fun clampSalary(value: Int): Int = value.coerceIn(SALARY_FLOOR, SALARY_CEILING)

/** 解析手动月薪输入（14.6b·1:1 iOS `Int(salaryText.trimmed) ?? 0` 再 clamp）：非数字/空 → 0，再 clamp[0,50000]。 */
internal fun parseSalaryInput(text: String): Int = clampSalary(text.trim().toIntOrNull() ?: 0)
