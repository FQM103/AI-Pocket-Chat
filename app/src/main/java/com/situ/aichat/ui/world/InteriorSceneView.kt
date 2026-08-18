package com.situ.aichat.ui.world

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.pet.EggNestState
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.AvatarColor
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.world.eggnest.EGG_NEST_ANCHOR
import com.situ.aichat.ui.world.eggnest.EggNestCelebrateBurst
import com.situ.aichat.ui.world.eggnest.EggNestOverlay
import com.situ.aichat.ui.world.continent.ContinentSite
import com.situ.aichat.ui.world.continent.SiteProjection
import com.situ.aichat.ui.world.interior.InteriorAnchor
import com.situ.aichat.ui.world.interior.InteriorData
import com.situ.aichat.ui.world.interior.InteriorFlavorSpot
import com.situ.aichat.ui.world.interior.InteriorGLView
import com.situ.aichat.ui.world.interior.InteriorMath
import com.situ.aichat.world.live.EavesdropEntity
import com.situ.aichat.world.live.EavesdropOutcome
import com.situ.aichat.world.stage.StageMode
import com.situ.aichat.world.stage.StagedCharacter
import com.situ.aichat.world.stage.StagedNative
import com.situ.aichat.world.stage.StagedPet
import com.situ.aichat.world.stage.WorldTownCast
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 室内可选中项（场景点 / guest 人物 / 原住民 / 宠物 / 家的蛋巢）。 */
internal sealed interface InteriorSelection {
    data class Spot(val spot: InteriorFlavorSpot) : InteriorSelection
    data class Person(val staged: StagedCharacter, val anchor: InteriorAnchor) : InteriorSelection
    data class Native(val staged: StagedNative, val anchor: InteriorAnchor) : InteriorSelection
    data class Pet(val pet: StagedPet, val anchor: InteriorAnchor) : InteriorSelection
    data class Nest(val anchor: InteriorAnchor) : InteriorSelection // W12.5 家的蛋巢（yunye_home 专属）
}

// 偷听触发/复位 dist 阈值（§4.6·以 9d 室内相机 tDist[6.5,17] 换算·InteriorCamera 私有 companion 属 §6 锁死区不可引·换算结果落值·§11 记）：
// 触发 = 6.5 + 0.19×(17−6.5) = 8.495；复位 = 6.5 + 0.29×(17−6.5) = 9.545。
private const val EAVES_TRIGGER_DIST = 8.495f
private const val EAVES_RESET_DIST = 9.545f

// 偷听逐句节奏（§4.6·锁死·ms）。
private const val EAVES_LINE_GAP_MS = 1700L
private const val EAVES_LAST_HOLD_MS = 2600L
private const val EAVES_GROUP_FADE_MS = 600L
private const val EAVES_WHISPER_MS = 3400L
private const val EAVES_WHISPER_COOL_MS = 2400L

/**
 * 室内场景宿主（W9d 图纸 §2/§3.6/§4.6）：AndroidView([InteriorGLView]) + 生命周期 + 装载（Default 建几何）+
 * 投影循环（pcard 锚 / 场景点 / 热气）+ pick（56dp）→ 选中态 hoist（站点卡·复用 [WorldSiteSheet]）+ 选中环 46dp +
 * pcard/热气/页级降水覆盖层 + hint/up-hint chip + **进店原住民发现流**。回调（去聊天/去宠物/偶遇/回小镇）由 WorldScreen 接。
 */
@Composable
internal fun BoxScope.InteriorSceneView(
    placeId: String,
    cast: WorldTownCast?,
    reduceMotion: Boolean,
    staticMode: Boolean,
    interactive: Boolean,
    userPresent: Boolean,
    interiorOf: suspend (String) -> InteriorData?,
    onGlError: () -> Unit,
    onFirstFrame: () -> Unit,
    onReturnToTown: () -> Unit,
    onViewReady: (InteriorGLView) -> Unit,
    onOpenQuickChat: (String, String, String) -> Unit, // W12 C6：Person「坐下说两句」→ 快聊弹窗（uuid/name/statusLine）
    onOpenMeet: (String, String, String) -> Unit, // W12 C7：Native willing「去打个招呼」→ 初遇弹窗（nativeId/name/placeName）
    onDismissResident: (String, String) -> Unit, // 战役 B（O6·§4.3）：自建未招募居民「送 TA 离开」→ 暖纸确认（nativeId/name）
    onOpenPet: (String) -> Unit,
    onMeetNative: suspend (String) -> Boolean,
    quickChatOpen: Boolean,
    onEavesdrop: suspend (List<EavesdropEntity>) -> EavesdropOutcome,
    // W12.5 家的蛋巢（决策 42·仅 yunye_home）：巢态 + 空态开「孵蛋之约」 + 可孵化「迎接」→ 庆祝爆发 + petAdoption 导航。
    nestState: EggNestState,
    onOpenNestPact: () -> Unit,
    onOpenPetAdoption: (String) -> Unit,
) {
    val isHomeNest = placeId == "yunye_home"
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val ctx = LocalContext.current
    var glView by remember { mutableStateOf<InteriorGLView?>(null) }
    var sceneSize by remember { mutableStateOf(IntSize.Zero) }
    var data by remember { mutableStateOf<InteriorData?>(null) }
    var selected by remember { mutableStateOf<InteriorSelection?>(null) }
    var metNativeId by remember { mutableStateOf<String?>(null) }
    var upHint by remember { mutableStateOf(false) }
    var celebrateUuid by remember { mutableStateOf<String?>(null) } // W12.5 孵化庆祝在途（§4.5·非状态·仅转场装饰）
    val haptics = LocalAppHaptics.current
    val pickRadiusPx = with(density) { 56.dp.toPx() }
    val ringHalfPx = with(density) { 23.dp.toPx() }

    // 本店演员过滤：guest（AT_PLACE 本地点·uuid 升序前 2·E12）/ 原住民（本地点）/ 宠物（配 petSpots）。
    val d = data
    val guests = remember(cast, placeId, d) {
        (cast?.characters ?: emptyList()).filter { it.mode == StageMode.AT_PLACE && it.placeId == placeId }
            .sortedBy { it.uuid }.take(2)
    }
    val native = remember(cast, placeId) { cast?.natives?.firstOrNull { it.placeId == placeId } }
    val pets = remember(cast, d) {
        val spots = d?.petSpots ?: emptyList()
        (cast?.pets ?: emptyList()).take(spots.size)
    }

    // 偷听池（§3·guests ∪ 该处 discovered 原住民·未发现绝不入池·≥2 才可偷听）+ 说话人名→pcard 投影下标（§4.6 气泡锚在卡上方）。
    val eavesPool = remember(guests, native) {
        buildList {
            guests.forEach { add(EavesdropEntity(it.uuid, it.name, characterUuid = it.uuid, nativeSlug = null)) }
            native?.takeIf { it.discovered }?.let { add(EavesdropEntity(it.nativeId, it.name, characterUuid = null, nativeSlug = it.slug)) }
        }
    }
    val speakerProjIndex = remember(guests, native, d) {
        val cur = d
        val map = HashMap<String, Int>()
        if (cur != null) {
            var pidx = cur.flavorSpots.size
            guests.forEachIndexed { i, g -> if (cur.guestSlots.getOrNull(i) != null) { map[g.name] = pidx; pidx++ } }
            if (native != null && native.discovered && cur.nativeAnchor != null) map[native.name] = pidx
        }
        map
    }
    // 偷听播放态（§4.6·arm 随 dist 触发/复位·bubbles 同人新句旧泡退场·whisper 落账）。
    var eavesArmed by remember { mutableStateOf(true) }
    var eavesPlaying by remember { mutableStateOf(false) }
    var eavesTrigger by remember { mutableStateOf(0) }
    var eavesBubbles by remember { mutableStateOf<List<EavesBubbleState>>(emptyList()) }
    var eavesWhisper by remember { mutableStateOf<AnnotatedString?>(null) }
    val cardClearancePx = with(density) { 76.dp.toPx() } // 气泡/chip 上抬过卡（~64dp pcard + 余量·§11 记）

    fun pickables(): List<Pair<InteriorAnchor, InteriorSelection>> {
        val cur = data ?: return emptyList()
        val out = mutableListOf<Pair<InteriorAnchor, InteriorSelection>>()
        cur.flavorSpots.forEach { out += it.anchor to InteriorSelection.Spot(it) }
        guests.forEachIndexed { i, g -> cur.guestSlots.getOrNull(i)?.let { out += it to InteriorSelection.Person(g, it) } }
        native?.let { n -> cur.nativeAnchor?.let { if (n.discovered) out += it to InteriorSelection.Native(n, it) } }
        pets.forEachIndexed { i, p -> cur.petSpots.getOrNull(i)?.let { out += it to InteriorSelection.Pet(p, it) } }
        if (isHomeNest) out += EGG_NEST_ANCHOR to InteriorSelection.Nest(EGG_NEST_ANCHOR) // W12.5 蛋巢 GL-tap 兜底
        return out
    }

    fun pick(px: Float, py: Float) {
        if (!interactive) return
        val v = glView ?: return
        val w = sceneSize.width.toFloat(); val h = sceneSize.height.toFloat()
        if (w == 0f || h == 0f) return
        val snap = v.cameraSnapshot()
        val mvp = InteriorMath.interiorMvp(snap.yaw, snap.pitch, snap.dist, snap.tx, snap.ty, snap.tz, w / h)
        var best: InteriorSelection? = null
        var bestD = pickRadiusPx
        for ((anchor, sel) in pickables()) {
            val proj = InteriorMath.projectAnchor(mvp, anchor.x, anchor.y, anchor.z, w, h)
            if (!proj.visible) continue
            val dist = hypot(proj.x - px, proj.y - py)
            if (dist < bestD) { bestD = dist; best = sel }
        }
        selected = best // 空点清选中·相机不复位
    }

    AndroidView(
        factory = { ctx ->
            InteriorGLView(
                context = ctx, reduceMotion = reduceMotion,
                onGlError = onGlError, onFirstFrame = onFirstFrame,
                onTap = { x, y -> pick(x, y) }, onReturnGesture = onReturnToTown,
            ).also { glView = it; onViewReady(it) }
        },
        modifier = Modifier.fillMaxSize().onSizeChanged { sceneSize = it },
        update = { it.setRenderFlags(reduceMotion, staticMode) },
    )

    DisposableEffect(lifecycleOwner, glView) {
        val v = glView
        if (v != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) v.resumeWorld()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> v?.resumeWorld()
                Lifecycle.Event.ON_PAUSE -> v?.pauseWorld()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); v?.pauseWorld() }
    }

    // 装载室内几何（Default 已在 interiorOf 内建·此处提交）。
    LaunchedEffect(glView, placeId) {
        val v = glView ?: return@LaunchedEffect
        val loaded = interiorOf(placeId) ?: return@LaunchedEffect
        v.submitInterior(loaded)
        data = loaded
        selected = null
    }

    // 进店原住民偶遇流（§4.6C·🔴-2：已发现也记一次偶遇燃料·服务侧同日去重；newly 为 false 时静默不弹卡不设 metNativeId）。
    LaunchedEffect(placeId, native?.nativeId, userPresent) {
        val n = native ?: return@LaunchedEffect
        if (!userPresent) return@LaunchedEffect
        val newly = onMeetNative(n.nativeId)
        // 🟡-3：新发现触发 haptics light（§4.6C）。
        if (newly) { haptics.light(); metNativeId = n.nativeId; data?.nativeAnchor?.let { selected = InteriorSelection.Native(n, it) } }
    }

    // 投影循环（每帧读快照复算 mvp·overlay 经 offset 定位不触重组）。
    val anchorProj = remember { mutableStateOf<List<SiteProjection>>(emptyList()) }
    val nestProj = remember { mutableStateOf<SiteProjection?>(null) } // W12.5 蛋巢锚每帧投影（独立·不搅 projItems 下标）
    val projItems = remember(data, guests, native, pets) {
        val cur = data
        buildList {
            if (cur != null) {
                cur.flavorSpots.forEach { add(it.anchor) }
                guests.forEachIndexed { i, _ -> cur.guestSlots.getOrNull(i)?.let { add(it) } }
                native?.let { n -> cur.nativeAnchor?.let { if (n.discovered) add(it) } }
                pets.forEachIndexed { i, _ -> cur.petSpots.getOrNull(i)?.let { add(it) } }
                cur.steamSpots.forEach { add(it) }
            }
        }
    }
    LaunchedEffect(glView, sceneSize, projItems) {
        val v = glView ?: return@LaunchedEffect
        if (sceneSize.width == 0 || sceneSize.height == 0) return@LaunchedEffect
        val w = sceneSize.width.toFloat(); val h = sceneSize.height.toFloat()
        while (true) {
            withFrameNanos { }
            val snap = v.cameraSnapshot()
            val mvp = InteriorMath.interiorMvp(snap.yaw, snap.pitch, snap.dist, snap.tx, snap.ty, snap.tz, w / h)
            anchorProj.value = projItems.map { InteriorMath.projectAnchor(mvp, it.x, it.y, it.z, w, h) }
            if (isHomeNest) nestProj.value = InteriorMath.projectAnchor(mvp, EGG_NEST_ANCHOR.x, EGG_NEST_ANCHOR.y, EGG_NEST_ANCHOR.z, w, h)
            upHint = v.wantsUpHint()
            // 偷听 dist 触发（§4.6·仅穿阈写态·非每帧）：推近过阈 → 触发一次；退远过复位阈 → 重新武装。
            if (eavesArmed && !eavesPlaying && eavesPool.size >= 2 && snap.dist <= EAVES_TRIGGER_DIST) {
                eavesArmed = false; eavesTrigger++
            } else if (snap.dist >= EAVES_RESET_DIST) {
                eavesArmed = true
            }
        }
    }

    // 偷听回放（触发计数变化即跑一次·弹窗开/非交互/池不足则 no-op·§4.6 逐句节奏 + whisper）。
    LaunchedEffect(eavesTrigger) {
        if (eavesTrigger == 0 || quickChatOpen || !interactive || eavesPool.size < 2) return@LaunchedEffect
        eavesPlaying = true
        try {
            when (val o = onEavesdrop(eavesPool)) {
                is EavesdropOutcome.Live -> playEavesdropLines(o.lines, liveWhisper(ctx, o.summary), { eavesBubbles = it }, { eavesWhisper = it })
                is EavesdropOutcome.Template -> playEavesdropLines(o.lines, AnnotatedString(ctx.getString(R.string.world_eaves_whisper_tpl)), { eavesBubbles = it }, { eavesWhisper = it })
                EavesdropOutcome.Cooldown -> { eavesWhisper = AnnotatedString(ctx.getString(R.string.world_eaves_whisper_cool)); delay(EAVES_WHISPER_COOL_MS) }
                EavesdropOutcome.Unavailable -> Unit
            }
        } finally {
            eavesBubbles = emptyList(); eavesWhisper = null; eavesPlaying = false
        }
    }

    // pcard 覆盖层（native 名卡 + guest + 宠物·投影经 offset 定位·底部中心锚）。
    val cur = data
    if (cur != null) {
        var idx = cur.flavorSpots.size // pcard 段起点
        guests.forEachIndexed { i, g ->
            val pi = idx + i
            PcardAt(anchorProj, pi, sceneSize) {
                val a11y = stringResource(R.string.world_card_character_a11y, g.name, cur.placeName)
                InteriorPcard(g.name, g.avatarPath, g.statusLine, isPet = false, a11y = a11y, reduceMotion = reduceMotion,
                    onClick = { if (interactive) cur.guestSlots.getOrNull(i)?.let { selected = InteriorSelection.Person(g, it) } })
            }
        }
        idx += guests.size
        val n = native
        if (n != null && n.discovered && cur.nativeAnchor != null) {
            PcardAt(anchorProj, idx, sceneSize) {
                val a11y = stringResource(R.string.world_card_character_a11y, n.name, cur.placeName)
                InteriorPcard(n.name, null, n.oneLiner, isPet = false, a11y = a11y, reduceMotion = reduceMotion, // 🔵-2：状态行传 oneLiner（零新增数据）
                    onClick = { if (interactive) selected = InteriorSelection.Native(n, cur.nativeAnchor) })
            }
            idx += 1
        } else if (n != null && n.discovered) {
            idx += 1
        }
        pets.forEachIndexed { i, p ->
            val pi = idx + i
            PcardAt(anchorProj, pi, sceneSize) {
                val a11y = stringResource(R.string.world_card_pet_a11y, p.name)
                InteriorPcard(p.name, null, "", isPet = true, a11y = a11y, reduceMotion = reduceMotion,
                    onClick = { if (interactive) cur.petSpots.getOrNull(i)?.let { selected = InteriorSelection.Pet(p, it) } })
            }
        }
        // 热气（段尾·相位按序）。
        val steamStart = idx + pets.size
        cur.steamSpots.forEachIndexed { i, _ ->
            val pi = steamStart + i
            val proj = anchorProj.value.getOrNull(pi)
            if (proj != null && proj.visible) {
                InteriorSteam(index = i, modifier = Modifier.offset { IntOffset(proj.x.roundToInt(), proj.y.roundToInt()) })
            }
        }
    }

    // W12.5 家的蛋巢叠层（仅 yunye_home·2D 叠层零碰 GL·投影随 nestProj 每帧·点击 hoist 选中）。
    if (isHomeNest) {
        EggNestOverlay(
            state = nestState,
            projection = { nestProj.value },
            reduceMotion = reduceMotion,
            onClick = { if (interactive) selected = InteriorSelection.Nest(EGG_NEST_ANCHOR) },
        )
    }

    // 选中环（锚 = 选中项锚·中心对齐）。
    val sel = selected
    val selAnchor = when (sel) {
        is InteriorSelection.Spot -> sel.spot.anchor
        is InteriorSelection.Person -> sel.anchor
        is InteriorSelection.Native -> sel.anchor
        is InteriorSelection.Pet -> sel.anchor
        // TODO(图纸未覆盖): 蛋巢选中环——§4 未提；判定不显（巢自带光晕/大体量·46dp 环会与巢叠置显乱）→ Nest 无环。待作者复审（§11）。
        is InteriorSelection.Nest -> null
        null -> null
    }
    if (selAnchor != null && cur != null) {
        InteriorSelectedRing(
            reduceMotion = reduceMotion,
            modifier = Modifier.offset {
                val v = glView
                val w = sceneSize.width.toFloat(); val h = sceneSize.height.toFloat()
                if (v == null || w == 0f || h == 0f) return@offset IntOffset(-9999, -9999)
                val snap = v.cameraSnapshot()
                val mvp = InteriorMath.interiorMvp(snap.yaw, snap.pitch, snap.dist, snap.tx, snap.ty, snap.tz, w / h)
                val p = InteriorMath.projectAnchor(mvp, selAnchor.x, selAnchor.y, selAnchor.z, w, h)
                if (!p.visible) IntOffset(-9999, -9999) else IntOffset((p.x - ringHalfPx).roundToInt(), (p.y - ringHalfPx).roundToInt())
            },
        )
    }

    // 页级降水（雨/雪·仅室内·reduce/static 隐·§4.10）。
    val weather = cur?.weather
    if (!reduceMotion && !staticMode && weather != null && weather != com.situ.aichat.world.stage.WorldWeatherKind.CLEAR) {
        InteriorPagePrecip(snow = weather == com.situ.aichat.world.stage.WorldWeatherKind.SNOW)
    }

    // hint chip（底部中央·开卡时隐）。
    if (selected == null) {
        WorldGlassChip(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)) {
            Text(stringResource(R.string.world_interior_hint), color = WorldSceneColors.onGlass, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
    // up-hint chip（顶部中央·dist>15.8 && intro 完成）。
    val upAlpha by animateFloatAsState(if (upHint) 1f else 0f, tween(300, easing = AppMotion.EaseInOut), label = "intUpHint")
    if (upAlpha > 0f) {
        WorldGlassChip(modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp).alpha(upAlpha)) {
            Text(stringResource(R.string.world_interior_zoom_out_hint), color = WorldSceneColors.onGlass, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }

    // 站点卡（复用 WorldSiteSheet·内容按选中类型装配）。
    // 🟡-R2-1：开快聊/初遇前先收起站点卡（selected=null·等价 demo shAct「先关卡再开弹窗」·消除弹窗暖纸下站点卡残影的双层叠置）；
    // 宠物「去看看」跳离本屏、无叠置，保持现状不清选中。
    InteriorSiteSheet(
        selected, cur?.placeName, metNativeId, reduceMotion,
        onClose = { selected = null },
        onOpenQuickChat = { uuid, name, statusLine -> selected = null; onOpenQuickChat(uuid, name, statusLine) },
        onOpenMeet = { nativeId, name, place -> selected = null; onOpenMeet(nativeId, name, place) },
        onDismissResident = { nativeId, name -> selected = null; onDismissResident(nativeId, name) }, // 收卡再开确认弹窗（同 onOpenMeet 惯例）
        onOpenPet = onOpenPet,
        // W12.5 蛋巢：空态开「孵蛋之约」/可孵化「迎接」均先收卡再外抛（§4.2·W12 R2-1）；在孵去看进度复用 onOpenPet→petDetail。
        nestState = nestState,
        onOpenNestPact = { selected = null; onOpenNestPact() },
        // 「迎接小家伙」：收卡 → celebrate 触觉（同红包揭晓档）→ 触发庆祝爆发（§4.5·导航由下方 LaunchedEffect 驱动）。
        onWelcomeHatch = { uuid -> selected = null; haptics.success(); celebrateUuid = uuid },
    )

    // W12.5 孵化庆祝 → 导航（§4.5）：非 reduceMotion 等 800ms 让爆发起势再跳三步流；reduceMotion 直接导航（跳过①②）。
    LaunchedEffect(celebrateUuid) {
        val uuid = celebrateUuid ?: return@LaunchedEffect
        if (!reduceMotion) delay(800)
        onOpenPetAdoption(uuid)
        celebrateUuid = null // 复位：三步流放弃回来（蛋仍 Hatchable）可再迎接（E3）·不重放旧爆发。
    }
    // 庆祝爆发叠层（仅 yunye_home·在途·非 reduceMotion·锚巢投影·角色色入粒子池）。
    if (isHomeNest && celebrateUuid != null && !reduceMotion) {
        val burstColor = (nestState as? EggNestState.Hatchable)?.let { AvatarColor.color(it.characterName) } ?: WorldSceneColors.gold
        EggNestCelebrateBurst(projection = { nestProj.value }, characterColor = burstColor)
    }

    // ── 偷听 overlay（§4.6·邀请 chip / 双气泡 / whisper·投影经 anchorProj 定位·同 pcard 家族）──
    if (eavesArmed && !eavesPlaying && !quickChatOpen && interactive && eavesPool.size >= 2 && speakerProjIndex.isNotEmpty()) {
        var chipSize by remember { mutableStateOf(IntSize.Zero) }
        EavesInviteChip(
            text = stringResource(R.string.world_eaves_chip),
            reduceMotion = reduceMotion,
            // 直接触发（§4.6）。TODO(图纸未覆盖): 「相机推近至触发阈值」——室内相机为 §6 锁死区、无推近 API，暂只直接触发（§11 记）。
            onClick = { if (!eavesPlaying) { eavesArmed = false; eavesTrigger++ } },
            modifier = Modifier.onSizeChanged { chipSize = it }.offset {
                val pts = speakerProjIndex.values.mapNotNull { anchorProj.value.getOrNull(it)?.takeIf { p -> p.visible } }
                if (pts.isEmpty()) return@offset IntOffset(-9999, -9999)
                val cx = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
                val cy = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
                IntOffset((cx - chipSize.width / 2f).roundToInt(), (cy - cardClearancePx - chipSize.height).roundToInt())
            },
        )
    }
    eavesBubbles.forEach { b ->
        val idx = speakerProjIndex[b.speaker] ?: return@forEach
        var bubbleSize by remember(b.id) { mutableStateOf(IntSize.Zero) }
        EavesBubble(
            b.text, b.exiting, reduceMotion,
            modifier = Modifier.onSizeChanged { bubbleSize = it }.offset {
                val p = anchorProj.value.getOrNull(idx)
                if (p == null || !p.visible) IntOffset(-9999, -9999)
                else IntOffset((p.x - bubbleSize.width / 2f).roundToInt(), (p.y - cardClearancePx - bubbleSize.height).roundToInt())
            },
        )
    }
    var lastWhisper by remember { mutableStateOf<AnnotatedString?>(null) }
    if (eavesWhisper != null) lastWhisper = eavesWhisper
    AnimatedVisibility(
        visible = eavesWhisper != null,
        enter = fadeIn(tween(500)), exit = fadeOut(tween(500)),
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp),
    ) {
        lastWhisper?.let { EavesWhisper(it) }
    }
}

/**
 * 偷听逐句回放（§4.6·锁死节奏）：i>0 逐句间隔 1700ms·同人新句旧泡置 exiting（不叠罗汉）·末句停 2600ms·
 * 整组 600ms 渐隐（同刻出 whisper）·whisper 驻留至 3400ms。取消（离场/再触发）由调用方 finally 清态。
 */
private suspend fun playEavesdropLines(
    lines: List<com.situ.aichat.world.live.EavesLine>,
    whisper: AnnotatedString,
    setBubbles: (List<EavesBubbleState>) -> Unit,
    setWhisper: (AnnotatedString?) -> Unit,
) {
    var bubbles = emptyList<EavesBubbleState>()
    var nextId = 0
    lines.forEachIndexed { i, line ->
        if (i > 0) delay(EAVES_LINE_GAP_MS)
        bubbles = bubbles.map { if (it.speaker == line.speaker && !it.exiting) it.copy(exiting = true) else it } +
            EavesBubbleState(nextId++, line.speaker, line.text, exiting = false)
        setBubbles(bubbles)
    }
    delay(EAVES_LAST_HOLD_MS)
    setBubbles(bubbles.map { it.copy(exiting = true) })
    setWhisper(whisper)
    delay(EAVES_GROUP_FADE_MS)
    setBubbles(emptyList())
    delay(EAVES_WHISPER_MS - EAVES_GROUP_FADE_MS)
    setWhisper(null)
}

/** LLM 模式 whisper（§4.6·「…记下一句：<摘要>」·摘要段 w600 #EAD9BE）。 */
private fun liveWhisper(ctx: android.content.Context, summary: String): AnnotatedString {
    val full = ctx.getString(R.string.world_eaves_whisper, summary)
    val start = full.lastIndexOf(summary)
    return buildAnnotatedString {
        append(full)
        if (start >= 0) addStyle(eavesWhisperEmphasis, start, start + summary.length)
    }
}

/** pcard 定位包装（投影 index → offset·底部中心锚·不可见移出屏）。 */
@Composable
private fun BoxScope.PcardAt(anchorProj: androidx.compose.runtime.State<List<SiteProjection>>, index: Int, sceneSize: IntSize, content: @Composable () -> Unit) {
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.onSizeChanged { cardSize = it }.offset {
            val p = anchorProj.value.getOrNull(index)
            if (p == null || !p.visible) IntOffset(-9999, -9999)
            else IntOffset((p.x - cardSize.width / 2f).roundToInt(), (p.y - cardSize.height).roundToInt())
        },
    ) { content() }
}
