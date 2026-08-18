package com.situ.aichat.ui.world.town

/**
 * 小镇盒景 GLSL 源串（W9c 图纸 §4.1E·GLES2）。
 *
 * [T_FS_LIT]：从对版 demo `design/world/town-3d-demo.html` L196-201 **逐行移植**——warm 内嵌、暮色雾数值与
 * 表达式一字不改（图纸 §9 禁改·对版脚本逐行零漂移）。[T_BG_FS]：天空 **7 停靠**竖向渐变 + 椭圆辉光（demo
 * DOM `.sky`/`.glow` → GL·值同源·中心 (0.50,0.720)·半径 0.715×0.200·α 0.5→0.15→0·screen 混合同 9b 公式）。
 * 顶点着色器与 emis 片元**复用** [com.situ.aichat.ui.world.continent.ContinentShaders].C_VS / C_FS_EMIS（不改）。
 */
internal object TownShaders {

    /** 地形/建筑/环境件片元（demo:L196-201 逐行·暖阳 warm 内嵌 + 暮色雾）。 */
    const val T_FS_LIT = """
precision mediump float;varying vec3 vN;varying vec3 vC;varying float vD;
uniform vec3 uSun;
void main(){float d=max(dot(normalize(vN),normalize(uSun)),0.0);
 vec3 warm=vec3(1.0,0.86,0.70);vec3 col=vC*(0.42+0.75*d*warm);
 col=mix(col,vec3(0.79,0.54,0.46),clamp((vD-26.0)/34.0,0.0,0.45));
 gl_FragColor=vec4(col,1.0);}
"""

    /**
     * 背景天空片元（§4.1E·值同源）：竖向 **7 停靠**渐变（`uSky[7]`+`uSkyPos[7]`）+ 椭圆辉光（中心 (0.50,0.720)·
     * 半径 0.715×0.200·(255,214,150)α0.5@0→(255,196,130)α0.15@0.45→透明@0.70·screen 叠加·整体 ×uGlowA）。
     * VS 复用 PlanetShaders.BG_VS（vUv 顶=0 底=1）。
     */
    const val T_BG_FS = """
precision mediump float;
varying vec2 vUv;
uniform vec3 uSky[7];
uniform float uSkyPos[7];
uniform float uGlowA;
vec3 gradAt(float y){
  if(y<=uSkyPos[1]) return mix(uSky[0],uSky[1],clamp((y-uSkyPos[0])/(uSkyPos[1]-uSkyPos[0]),0.0,1.0));
  if(y<=uSkyPos[2]) return mix(uSky[1],uSky[2],clamp((y-uSkyPos[1])/(uSkyPos[2]-uSkyPos[1]),0.0,1.0));
  if(y<=uSkyPos[3]) return mix(uSky[2],uSky[3],clamp((y-uSkyPos[2])/(uSkyPos[3]-uSkyPos[2]),0.0,1.0));
  if(y<=uSkyPos[4]) return mix(uSky[3],uSky[4],clamp((y-uSkyPos[3])/(uSkyPos[4]-uSkyPos[3]),0.0,1.0));
  if(y<=uSkyPos[5]) return mix(uSky[4],uSky[5],clamp((y-uSkyPos[4])/(uSkyPos[5]-uSkyPos[4]),0.0,1.0));
  return mix(uSky[5],uSky[6],clamp((y-uSkyPos[5])/(uSkyPos[6]-uSkyPos[5]),0.0,1.0));
}
void main(){
  vec3 col=gradAt(vUv.y);
  vec2 e=vec2((vUv.x-0.50)/0.715,(vUv.y-0.720)/0.200);
  float ed=length(e);
  float t0=smoothstep(0.0,0.45,ed);
  float t1=smoothstep(0.45,0.70,ed);
  float ga=(ed<0.45 ? mix(0.5,0.15,t0) : mix(0.15,0.0,t1))*uGlowA;
  vec3 gcol=mix(vec3(1.0,0.8392157,0.5882353),vec3(1.0,0.7686275,0.5098039),t0);
  vec3 src=gcol*ga;
  col=1.0-(1.0-col)*(1.0-src);
  gl_FragColor=vec4(col,1.0);
}
"""
}
