package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.continent.ContinentMath
import com.situ.aichat.ui.world.continent.SiteProjection
import com.situ.aichat.ui.world.planet.PlanetMath

/**
 * 小镇盒景相机 / 投影矩阵（W9c·demo:L294-295 逐式）。渲染器与覆盖层投影共用同式。复用 [PlanetMath] 的
 * Float 列主序族与 [ContinentMath].trans3（图纸 §9 复用不改·§6 ContinentMath 零改）；小镇独有的 = 投影
 * 远平面 **120**（demo:L295 `persp(0.85, asp, 0.5, 120)`·区别于大陆的 220）与恒定 target 视图。
 *
 * 注：图纸 §2 模块表未单列 TownMath——但渲染器与地点投影都要小镇专属的 `persp(…,120)`，而 §6 锁死 ContinentMath
 * 零改，故不能把 120 混进大陆投影；此为在 §2/§6 约束下的最小忠实拆分（图纸 §11 施工日志登记）。
 */
internal object TownMath {

    private const val FOV = 0.85f
    private const val NEAR = 0.5f
    private const val FAR = 120f

    /** 视图矩阵 `trans(0,0,-dist)·rotX(pitch)·rotY(yaw)·trans(-target)`（demo:L294）。 */
    private fun townView(yaw: Float, pitch: Float, dist: Float, tx: Float, ty: Float, tz: Float): FloatArray {
        val rot = PlanetMath.mul(PlanetMath.rotX(pitch), PlanetMath.rotY(yaw))
        val rotTrans = PlanetMath.mul(rot, ContinentMath.trans3(-tx, -ty, -tz))
        return PlanetMath.mul(ContinentMath.trans3(0f, 0f, -dist), rotTrans)
    }

    /** MVP `persp(0.85, asp, 0.5, 120)·view`（demo:L295）。 */
    fun townMvp(
        yaw: Float, pitch: Float, dist: Float, tx: Float, ty: Float, tz: Float, aspect: Float,
    ): FloatArray = PlanetMath.mul(PlanetMath.persp(FOV, aspect, NEAR, FAR), townView(yaw, pitch, dist, tx, ty, tz))


    /**
     * 地点世界坐标 → 屏幕像素（demo:L262-264·复用 continent 的 [SiteProjection]）：`c=mvp·(x,y,z,1)`；
     * `c.w≤0` → 不可见；否则 `sx=(c.x/c.w·0.5+0.5)·W`、`sy=(-c.y/c.w·0.5+0.5)·H`。
     */
    fun projectPlace(mvp: FloatArray, x: Float, y: Float, z: Float, viewW: Float, viewH: Float): SiteProjection {
        val c = PlanetMath.v4(mvp, x, y, z)
        if (c[3] <= 0f) return SiteProjection(false, 0f, 0f)
        return SiteProjection(true, (c[0] / c[3] * 0.5f + 0.5f) * viewW, (-c[1] / c[3] * 0.5f + 0.5f) * viewH)
    }
}
