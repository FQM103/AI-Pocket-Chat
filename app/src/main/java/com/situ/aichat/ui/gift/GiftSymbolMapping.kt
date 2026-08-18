package com.situ.aichat.ui.gift

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EmojiFoodBeverage
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KingBed
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RiceBowl
import androidx.compose.material.icons.filled.SetMeal
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Toys
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * SF Symbol → Material Icon 映射（9.2d d-1）。
 *
 * iOS `GiftItem.fallbackSymbol` / `GiftCategory.iconSymbol` 用 SF Symbol 名（如 "fork.knife"），安卓没有 SF Symbol，
 * 改用 material-icons-extended 的等价 `ImageVector`。**这是图片缺失/DIY 时的兜底图标**——46 张礼物图已全部入包
 * （`assets/giftimages/` 下 46 个 .jpg），故目录项几乎不会落到兜底；映射主要服务 DIY（paintbrush）与极端缺资源场景，
 * 以及 d-3 的品类 Tab 图标。视觉求「地道 Material 近似」，非像素仿 SF Symbol。
 *
 * 弃用图标必须用 `Icons.AutoMirrored.*`（否则卡 0 警告门）——本表刻意只选**非方向性**图标，无一在
 * AutoMirrored 迁移名单内，规避弃用警告。
 *
 * 完整性由 `GiftSymbolMappingTest` 守卫：每个 catalog `fallbackSymbol` + 每个 category `iconSymbol` + DIY 三符号
 * 都必须在 [mappedSymbols] 内，未映射会被单测抓出（不靠肉眼）。
 */
object GiftSymbolMapping {

    /** 终极兜底（iOS `gift.fill`）：未知符号 → 礼物盒图标。 */
    val ultimateFallback: ImageVector = Icons.Filled.CardGiftcard

    private val map: Map<String, ImageVector> = mapOf(
        // —— 食物（10）——
        "bowl.of.rice" to Icons.Filled.RiceBowl,                 // 关东煮
        "cup.and.saucer" to Icons.Filled.LocalCafe,              // 珍珠奶茶
        "cup.and.saucer.fill" to Icons.Filled.Coffee,            // 手冲咖啡
        "birthday.cake" to Icons.Filled.Cake,                    // 小蛋糕
        "leaf" to Icons.Filled.Eco,                              // 水果切盘
        "circle.grid.3x3.fill" to Icons.Filled.Apps,            // 法式马卡龙
        "fish" to Icons.Filled.SetMeal,                          // 寿司拼盘
        "flame.fill" to Icons.Filled.LocalFireDepartment,       // 火锅 / 香薰蜡烛
        "fork.knife" to Icons.Filled.Restaurant,                 // 高级牛排 / 食物品类
        "star.circle.fill" to Icons.Filled.Stars,                // 米其林大餐

        // —— 花束（7）——
        "camera.macro" to Icons.Filled.LocalFlorist,             // 玫瑰/郁金香/花束 / 花束品类
        "camera.macro.circle" to Icons.Filled.LocalFlorist,      // 小雏菊
        "sparkles" to Icons.Filled.AutoAwesome,                  // 满天星 / 饰品品类
        "sun.max.fill" to Icons.Filled.WbSunny,                  // 向日葵
        "heart.circle.fill" to Icons.Filled.Favorite,            // 99 朵玫瑰

        // —— 饰品（7）——
        "circle.hexagongrid" to Icons.Filled.Hexagon,           // 发夹
        "infinity" to Icons.Filled.AllInclusive,                 // 情侣手绳
        "circle.circle" to Icons.Filled.Circle,                  // 手链
        "drop" to Icons.Filled.WaterDrop,                        // 耳环
        "heart" to Icons.Filled.FavoriteBorder,                  // 项链
        "sunglasses" to Icons.Filled.Visibility,                 // 墨镜
        "diamond.fill" to Icons.Filled.Diamond,                  // 钻戒

        // —— 日用品（8）——
        "flame" to Icons.Filled.Whatshot,                        // 暖手宝
        "shoe" to Icons.Filled.Checkroom,                        // 毛绒拖鞋
        "teddybear" to Icons.Filled.Toys,                        // 小熊玩偶
        "mug" to Icons.Filled.EmojiFoodBeverage,                 // 保温杯
        "mug.fill" to Icons.Filled.EmojiFoodBeverage,            // 情侣马克杯
        "bed.double" to Icons.Filled.KingBed,                    // 抱枕
        "scribble.variable" to Icons.Filled.Gesture,             // 围巾

        // —— 奢侈品（5）——
        "lips" to Icons.Filled.Face,                             // 口红
        "drop.circle.fill" to Icons.Filled.Opacity,              // 香水
        "square.fill.on.circle.fill" to Icons.Filled.Layers,     // 真丝围巾
        "sparkles.square.filled.on.square" to Icons.Filled.AutoAwesomeMotion, // 高端护肤套装
        "handbag.fill" to Icons.Filled.ShoppingBag,              // 名牌手袋

        // —— 体验券（5）——
        "film" to Icons.Filled.Movie,                            // 一起看电影
        "mic.fill" to Icons.Filled.Mic,                          // KTV
        "wineglass" to Icons.Filled.WineBar,                     // 约会晚餐
        "leaf.circle" to Icons.Filled.Spa,                       // 按摩 SPA
        "airplane" to Icons.Filled.Flight,                       // 周末出游

        // —— 手作卡片（4）——
        "note.text" to Icons.AutoMirrored.Filled.StickyNote2,    // 手写便签
        "envelope" to Icons.Filled.MailOutline,                  // 手写明信片
        "signature" to Icons.Filled.Gesture,                     // 纸鹤
        "envelope.fill" to Icons.Filled.Email,                   // 手写情书

        // —— 品类 Tab 专用（d-3）——
        "house" to Icons.Filled.Home,                            // 日用品品类
        "crown" to Icons.Filled.WorkspacePremium,                // 奢侈品品类
        "ticket" to Icons.Filled.ConfirmationNumber,             // 体验品类
        "paintbrush" to Icons.Filled.Brush,                      // 手作品类

        // —— DIY 兜底符号 ——
        "paintbrush.fill" to Icons.Filled.Brush,                 // GiftImageView(record:) 对 DIY 用
        "paintbrush.pointed.fill" to Icons.Filled.Brush,         // DIY 入口卡大图标
        "heart.text.square" to Icons.Filled.Favorite,            // makeUserDIY 的 fallbackSymbol
    )

    /** 已映射符号集（单测断言完整性用）。 */
    val mappedSymbols: Set<String> get() = map.keys

    /** SF Symbol 名 → Material `ImageVector`，未知符号回退 [ultimateFallback]（CardGiftcard）。 */
    fun materialIcon(sfSymbol: String): ImageVector = map[sfSymbol] ?: ultimateFallback
}
