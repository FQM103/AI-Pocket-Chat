package com.situ.aichat.pet

/**
 * 宠物用品目录与类型（1:1 iOS `Models/PetItemTypes.swift`）。
 *
 * 设计哲学同 `GiftCatalog`：清单是产品决定的**静态代码数据**（非 Room 行），随 app 版本更新；购买记录只存
 * `itemId` 字符串（落在 [PetMetadata.petInventory]），用 [PetItemCatalog.find] 查回详情，找不到时调用方兜底。
 *
 * Android 适配：iOS `PetItem` 带 `Codable`（其实只为 `targetSpecies` 自动合成）；这里是静态数据、从不持久化，
 * 故用普通 data class。iOS 的 `fallbackSymbol` 是 SF Symbol 字符串——保留原值供商店 UI 映射 Material 图标
 * （同 `GiftSymbolMapping`）。iOS 的 `visualAssetName`（首期恒 nil 预留）未携带——装扮叠图按 itemId 路由
 * （见 `PetCostumeOverlay`），无需此字段。
 */

/** 宠物用品分类（商店 Tab 单位），1:1 iOS `PetItemCategory`。首期仅 food/costume 两类。 */
enum class PetItemCategory(val raw: String, val displayName: String) {
    FOOD("food", "零食"),
    COSTUME("costume", "装扮");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): PetItemCategory = byRaw[raw] ?: FOOD
    }
}

/**
 * 物品性质（决定购买/使用分支），1:1 iOS `PetItemKind`。
 * - [CONSUMABLE]：消耗品，使用后扣 1 份，可重复购买多次。
 * - [EQUIPPABLE]：装扮，永久拥有，可反复佩戴/摘下，**不可重复购买**。
 */
enum class PetItemKind(val raw: String) {
    CONSUMABLE("consumable"),
    EQUIPPABLE("equippable");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): PetItemKind = byRaw[raw] ?: CONSUMABLE
    }
}

/**
 * 消耗品使用后对宠物状态值的增量（应用时各维 clamp 0-100），1:1 iOS `PetStatBoosts`。
 * 语义：[hunger] **负值=喂饱**（降低饥饿）；[cleanliness]/[happiness]/[health] 正值提升。只填需改动的维度，其余 null。
 */
data class PetStatBoosts(
    val hunger: Int? = null,
    val cleanliness: Int? = null,
    val happiness: Int? = null,
    val health: Int? = null,
)

/**
 * 宠物用品目录条目（静态数据），1:1 iOS `PetItem`。
 *
 * @property id 稳定 ID（如 `pet_food_biscuit`/`pet_costume_bowtie`），也是库存键与装扮叠图键。
 * @property statBoosts 消耗品使用后的数值加成（仅 [PetItemKind.CONSUMABLE] 有，装扮为 null）。
 * @property targetSpecies 适用物种（`null` = 全物种通用）。
 * @property fallbackSymbol iOS SF Symbol 占位图名（商店 UI 映射 Material 图标用）。
 * @property isSignature 是否「重磅款」（商店列表加粗 + 情感权重留口子）。
 */
data class PetItem(
    val id: String,
    val name: String,
    val subtitle: String,
    val price: Int,
    val category: PetItemCategory,
    val kind: PetItemKind,
    val statBoosts: PetStatBoosts? = null,
    val targetSpecies: List<PetSpecies>? = null,
    val fallbackSymbol: String,
    val isSignature: Boolean = false,
) {
    /** 该物品是否适用于指定物种（1:1 iOS `isAvailable(for:)`）。`targetSpecies==null` → 全物种可用。 */
    fun isAvailable(species: PetSpecies): Boolean = targetSpecies?.contains(species) ?: true
}

/**
 * 静态宠物用品目录（首期 10 件：零食 6 + 装扮 4），1:1 iOS `PetItemCatalog`。价格/加成/物种**精确照搬**。
 * 价格定位：零食 20-80（轻消耗），装扮 150-500（有稀缺感）。
 */
object PetItemCatalog {

    /**
     * 贵价购买阈值（金币）：单次购买 ≥ 此值才写入 [PetMetadata.recentExpensivePurchases]，触发朋友圈/日记联动
     * （Q33 拍板）。1:1 iOS `PetShopMomentQueueService.priceThreshold = 300`。放目录层作单一真相源，
     * 供 `PetShopService`（写侧）与 `PetShopMomentQueueService`（读侧）共用，避免重复常量。
     */
    const val EXPENSIVE_PURCHASE_THRESHOLD: Int = 300

    /** 通过 ID 查找物品（1:1 iOS `find(id:)`）。 */
    fun find(id: String): PetItem? = allItems.firstOrNull { it.id == id }

    /** 按分类过滤（1:1 iOS `items(in:)`）。 */
    fun items(category: PetItemCategory): List<PetItem> = allItems.filter { it.category == category }

    /** 按物种过滤（只返回该物种可用的，1:1 iOS `items(for:)`）。 */
    fun items(species: PetSpecies): List<PetItem> = allItems.filter { it.isAvailable(species) }

    /** 全部物品（首期 10 件）。 */
    val allItems: List<PetItem> = listOf(
        // ── 零食（6 件 · 20-80 金币）──
        PetItem(
            id = "pet_food_biscuit",
            name = "小饼干",
            subtitle = "香脆入门款，谁都爱",
            price = 20,
            category = PetItemCategory.FOOD,
            kind = PetItemKind.CONSUMABLE,
            statBoosts = PetStatBoosts(hunger = -15, happiness = 3),
            targetSpecies = null,
            fallbackSymbol = "birthday.cake",
            isSignature = false,
        ),
        PetItem(
            id = "pet_food_carrot",
            name = "水灵胡萝卜",
            subtitle = "兔子的心头好，新鲜蔬菜",
            price = 25,
            category = PetItemCategory.FOOD,
            kind = PetItemKind.CONSUMABLE,
            statBoosts = PetStatBoosts(hunger = -15, happiness = 8),
            targetSpecies = listOf(PetSpecies.RABBIT),
            fallbackSymbol = "carrot.fill",
            isSignature = false,
        ),
        PetItem(
            id = "pet_food_seeds",
            name = "坚果种子",
            subtitle = "仓鼠专属的营养小零嘴",
            price = 25,
            category = PetItemCategory.FOOD,
            kind = PetItemKind.CONSUMABLE,
            statBoosts = PetStatBoosts(hunger = -15, happiness = 8),
            targetSpecies = listOf(PetSpecies.HAMSTER),
            fallbackSymbol = "leaf.fill",
            isSignature = false,
        ),
        PetItem(
            id = "pet_food_cat_can",
            name = "猫咪罐头",
            subtitle = "肉香四溢，猫咪跟着跑",
            price = 35,
            category = PetItemCategory.FOOD,
            kind = PetItemKind.CONSUMABLE,
            statBoosts = PetStatBoosts(hunger = -20, happiness = 5),
            targetSpecies = listOf(PetSpecies.CAT),
            fallbackSymbol = "fish.fill",
            isSignature = false,
        ),
        PetItem(
            id = "pet_food_premium_can",
            name = "特级罐头",
            subtitle = "进口优选，全物种通吃",
            price = 60,
            category = PetItemCategory.FOOD,
            kind = PetItemKind.CONSUMABLE,
            statBoosts = PetStatBoosts(hunger = -30, happiness = 10, health = 3),
            targetSpecies = null,
            fallbackSymbol = "star.circle.fill",
            isSignature = true,
        ),
        PetItem(
            id = "pet_food_birthday_cake",
            name = "宠物生日蛋糕",
            subtitle = "小庆祝一下，超级开心",
            price = 80,
            category = PetItemCategory.FOOD,
            kind = PetItemKind.CONSUMABLE,
            statBoosts = PetStatBoosts(hunger = -25, happiness = 20, health = 5),
            targetSpecies = null,
            fallbackSymbol = "birthday.cake.fill",
            isSignature = true,
        ),

        // ── 装扮（4 件 · 150-500 金币）──
        PetItem(
            id = "pet_costume_bowtie",
            name = "小领结",
            subtitle = "红色蝴蝶结，绅士范儿",
            price = 150,
            category = PetItemCategory.COSTUME,
            kind = PetItemKind.EQUIPPABLE,
            statBoosts = null,
            targetSpecies = null,
            fallbackSymbol = "bolt.heart.fill",
            isSignature = false,
        ),
        PetItem(
            id = "pet_costume_scarf",
            name = "毛绒围巾",
            subtitle = "条纹款，保暖又可爱",
            price = 200,
            category = PetItemCategory.COSTUME,
            kind = PetItemKind.EQUIPPABLE,
            statBoosts = null,
            targetSpecies = null,
            fallbackSymbol = "scribble.variable",
            isSignature = false,
        ),
        PetItem(
            id = "pet_costume_crown",
            name = "金色小皇冠",
            subtitle = "宝石镶嵌，闪闪发光",
            price = 380,
            category = PetItemCategory.COSTUME,
            kind = PetItemKind.EQUIPPABLE,
            statBoosts = null,
            targetSpecies = null,
            fallbackSymbol = "crown.fill",
            isSignature = true,
        ),
        PetItem(
            id = "pet_costume_wings",
            name = "精灵翅膀",
            subtitle = "透明薄纱，梦幻飘动",
            price = 500,
            category = PetItemCategory.COSTUME,
            kind = PetItemKind.EQUIPPABLE,
            statBoosts = null,
            targetSpecies = null,
            fallbackSymbol = "sparkles",
            isSignature = true,
        ),
    )
}
