package com.situ.aichat.sticker

/**
 * 32 个内置表情包目录（1:1 iOS `BuiltInStickerCatalog`, Models/StickerTypes.swift:102-181）。
 * 图片资源打包在 `assets/stickers/<id>.<ext>`（文件名 = 中文 ID）。
 *
 * - [byId]: **全集**（含被用户隐藏的），O(1) 查找；用于历史消息渲染、有效性校验。
 * - [enabled]: 过滤掉用户隐藏的，用于 prompt 注入 / 选择器 / 管理页「已启用」。
 *   Android 适配：iOS `enabled` 直接读全局 `UserDefaults`；这里把隐藏集合作参数传入（SharedPreferences
 *   需 Context，调用方从 [DisabledBuiltInStickerStore] 读后传入），保持纯函数可测、与 iOS 行为一致。
 *
 * 32 个里：1 个 GIF（谁怕谁_1），2 个 jpg（大哭_1 / 大哭_2），其余 29 个 png。
 */
object BuiltInStickerCatalog {

    val all: List<StickerInfo> = listOf(
        // 开心类
        StickerInfo("开心_1", "开心", "超级开心、兴奋、调皮捣蛋、嘻嘻", false, true, "png"),
        StickerInfo("大笑_1", "大笑", "哈哈大笑、太好笑了、开怀大笑", false, true, "png"),
        StickerInfo("winking_1", "眨眼", "俏皮眨眼、逗你玩、暗示、撩一下", false, true, "png"),
        // 喜爱类
        StickerInfo("喜欢_1", "喜欢", "喜欢你、爱你、被暖到了、满满的爱意", false, true, "png"),
        StickerInfo("亲亲_1", "亲亲", "亲亲、么么哒、爱你哦、撒娇求亲", false, true, "png"),
        StickerInfo("融化_1", "融化", "心都化了、太甜了、被感动到融化、受不了的可爱", false, true, "png"),
        StickerInfo("害羞_1", "害羞", "害羞、不好意思、被夸了脸红、羞涩的开心", false, true, "png"),
        // 好奇类
        StickerInfo("捂眼看_1", "捂眼看", "不敢看但又忍不住偷看、害羞好奇、又怕又想知道", false, true, "png"),
        // 难过类
        StickerInfo("大哭_1", "大哭", "伤心大哭、好难过、哭得停不下来", false, true, "jpg"),
        StickerInfo("大哭_2", "大哭NO", "不要啊、求求了、委屈地哭、拒绝同时很伤心", false, true, "jpg"),
        StickerInfo("大哭_3", "嚎啕大哭", "嚎啕大哭、崩溃了、太难过了、哭死我了", false, true, "png"),
        StickerInfo("要哭了_1", "要哭了", "快哭了但忍住、委屈、强颜欢笑、眼泪在打转", false, true, "png"),
        StickerInfo("难过_1", "难过", "难过、求求你了、可怜、楚楚可怜地撒娇", false, true, "png"),
        // 无奈/搞笑类
        StickerInfo("无奈_1", "无奈", "无奈、心累、受不了了、无语叹气、算了吧", false, true, "png"),
        StickerInfo("笑哭_1", "笑哭", "笑死我了、太离谱了、笑到流泪、不行了太好笑了", false, true, "png"),
        StickerInfo("小丑_1", "小丑", "我是小丑、自嘲、尴尬、丢人了、感觉自己像傻子", false, true, "png"),
        // 惊讶类
        StickerInfo("惊恐_1", "惊恐", "吓死了、恐惧、完蛋了、慌了、大事不好", false, true, "png"),
        // 拒绝类
        StickerInfo("不行_1", "不行", "不行、停、拒绝、别过来、打住", false, true, "png"),
        StickerInfo("摇头_1", "摇头", "不要、算了吧、表示否定、温柔地拒绝", false, true, "png"),
        // 安静类
        StickerInfo("不说_1", "不说", "我什么都没说、保守秘密、不小心说漏嘴、装作不知道", false, true, "png"),
        StickerInfo("嘘_1", "嘘", "嘘、小声点、这是秘密、别告诉别人", false, true, "png"),
        StickerInfo("闭嘴_1", "闭嘴", "闭嘴、不说了、打死也不说、管住嘴", false, true, "png"),
        // 困倦类
        StickerInfo("睡觉_1", "睡觉", "困了、要睡了、晚安、犯困", false, true, "png"),
        StickerInfo("睡着了_1", "睡着了", "已经睡着了、呼呼大睡、不在线了", false, true, "png"),
        // 搞怪类
        StickerInfo("大便_1", "大便", "搞怪、恶趣味、真是一坨屎、调侃", false, true, "png"),
        StickerInfo("放屁_1", "放屁", "放屁、打岔、胡说八道、臭臭的、搞笑", false, true, "png"),
        StickerInfo("装死_1", "装死", "装死、社死了、我死了、晕倒、受不了了", false, true, "png"),
        // 三猴类
        StickerInfo("瞎了_1", "瞎了", "辣眼睛、不忍直视、我瞎了、看到了不该看的", false, true, "png"),
        StickerInfo("听不见_1", "听不见", "听不见、我不听、lalala假装没听到、拒绝接受", false, true, "png"),
        // 特殊类
        StickerInfo("钱_1", "钱", "发财、好有钱、想要钱、眼里只有钱、买买买", false, true, "png"),
        StickerInfo("发烧_1", "发烧", "生病了、发烧不舒服、身体不好、需要关心", false, true, "png"),
        StickerInfo("谁怕谁_1", "谁怕谁", "不怕你、来战、挑衅、互不相让、谁怕谁啊", true, true, "gif"),
    )

    /** 全集字典（含已隐藏），用于历史消息渲染与有效性校验。 */
    val byId: Map<String, StickerInfo> = all.associateBy { it.id }

    /** 当前启用的内置表情（过滤掉 [disabled] 中的）。disabled 空时直接返回全集。 */
    fun enabled(disabled: Set<String>): List<StickerInfo> =
        if (disabled.isEmpty()) all else all.filter { it.id !in disabled }

    /**
     * 逻辑 ID（中文）→ 物理资源 ASCII 基名（拼音）。中文文件名打进 APK 后 zip 条目未置 UTF-8 标志，
     * 跨设备 `AssetManager` 查找不稳，故物理文件统一用纯 ASCII；逻辑 ID 全程保持中文
     * （prompt/标记/DB/渲染查表都用中文 ID，仅磁盘文件名 ASCII）。
     */
    private val assetBaseNames: Map<String, String> = mapOf(
        "开心_1" to "kaixin_1",
        "大笑_1" to "daxiao_1",
        "winking_1" to "winking_1",
        "喜欢_1" to "xihuan_1",
        "亲亲_1" to "qinqin_1",
        "融化_1" to "ronghua_1",
        "害羞_1" to "haixiu_1",
        "捂眼看_1" to "wuyankan_1",
        "大哭_1" to "daku_1",
        "大哭_2" to "daku_2",
        "大哭_3" to "daku_3",
        "要哭了_1" to "yaokule_1",
        "难过_1" to "nanguo_1",
        "无奈_1" to "wunai_1",
        "笑哭_1" to "xiaoku_1",
        "小丑_1" to "xiaochou_1",
        "惊恐_1" to "jingkong_1",
        "不行_1" to "buxing_1",
        "摇头_1" to "yaotou_1",
        "不说_1" to "bushuo_1",
        "嘘_1" to "xu_1",
        "闭嘴_1" to "bizui_1",
        "睡觉_1" to "shuijiao_1",
        "睡着了_1" to "shuizhaole_1",
        "大便_1" to "dabian_1",
        "放屁_1" to "fangpi_1",
        "装死_1" to "zhuangsi_1",
        "瞎了_1" to "xiale_1",
        "听不见_1" to "tingbujian_1",
        "钱_1" to "qian_1",
        "发烧_1" to "fashao_1",
        "谁怕谁_1" to "sheipashei_1",
    )

    /** 内置 sticker 的 asset 相对路径 `stickers/<ascii>.<ext>`；未知 id 返回 null。 */
    fun assetPath(id: String): String? {
        val info = byId[id] ?: return null
        val base = assetBaseNames[id] ?: return null
        return "stickers/$base.${info.fileExtension}"
    }
}
