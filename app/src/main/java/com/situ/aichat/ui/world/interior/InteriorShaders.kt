package com.situ.aichat.ui.world.interior

/**
 * 室内盒景 GLSL 源串 + 昼夜光照参数（W9d 图纸 §4.2E·demo:L256-287 逐行·GLES2·§9 禁改）。
 *
 * [I_FS_LIT] 双向光（灯暖顶光 + 窗侧冷光 + 距离暮化）逐行照 demo:L259-266，昼夜差值（ambient/warmW/coolW/
 * coolCol/fog）外提为 uniform 由渲染器按 [NIGHT] / [DAY] 灌（夜 = demo 原值·昼 = 作者落值）。[I_FS_EMIS] 闪烁式
 * （demo:L267-269）。[I_VS_RAIN]/[I_FS_RAIN] 降水（demo:L270-275）——速度/透明/色外提 uniform 以「同管线变参」支雪
 * （§4.2D·rain uniform = demo 原值·§11）。[I_BG_FS] 页底渐变 + 暖光溢出（demo:L6-9 转译·3 停靠·§4.2E）。
 * 顶点着色器 lit/emis 复用 [com.situ.aichat.ui.world.continent.ContinentShaders].C_VS；bg VS 复用 PlanetShaders.BG_VS。
 */
internal object InteriorShaders {

    /** 双向光片元（demo:L259-266 逐行·昼夜差为 uniform·光向 uWarm/uCool 恒 demo:L287）。 */
    const val I_FS_LIT = """
precision mediump float;varying vec3 vN;varying vec3 vC;varying float vD;
uniform vec3 uWarm;uniform vec3 uCool;
uniform float uAmbient;uniform float uWarmW;uniform float uCoolW;uniform vec3 uCoolCol;uniform vec3 uFog;
void main(){vec3 n=normalize(vN);
 float dw=max(dot(n,normalize(uWarm)),0.0);
 float dc=max(dot(n,normalize(uCool)),0.0);
 vec3 col=vC*(uAmbient + uWarmW*dw*vec3(1.0,0.87,0.70) + uCoolW*dc*uCoolCol);
 col=mix(col,uFog,clamp((vD-16.0)/26.0,0.0,0.5));
 gl_FragColor=vec4(col,1.0);}
"""

    /** 自发光闪烁片元（demo:L267-269·reduce/static uTime 恒 0）。 */
    const val I_FS_EMIS = """
precision mediump float;varying vec3 vC;varying float vD;uniform float uTime;
void main(){float f=0.96+0.04*sin(uTime*3.1+vD*7.0);
 gl_FragColor=vec4(vC*1.12*f,1.0);}
"""

    /** 降水顶点（demo:L270-273·aCol.g=基准高 aCol.r=相位·整条按 uTime 回绕·速度 uFall/透明 uAlpha 变参支雪）。 */
    const val I_VS_RAIN = """
attribute vec3 aPos;attribute vec3 aNor;attribute vec3 aCol;
uniform mat4 uMVP;uniform float uTime;uniform float uFall;uniform float uAlpha;varying float vA;
void main(){float base=mod(aCol.g - uTime*uFall + aCol.r, 3.4);
 vA=uAlpha;vec4 p=uMVP*vec4(aPos.x,base+aPos.y,aPos.z,1.0);gl_Position=p;}
"""

    /** 降水片元（demo:L274-275·色 uPrecipCol 变参：雨 (0.72,0.80,0.95) / 雪 (0.95,0.96,1.0)）。 */
    const val I_FS_RAIN = """
precision mediump float;varying float vA;uniform vec3 uPrecipCol;
void main(){gl_FragColor=vec4(uPrecipCol,vA);}
"""

    /**
     * 页底片元（demo:L6-9 转译·§4.2E）：竖向 3 停靠渐变（`uSky[3]`+`uSkyPos[3]`·vUv 顶=0 底=1）+ 暖光溢出
     * （径向椭圆·中心 (0.50,1.06)·rx0.55/ry0.30·(255,205,140)·α uSpillA@0→×0.3125@0.48→0@0.72·screen 混合）。
     * 3 停靠直接实现（§4.2E「pad 到 7·渲染等价」·此处用 3 停靠·§11）。溢出强度 uSpillA 夜 0.16 / 昼 0.08。
     */
    const val I_BG_FS = """
precision mediump float;
varying vec2 vUv;
uniform vec3 uSky[3];
uniform float uSkyPos[3];
uniform float uSpillA;
vec3 gradAt(float y){
  if(y<=uSkyPos[1]) return mix(uSky[0],uSky[1],clamp((y-uSkyPos[0])/(uSkyPos[1]-uSkyPos[0]),0.0,1.0));
  return mix(uSky[1],uSky[2],clamp((y-uSkyPos[1])/(uSkyPos[2]-uSkyPos[1]),0.0,1.0));
}
void main(){
  vec3 col=gradAt(vUv.y);
  vec2 e=vec2((vUv.x-0.50)/0.55,(vUv.y-1.06)/0.30);
  float ed=length(e);
  float t0=smoothstep(0.0,0.48,ed);
  float t1=smoothstep(0.48,0.72,ed);
  float ga=(ed<0.48 ? mix(uSpillA,uSpillA*0.3125,t0) : mix(uSpillA*0.3125,0.0,t1));
  vec3 src=vec3(1.0,0.8039216,0.5490196)*ga;
  col=1.0-(1.0-col)*(1.0-src);
  gl_FragColor=vec4(col,1.0);
}
"""

    /** 光向恒定（demo:L287·昼夜同）。 */
    val WARM_DIR = floatArrayOf(0.22f, 0.9f, 0.3f)
    val COOL_DIR = floatArrayOf(-1.0f, 0.25f, 0.1f)

    /** 一套昼/夜光照参数（I_FS_LIT uniform + I_BG_FS 停靠色/溢出·§4.2E）。 */
    class Lighting(
        val ambient: Float, val warmW: Float, val coolW: Float,
        val coolCol: FloatArray, val fog: FloatArray,
        val bgStops: Array<FloatArray>, val spillA: Float,
    )

    /** 页底渐变 3 停靠位置（0/0.46/1.0·§4.2E）。 */
    val BG_POS = floatArrayOf(0f, 0.46f, 1f)

    private fun c(hex: Int) = floatArrayOf(
        ((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f,
    )

    /** 夜参 = demo 原值（demo:L264-265 / L6）。 */
    val NIGHT = Lighting(
        ambient = 0.34f, warmW = 0.72f, coolW = 0.22f,
        coolCol = floatArrayOf(0.62f, 0.72f, 0.95f), fog = floatArrayOf(0.09f, 0.12f, 0.21f),
        bgStops = arrayOf(c(0x0C1222), c(0x101A30), c(0x16223C)), spillA = 0.16f,
    )

    /** 昼参（作者落值·§4.2E）。 */
    val DAY = Lighting(
        ambient = 0.46f, warmW = 0.50f, coolW = 0.40f,
        coolCol = floatArrayOf(0.88f, 0.92f, 1.00f), fog = floatArrayOf(0.35f, 0.42f, 0.52f),
        bgStops = arrayOf(c(0x5C7186), c(0x71869A), c(0x8A9DAF)), spillA = 0.08f,
    )

    /** 降水色（雨/雪·§4.2D）。 */
    val RAIN_COL = floatArrayOf(0.72f, 0.80f, 0.95f)
    val SNOW_COL = floatArrayOf(0.95f, 0.96f, 1.0f)
}
