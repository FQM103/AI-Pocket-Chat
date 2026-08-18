package com.situ.aichat.ui.world.interior

import com.situ.aichat.ui.world.continent.ContinentMath
import com.situ.aichat.ui.world.continent.SiteProjection
import com.situ.aichat.ui.world.planet.PlanetMath

/**
 * 室内盒景相机 / 投影矩阵（W9d 图纸 §3.3/§4.5·demo:L386-387 逐式）。渲染器与覆盖层投影共用同式。
 * 复用 [PlanetMath] Float 列主序族 + [ContinentMath].trans3（图纸 §9 复用不改·§6 锁死区零改）。室内独有 =
 * 投影 `persp(0.8, asp, 0.3, 80)`（demo:L387）与恒定 TARGET 视图（无跟随）。
 */
internal object InteriorMath {

    private const val FOV = 0.8f
    private const val NEAR = 0.3f
    private const val FAR = 80f

    /** 视图矩阵 `trans(0,0,-dist)·rotX(pitch)·rotY(yaw)·trans(-target)`（demo:L386）。 */
    private fun interiorView(yaw: Float, pitch: Float, dist: Float, tx: Float, ty: Float, tz: Float): FloatArray {
        val rot = PlanetMath.mul(PlanetMath.rotX(pitch), PlanetMath.rotY(yaw))
        val rotTrans = PlanetMath.mul(rot, ContinentMath.trans3(-tx, -ty, -tz))
        return PlanetMath.mul(ContinentMath.trans3(0f, 0f, -dist), rotTrans)
    }

    /** MVP `persp(0.8, asp, 0.3, 80)·view`（demo:L387）。 */
    fun interiorMvp(
        yaw: Float, pitch: Float, dist: Float, tx: Float, ty: Float, tz: Float, aspect: Float,
    ): FloatArray = PlanetMath.mul(PlanetMath.persp(FOV, aspect, NEAR, FAR), interiorView(yaw, pitch, dist, tx, ty, tz))

    /**
     * 锚点世界坐标 → 屏幕像素（demo:L351-353·复用 continent [SiteProjection]）：`c=mvp·(x,y,z,1)`；
     * `c.w≤0` → 不可见；否则 `sx=(c.x/c.w·0.5+0.5)·W`、`sy=(-c.y/c.w·0.5+0.5)·H`。
     */
    fun projectAnchor(mvp: FloatArray, x: Float, y: Float, z: Float, viewW: Float, viewH: Float): SiteProjection {
        val c = PlanetMath.v4(mvp, x, y, z)
        if (c[3] <= 0f) return SiteProjection(false, 0f, 0f)
        return SiteProjection(true, (c[0] / c[3] * 0.5f + 0.5f) * viewW, (-c[1] / c[3] * 0.5f + 0.5f) * viewH)
    }
}
