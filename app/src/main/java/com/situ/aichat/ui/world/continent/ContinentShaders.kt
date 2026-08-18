package com.situ.aichat.ui.world.continent

/**
 * 大陆盒景 GLSL 源串（W9b 图纸 §4.1A/§4.1C·GLES2）。
 *
 * [C_VS]/[C_FS_LIT]/[C_FS_WATER]/[C_FS_EMIS]：从对版 demo `design/world/continent-3d-demo.html` L242-254
 * **逐行移植**——数值与表达式一字不改（图纸 §9 禁改·复核硬项 verify_9b_shaders 逐行零漂移）。
 * [C_BG_FS]：天空 5 停靠竖向渐变 + 椭圆辉光（demo DOM `.sky`/`.glow` → GL·值同源新作·§4.1C）；背景 quad VS
 * 与星点程序复用 [com.situ.aichat.ui.world.planet.PlanetShaders].BG_VS/STAR_VS/STAR_FS（同一片着色器·不同顶点）。
 */
internal object ContinentShaders {

    /** 顶点着色器（demo:L242-244）·lit/water/emis 共用。 */
    const val C_VS = """
attribute vec3 aPos;attribute vec3 aNor;attribute vec3 aCol;
uniform mat4 uMVP;varying vec3 vN;varying vec3 vC;varying float vD;
void main(){vN=aNor;vC=aCol;vec4 p=uMVP*vec4(aPos,1.0);vD=p.w;gl_Position=p;}
"""

    /** 地形/树/城/塔片元（demo:L245-250·暖阳 + 距离雾）。 */
    const val C_FS_LIT = """
precision mediump float;varying vec3 vN;varying vec3 vC;varying float vD;
uniform vec3 uSun;uniform vec3 uWarm;uniform vec3 uHaze;
void main(){float d=max(dot(normalize(vN),normalize(uSun)),0.0);
 vec3 col=vC*(0.40+0.78*d)*uWarm;
 col=mix(col,uHaze,clamp((vD-42.0)/70.0,0.0,0.4));
 gl_FragColor=vec4(col,1.0);}
"""

    /** 水面片元（demo:L251-252·半透 alpha 0.84）。 */
    const val C_FS_WATER = """
precision mediump float;varying vec3 vC;varying float vD;
void main(){gl_FragColor=vec4(vC,0.84);}
"""

    /** 自发光片元（窗/顶灯·demo:L253-254）。 */
    const val C_FS_EMIS = """
precision mediump float;varying vec3 vC;
void main(){gl_FragColor=vec4(vC*1.15,1.0);}
"""

    /**
     * 背景天空片元（§4.1C·值同源新作·9a §11.B4 先例）：竖向 5 停靠渐变（`uSky[5]`+`uSkyPos[5]`·区切换时
     * CPU lerp 后上传）+ 椭圆辉光（demo:L6-8 CSS 推导·中心 (0.50,0.704)·半径 0.715×0.208·(255,214,150)α0.42
     * @0→(255,196,130)α0.12@0.45→透明@0.70·screen 叠加·整体 ×uGlowA）。VS 复用 PlanetShaders.BG_VS（vUv 顶=0 底=1）。
     */
    const val C_BG_FS = """
precision mediump float;
varying vec2 vUv;
uniform vec3 uSky[5];
uniform float uSkyPos[5];
uniform float uGlowA;
vec3 gradAt(float y){
  if(y<=uSkyPos[1]) return mix(uSky[0],uSky[1],clamp((y-uSkyPos[0])/(uSkyPos[1]-uSkyPos[0]),0.0,1.0));
  if(y<=uSkyPos[2]) return mix(uSky[1],uSky[2],clamp((y-uSkyPos[1])/(uSkyPos[2]-uSkyPos[1]),0.0,1.0));
  if(y<=uSkyPos[3]) return mix(uSky[2],uSky[3],clamp((y-uSkyPos[2])/(uSkyPos[3]-uSkyPos[2]),0.0,1.0));
  return mix(uSky[3],uSky[4],clamp((y-uSkyPos[3])/(uSkyPos[4]-uSkyPos[3]),0.0,1.0));
}
void main(){
  vec3 col=gradAt(vUv.y);
  vec2 e=vec2((vUv.x-0.50)/0.715,(vUv.y-0.704)/0.208);
  float ed=length(e);
  float t0=smoothstep(0.0,0.45,ed);
  float t1=smoothstep(0.45,0.70,ed);
  float ga=(ed<0.45 ? mix(0.42,0.12,t0) : mix(0.12,0.0,t1))*uGlowA;
  vec3 gcol=mix(vec3(1.0,0.8392157,0.5882353),vec3(1.0,0.7686275,0.5098039),t0);
  vec3 src=gcol*ga;
  col=1.0-(1.0-col)*(1.0-src);
  gl_FragColor=vec4(col,1.0);
}
"""
}
