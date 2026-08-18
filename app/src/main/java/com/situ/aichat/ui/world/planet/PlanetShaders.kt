package com.situ.aichat.ui.world.planet

/**
 * 全部 GLSL 源串（W9a 图纸 §4.1）。GLES2 语法（`attribute`/`varying`/`gl_FragColor`/`precision`）。
 *
 * [VS]/[FS_PLANET]/[FS_CLOUD]/[NOISE]：从对版 demo `design/world/planet-3d-demo.html` L78-152
 * **逐行移植**——所有数值与表达式一字不改（图纸 §9 禁改·复核硬项：与 demo 并排 diff 零漂移）。
 * [BG_VS]/[BG_FS]/[STAR_VS]/[STAR_FS]：demo DOM 背景层（`.space`/`.milky`/`.halo`/`.star`·demo:L5-15,
 * 35,49-52）→ GL 内实现，值同源（图纸 §4.1B）。
 */
internal object PlanetShaders {

    /** 顶点着色器（demo:L78-83）·星球与云共用。 */
    const val VS = """
attribute vec3 aPos;
uniform mat4 uMVP; uniform mat4 uModel; uniform float uScale;
varying vec3 vN; varying vec3 vLocal;
void main(){ vLocal=aPos; vN=mat3(uModel[0].xyz,uModel[1].xyz,uModel[2].xyz)*aPos;
  gl_Position=uMVP*vec4(aPos*uScale,1.0); }
"""

    /** 值噪声块（demo:L86-94）·注入星球/云 FS。 */
    private const val NOISE = """
float hash(vec3 p){ return fract(sin(dot(p,vec3(127.1,311.7,74.7)))*43758.5453123); }
float vnoise(vec3 p){ vec3 i=floor(p); vec3 f=fract(p); f=f*f*(3.0-2.0*f);
  float n000=hash(i+vec3(0.,0.,0.)), n100=hash(i+vec3(1.,0.,0.));
  float n010=hash(i+vec3(0.,1.,0.)), n110=hash(i+vec3(1.,1.,0.));
  float n001=hash(i+vec3(0.,0.,1.)), n101=hash(i+vec3(1.,0.,1.));
  float n011=hash(i+vec3(0.,1.,1.)), n111=hash(i+vec3(1.,1.,1.));
  return mix(mix(mix(n000,n100,f.x),mix(n010,n110,f.x),f.y),
             mix(mix(n001,n101,f.x),mix(n011,n111,f.x),f.y),f.z); }
float fbm(vec3 p){ float v=0.0,a=0.5; for(int k=0;k<5;k++){ v+=a*vnoise(p); p*=2.03; a*=0.5; } return v; }
"""

    /** 星球片元（demo:L97-139·逐行移植）。 */
    val FS_PLANET = """
precision highp float;
varying vec3 vN; varying vec3 vLocal;
uniform vec3 uSun; uniform float uSeedOff;
$NOISE
void main(){
  vec3 n=normalize(vN); vec3 ln=normalize(vLocal);
  vec3 p=ln*2.0+vec3(uSeedOff);
  float cont=fbm(p);
  float land=smoothstep(0.50,0.535,cont);
  float detail=fbm(ln*6.0+vec3(uSeedOff*1.7));
  // 海洋：暖调青蓝(远方蓝族 scene.ocean)·近岸变浅
  float shore=smoothstep(0.43,0.50,cont);
  vec3 oceanDeep=vec3(0.173,0.259,0.322);
  vec3 oceanMain=vec3(0.243,0.361,0.431);
  vec3 oceanShal=vec3(0.435,0.573,0.612);
  vec3 ocean=mix(mix(oceanDeep,oceanMain,smoothstep(0.25,0.45,cont)),oceanShal,shore*0.7);
  // 大陆三色：莎草/沙土/陶原 + 极地雪
  vec3 sage=vec3(0.561,0.639,0.494);
  vec3 sand=vec3(0.851,0.765,0.639);
  vec3 clay=vec3(0.769,0.643,0.518);
  vec3 landC=mix(sage,sand,smoothstep(0.35,0.65,detail));
  landC=mix(landC,clay,smoothstep(0.62,0.85,fbm(ln*3.3+vec3(9.2))));
  float polar=smoothstep(0.72,0.82,abs(ln.y)+detail*0.08);
  landC=mix(landC,vec3(0.93,0.92,0.90),polar);
  vec3 base=mix(ocean,landC,land);
  // 光照：昼夜 + 柔和晨昏线
  float ndl=dot(n,normalize(uSun));
  float day=smoothstep(-0.12,0.28,ndl);
  vec3 lit=base*(0.16+1.02*day);
  // 海面高光（昼侧）
  vec3 view=vec3(0.0,0.0,1.0);
  vec3 h=normalize(normalize(uSun)+view);
  float spec=pow(max(dot(n,h),0.0),42.0)*(1.0-land)*day;
  lit+=vec3(1.0,0.88,0.72)*spec*0.55;
  // 夜面城市灯：陆地暗面·成簇碎金
  float clus=smoothstep(0.55,0.75,fbm(ln*4.0+vec3(3.7)));
  float dots=step(0.90,vnoise(ln*60.0+vec3(uSeedOff)));
  float night=1.0-smoothstep(-0.18,0.05,ndl);
  lit+=vec3(0.910,0.773,0.494)*dots*clus*land*night*(1.0-polar)*0.95;
  // 大气边缘光：暮色蓝→暖(昼侧偏暖)
  float fres=pow(1.0-max(dot(n,view),0.0),2.6);
  vec3 rim=mix(vec3(0.32,0.42,0.62),vec3(0.86,0.62,0.48),day*0.6);
  lit+=rim*fres*0.55;
  gl_FragColor=vec4(lit,1.0); }
"""

    /** 云层片元（demo:L141-152·逐行移植）。 */
    val FS_CLOUD = """
precision highp float;
varying vec3 vN; varying vec3 vLocal;
uniform vec3 uSun; uniform float uTime;
$NOISE
void main(){
  vec3 n=normalize(vN); vec3 ln=normalize(vLocal);
  vec3 p=ln*3.0+vec3(uTime*0.012,0.0,uTime*0.007);
  float c=fbm(p);
  float a=smoothstep(0.52,0.72,c)*0.55;
  float ndl=dot(n,normalize(uSun));
  float day=smoothstep(-0.12,0.28,ndl);
  vec3 col=vec3(0.96,0.94,0.92)*(0.25+0.85*day);
  gl_FragColor=vec4(col,a*(0.25+0.75*day)); }
"""

    // ────────────────── 背景与星空（demo DOM → GL·值同源·图纸 §4.1B）──────────────────

    /** 背景全屏 quad VS：vUv = 屏幕 uv（(0,0)=左上·CSS 同向）。 */
    const val BG_VS = """
attribute vec2 aPos;
varying vec2 vUv;
void main(){ vUv=vec2(aPos.x*0.5+0.5, 0.5-aPos.y*0.5); gl_Position=vec4(aPos,0.0,1.0); }
"""

    /**
     * 背景 FS：竖向渐变（demo:L7）+ 椭圆辉光（demo:L6）+ 银河斜带（demo:L8-10·smoothstep 软边=blur 等效）
     * + 中央光晕（demo:L11-13）。合成序 = space → milky → halo（DOM z 序·demo:L35）。
     */
    val BG_FS = """
precision highp float;
varying vec2 vUv;
uniform vec2 uResolution;
vec3 screenBlend(vec3 b, vec3 s){ return 1.0-(1.0-b)*(1.0-s); }
void main(){
  // ① 竖向渐变 #0B0F1B → 55% #141C36 → #232447
  vec3 c0=vec3(0.0431,0.0588,0.1059);
  vec3 c1=vec3(0.0784,0.1098,0.2118);
  vec3 c2=vec3(0.1373,0.1412,0.2784);
  float t=vUv.y;
  vec3 col = t<0.55 ? mix(c0,c1,t/0.55) : mix(c1,c2,(t-0.55)/0.45);
  // ② 椭圆辉光 #3A3050 at 72%,108%·半径 90%×60%·0→55% 透明
  vec2 e=vec2((vUv.x-0.72)/0.90,(vUv.y-1.08)/0.60);
  float ed=length(e);
  float glow=clamp(1.0-ed/0.55,0.0,1.0);
  col=mix(col,vec3(0.2275,0.1882,0.3137),glow);
  // 像素/vmin 度量（银河与光晕用）
  vec2 px=vUv*uResolution;
  vec2 ctr=uResolution*0.5;
  float vmin=min(uResolution.x,uResolution.y);
  // ③ 银河：-13° 斜带·中心 14% 高·厚 0.18H·三段 alpha·screen 叠加
  vec2 mc=vec2(uResolution.x*0.5, uResolution.y*0.14+0.09*uResolution.y);
  float a13=0.226893; float ca=cos(a13), sa=sin(a13);
  vec2 d=px-mc;
  vec2 lp=vec2(ca*d.x - sa*d.y, sa*d.x + ca*d.y);
  float halfT=0.09*uResolution.y;
  float across=1.0-smoothstep(halfT*0.55, halfT, abs(lp.y));
  float along=lp.x/(1.70*uResolution.x)+0.5;
  float seg = along<0.30 ? mix(0.0,0.12,along/0.30)
            : along<0.50 ? mix(0.12,0.18,(along-0.30)/0.20)
            : along<0.70 ? mix(0.18,0.10,(along-0.50)/0.20)
            : along<1.0  ? mix(0.10,0.0,(along-0.70)/0.30) : 0.0;
  float milky=across*seg;
  col=mix(col,screenBlend(col,vec3(0.863,0.812,0.922)),milky);
  // ④ 中央光晕 rgba(150,180,230)·0.14@40% 0.05@55% 0@66%·78vmin
  float g=length(px-ctr)/(0.39*vmin);
  float ha = g<0.40 ? 0.14
           : g<0.55 ? mix(0.14,0.05,(g-0.40)/0.15)
           : g<0.66 ? mix(0.05,0.0,(g-0.55)/0.11) : 0.0;
  col=mix(col,vec3(0.588,0.706,0.902),ha);
  gl_FragColor=vec4(col,1.0);
}
"""

    /** 星点 VS：aStar = (x_ndc, y_ndc, 基础尺寸 px, 相位)；点尺寸按 [uPointScale]（DPR≤2）放大。 */
    const val STAR_VS = """
attribute vec4 aStar;
uniform float uPointScale;
varying float vPhase;
void main(){ vPhase=aStar.w; gl_Position=vec4(aStar.xy,0.0,1.0); gl_PointSize=aStar.z*uPointScale; }
"""

    /**
     * 星点 FS：圆点 + 4.5s 正弦闪烁 0.85↔0.2（demo:L15·相位各异）·色 #F5EFEA；
     * [uAnim]=0（静帧/reduceMotion）→ 恒 0.85 不闪。
     */
    val STAR_FS = """
precision mediump float;
varying float vPhase;
uniform float uTime;
uniform float uAnim;
void main(){
  float tw = 0.85 - uAnim*0.65*(0.5 - 0.5*sin(6.2831853*uTime/4.5 + vPhase));
  vec2 d = gl_PointCoord - 0.5;
  float m = smoothstep(0.5,0.2,length(d));
  gl_FragColor = vec4(vec3(0.9608,0.9373,0.9176)*tw, tw*m);
}
"""
}
