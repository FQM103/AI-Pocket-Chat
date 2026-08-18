package com.situ.aichat.world.atlas

/**
 * 十二处手写奇观（契约 §4「手写奇观 12 处」/ W3 图纸 §3.4·逐字照抄·图纸 §9 禁改）。
 *
 * `hint` = 发现前地图上的朦胧线索；`vignette` = 抵达时点亮的一小段场景文案（canon·无需 LLM 润色）。
 * 坐标在世界平面 4800 × 2600 内、贴各自大区放置（与 [WorldAtlas] 城网共坐标系）。
 * 奇观名一并占入取名器的「已用名」集合（§3.5·不被生成城撞名）。
 */
object WorldWonders {

    val ALL: List<WorldWonder> = listOf(
        WorldWonder(
            id = "wonder_aurora", name = "极光穹幕", regionId = "jibei", x = 2340, y = 180,
            hint = "雪原深处的夜空，据说会流动",
            vignette = "整片夜空像被谁掀开了一角，青绿的光幕无声地淌过雪原。站在这里的人都会不自觉放轻呼吸。",
        ),
        WorldWonder(
            id = "wonder_firefly", name = "萤川萤火林", regionId = "yingchuan", x = 1440, y = 1700,
            hint = "夏夜的河谷里有一条发光的川",
            vignette = "萤火从林间升起，顺着河谷汇成一条缓缓流动的光河。伸出手，光点会落在指尖停一秒再走。",
        ),
        WorldWonder(
            id = "wonder_lighthouse", name = "天涯老灯塔", regionId = "huangjiao", x = 4460, y = 2240,
            hint = "大洋尽头有一盏从不熄灭的灯",
            vignette = "风声里，老灯塔一圈圈扫过黑色的海面。守塔人说：灯亮着，走多远的人都记得回来的路。",
        ),
        WorldWonder(
            id = "wonder_mirrorlake", name = "镜湖", regionId = "yunze", x = 560, y = 1240,
            hint = "离家不远，有面「天空的镜子」",
            vignette = "无风的清晨，湖面平得像一块玻璃，云在脚下走。本地人说，第一次带朋友来这里的人，都会先看朋友的表情。",
        ),
        WorldWonder(
            id = "wonder_teaking", name = "千年茶王树", regionId = "chalong", x = 1930, y = 1060,
            hint = "茶田尽头有一棵被供起来的老树",
            vignette = "千年的茶王树要五人合抱，枝叶间挂满祈愿的红绳。清明采茶前，全大区的茶农都会来敬一杯新茶。",
        ),
        WorldWonder(
            id = "wonder_stonewind", name = "风语石林", regionId = "huangsha", x = 1160, y = 560,
            hint = "高原上有一片会「说话」的石头",
            vignette = "风穿过千百根风蚀石柱，发出低低的呜声，像很多人在远处交谈。据说每个人听到的话都不一样。",
        ),
        WorldWonder(
            id = "wonder_cloudfall", name = "暮山云瀑", regionId = "mushan", x = 2740, y = 760,
            hint = "黄昏时分，山脊会「流」下云来",
            vignette = "日落前后，云从主峰翻涌而下，像一条金红色的瀑布挂在山脊上，十几分钟后散尽——看到的人都说值得等。",
        ),
        WorldWonder(
            id = "wonder_glowtide", name = "夜光潮", regionId = "nanyu", x = 2460, y = 2350,
            hint = "某些夜晚，海水会自己发光",
            vignette = "浪涌上白沙，碎成一线幽蓝的光。赤脚踩进去，每一步都会亮一下，像走在星星上。",
        ),
        WorldWonder(
            id = "wonder_mossdome", name = "苔穹洞", regionId = "xiyulin", x = 310, y = 1950,
            hint = "雨林深处有一座「长满绿的天空」",
            vignette = "洞顶垂满发着微光的苔与蕨，水珠一滴一滴落进暗潭，声音清得像敲在心上。",
        ),
        WorldWonder(
            id = "wonder_starfall", name = "星落滩", regionId = "xinghai", x = 3650, y = 1450,
            hint = "东岸有片沙滩离星星最近",
            vignette = "夜里的星海压得极低，海平线分不清哪边是海哪边是天。偶有流星入海，本地人会许愿但从不说出来。",
        ),
        WorldWonder(
            id = "wonder_echogorge", name = "回声峡", regionId = "mushan", x = 2660, y = 850,
            hint = "有个峡谷会把话「还」给你",
            vignette = "对着峡谷喊一句话，回声会迟到七秒——刚好久到让你以为没有回应，然后它清清楚楚地回来了。",
        ),
        WorldWonder(
            id = "wonder_hotspring", name = "雪原浮汤", regionId = "jibei", x = 2260, y = 300,
            hint = "冰天雪地里泡着一池暖",
            vignette = "零下的雪原正中，温泉冒着白汽，边缘结着冰花。雪落进水里的那一声「嘶」，是极北最治愈的声音。",
        ),
    )
}
