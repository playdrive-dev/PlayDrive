package app.playdrive.ui.light

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 빛을 재료로 쓰는 화면(AGSL, API 33+). 무드 화면과 마음 앨범 커버가 같은 셰이더를 쓴다(coverMode).
 *
 * 뒤 → 앞:
 *  1) 시간대 색온도 그라데이션(OKLCh 보간 — 노을 중간이 탁해지지 않게) + 바닥 반사 띠 근처의 옅은 빛 + 아침 햇빛
 *  2) 메인 빛: 가우시안 세 겹(심·몸통·후광) + 가장자리 노이즈로 흩어짐
 *  3) 보조 빛: 깊이(크기·흐림·세기)가 다른 빛들(오늘 커버의 겹)
 *  4) 물결: 빛이 바닥에 반사된 듯한 가는 하이라이트 띠(빛 아래일수록 밝음).
 *     테이프스톱 자국(앨범 커버만): 그 자리에서 띠가 한 번 부드럽게 출렁이며 넓게 흩어짐
 *  5) 비: 앞유리 물방울 — 대부분 투명한 작은 렌즈(뒤의 장면이 뒤집혀 비침), 빛 쪽에만 작은 반짝임,
 *     작은 방울이 많고 큰 방울은 적다. 3~5개는 아래로 흘러내린 자국
 *  6) 미세 필름 그레인(픽셀 고정, 깜빡이지 않음 — 밴딩 제거용)
 *  + 유리 패널(재생·앨범 버튼 뒤): 같은 장면을 더 흐리게 + 살짝 밝게
 */
object LightShader {
    const val SRC = """
uniform float2 iResolution;
uniform float iTime;
uniform float coverMode;
uniform float3 bgTop;
uniform float3 bgBottom;
uniform float3 horizon;
uniform float4 mainLight;
uniform float3 mainColor;
uniform float calm;
uniform float4 lights[14];
uniform float4 lightCol[14];
uniform float lightCount;
uniform float4 ring;
uniform float4 scars[4];
uniform float scarCount;
uniform float bandY;
uniform float bandWave;
uniform float rain;
uniform float rainSeed;
uniform float rainAlpha;   // 물방울 페이드(0~1)
uniform float4 glass[4];
uniform float4 glassRad;
uniform float glassCount;
uniform float master;
uniform float4 sun;
uniform float3 sunCol;
// LIVE 효과(주행 중 음악 반응). 커버·CALM에선 0
uniform float4 ripple;     // 드롭 물결: (중심 x, 진행 0~1, 세기, 켜짐)
uniform float2 sideGlow;   // 깜빡이: 왼쪽·오른쪽 가장자리 번짐 0~1
uniform float sparkle;     // 킥에 맞춘 물방울 반짝임 0~1

const float TAU = 6.2831853;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

// 부드러운 빛 한 덩어리: soft 0 = 또렷한 심, 1 = 흐린 빛. n = 가장자리 흩어짐 노이즈(0~1)
float bloom(float2 p, float2 c, float r, float soft, float n) {
    float d = length(p - c);
    float dn = d * (1.0 + (n - 0.5) * (0.25 + 0.35 * soft) * smoothstep(0.3 * r, 1.6 * r, d));
    float a = dn / (r * 0.30);
    float b = dn / r;
    float h = dn / (r * 2.0);
    return exp(-a * a) * (1.0 - 0.7 * soft) * 0.9 + exp(-b * b) * 0.55 + exp(-h * h) * 0.16;
}

float sdRound(float2 q, float4 r, float rad) {
    float2 c = (r.xy + r.zw) * 0.5;
    float2 hs = (r.zw - r.xy) * 0.5 - rad;
    float2 d = abs(q - c) - hs;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - rad;
}

// OKLCh(L, C, h) → sRGB. bgTop·bgBottom은 OKLCh로 들어온다(LightParams.gradientLch).
float3 fromLch(float3 c) {
    float A = c.y * cos(c.z);
    float B = c.y * sin(c.z);
    float l = c.x + 0.3963377774 * A + 0.2158037573 * B;
    float m = c.x - 0.1055613458 * A - 0.0638541728 * B;
    float s = c.x - 0.0894841775 * A - 1.2914855480 * B;
    l = l * l * l; m = m * m * m; s = s * s * s;
    float3 lin = float3(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
    return pow(clamp(lin, 0.0, 1.0), float3(1.0 / 2.2));
}

float3 bgCol(float2 p) { return fromLch(mix(bgTop, bgBottom, smoothstep(0.0, 1.0, p.y))); }

float3 sceneCol(float2 p, float blur) {
    float3 bg = bgCol(p);
    float hz = (p.y - bandY) / 0.20;
    bg += horizon * exp(-hz * hz) * 0.14;

    // 느린 흩어짐: 600초에 한 바퀴 도는 원 위에서 노이즈를 읽는다(순환해도 이어짐)
    float2 drift = 0.6 * float2(cos(TAU * iTime / 600.0), sin(TAU * iTime / 600.0));
    float n = noise(p * 3.2 + drift);

    float3 light = float3(0.0);
    float3 refl = float3(0.0);
    if (sun.w > 0.0) {
        // 오른쪽 위에서 비쳐 드는 옅은 햇빛: 넓고 부드러운 두 겹
        float2 q = (p - sun.xy) / (sun.z * (1.0 + blur * 2.0));
        float d2 = dot(q, q);
        light += sunCol * sun.w * (exp(-d2) * 0.55 + exp(-d2 * 0.18) * 0.30);
    }
    for (int i = 0; i < 14; i++) {
        if (float(i) < lightCount) {
            float4 L = lights[i];
            float4 C = lightCol[i];
            float depth = L.w;
            float r = L.z * (1.0 + depth * 0.9) * (1.0 + blur * 2.5);
            float soft = clamp(0.25 + depth * 0.75 + blur * 3.0, 0.0, 1.0);
            float w = C.a * (1.0 - depth * 0.5);
            light += C.rgb * w * bloom(p, L.xy, r, soft, n);
            float rx = (p.x - L.x) / (r * 2.0);
            refl += C.rgb * w * exp(-rx * rx);
        }
    }
    if (mainLight.w > 0.0) {
        float r = mainLight.z * (1.0 + blur * 2.5);
        float soft = clamp(0.35 + 0.45 * (1.0 - calm) + blur * 3.0, 0.0, 1.0);
        light += mainColor * mainLight.w * bloom(p, mainLight.xy, r, soft, n);
        float rx = (p.x - mainLight.x) / (r * 2.2);
        refl += mainColor * mainLight.w * exp(-rx * rx) * 1.3;
    }
    if (ring.w > 0.0) {
        float d = (abs(length(p - ring.xy) - ring.z)) / (0.004 + blur * 0.05);
        light += float3(1.0, 0.96, 0.9) * ring.w * exp(-d * d) * 0.55;
    }

    // 바닥 반사: 가는 하이라이트 띠 두 줄. 테이프스톱 자국 = 그 자리에서 띠가 한 번 흐트러짐
    float disp = bandWave * sin(p.x * 7.0 + TAU * iTime / 12.0) + bandWave * 0.5 * sin(p.x * 13.0 - TAU * iTime / 10.0);
    if (ripple.w > 0.0) {
        // 드롭: 중심에서 좌우로 퍼지는 물결 한 번(진행할수록 넓게, 옅게)
        float front = ripple.y * iResolution.x / iResolution.y * 0.65;
        float rd = (abs(p.x - ripple.x) - front) / 0.09;
        disp += ripple.z * (1.0 - ripple.y) * exp(-rd * rd) * sin(rd * 3.0);
    }
    float broken = 1.0;
    float spreadW = 0.0;
    for (int k = 0; k < 4; k++) {
        if (float(k) < scarCount) {
            // 한 번 부드럽게 출렁이고(사인 한 주기), 그 자리의 띠가 넓고 옅게 흩어진다
            float sx = (p.x - scars[k].x) / 0.10;
            float s = scars[k].y * exp(-sx * sx);
            disp += s * 0.020 * sin(sx * 2.6 + float(k));
            spreadW += s * 2.2;
            broken *= 1.0 - s * 0.35;
        }
    }
    float bw = (0.0022 + blur * 0.03) * (1.0 + spreadW);
    float b1 = (p.y - bandY - disp) / bw;
    float b2 = (p.y - bandY - 0.032 - disp * 1.4) / (bw * 1.4);
    float band = (exp(-b1 * b1) + 0.35 * exp(-b2 * b2)) / (1.0 + spreadW * 0.5);
    float under = smoothstep(bandY, bandY + 0.25, p.y) * (1.0 - smoothstep(bandY + 0.05, bandY + 0.35, p.y));
    float3 floorGlow = refl * (band * broken * 0.75 + under * 0.08);

    float3 total = light + floorGlow;
    // 빛은 '스크린' 합성으로 더한다(바탕 색을 지키며 밝게)
    return bg + (1.0 - bg) * (1.0 - exp(-total * 1.1));
}

// 작은 렌즈 하나: (굴절 오프셋 xy, 반짝임). v = 렌즈 중심 기준 위치 / 반지름, rp = 반지름(장면 좌표).
// 뒤의 장면을 뒤집어 비춘다(중심 대칭 + 약간 넓게). 테두리는 그리지 않는다.
float3 lens(float2 v, float rp, float2 toLight) {
    float d = length(v);
    if (d >= 1.0) return float3(0.0);
    float z = sqrt(1.0 - d * d);
    float2 off = -v * rp * (1.6 + 0.8 * z);
    // 빛이 있는 쪽 가장자리 안쪽에 작은 반짝임
    float2 sp = (v - toLight * 0.55) / 0.22;
    return float3(off, exp(-dot(sp, sp)));
}

// 앞유리 물방울: (굴절 오프셋 xy, 반짝임, 흐름 자국의 젖은 정도)
float4 drops(float2 p, float2 lightPos) {
    float4 res = float4(0.0);
    float aspect = iResolution.x / iResolution.y;
    float cell = 0.045;
    float2 g = p / cell;
    float2 id = floor(g);
    float2 f = fract(g) - 0.5;
    float h = hash(id + rainSeed);
    if (h < rain * 0.40) {
        float hs = hash(id + 11.3);
        float rad = 0.06 + 0.32 * hs * hs * hs;              // 작은 방울이 대부분, 큰 방울은 드물게
        float2 c = (float2(hash(id + 3.1), hash(id + 7.7)) - 0.5) * (0.9 - 2.0 * rad);
        float2 toL = normalize(lightPos - (id + 0.5 + c) * cell + 1e-4);
        float3 L = lens((f - c) / rad, rad * cell, toL);
        res.xy += L.xy;
        res.z = max(res.z, L.z * smoothstep(0.13, 0.26, rad));   // 반짝임은 큰 방울에만
    }
    // 흘러내린 자국 3~5개: 가는 젖은 줄 + 끝에 맺힌 방울
    float nt = 3.0 + floor(hash(float2(rainSeed, 5.0)) * 2.99);
    for (int k = 0; k < 5; k++) {
        if (float(k) < nt && rain > 0.05) {
            float fk = float(k);
            float tx = (0.06 + 0.88 * fract(hash(float2(rainSeed + fk * 13.1, 1.7)) + fk * 0.21)) * aspect;
            float ty = 0.04 + 0.40 * hash(float2(rainSeed + fk * 5.3, 2.9));
            float len = 0.12 + 0.28 * hash(float2(rainSeed + fk * 2.7, 4.1));
            float yy = (p.y - ty) / len;
            float wob = 0.0012 * sin(p.y * 37.0 + fk * 2.0) + 0.0006 * sin(p.y * 91.0 + fk);
            if (yy > 0.0 && yy < 1.0) {
                float w = 0.0012 + 0.0014 * yy;
                float dx = (p.x - tx - wob) / w;
                float wet = exp(-dx * dx) * smoothstep(0.0, 0.35, yy) * (0.55 + 0.45 * step(0.3, noise(float2(p.y * 70.0, fk * 9.0))));
                res.x += -dx * w * 0.6 * wet;
                res.w = max(res.w, wet);
            }
            float2 dc = float2(tx + 0.0012 * sin((ty + len) * 37.0 + fk * 2.0) + 0.0006 * sin((ty + len) * 91.0 + fk), ty + len + 0.004);
            float rp = 0.0075;
            float3 L = lens((p - dc) / rp, rp, normalize(lightPos - dc + 1e-4));
            res.xy += L.xy;
            res.z = max(res.z, L.z);
        }
    }
    return res;
}

half4 main(float2 fc) {
    float2 p = fc / iResolution.y;
    float blur = 0.0;
    float edge = 0.0;
    for (int i = 0; i < 4; i++) {
        if (float(i) < glassCount) {
            float rad = i == 0 ? glassRad.x : (i == 1 ? glassRad.y : (i == 2 ? glassRad.z : glassRad.w));
            float s = sdRound(fc, glass[i], rad);
            if (s < 0.0) { blur = 0.045; edge = 1.0; }
        }
    }
    float4 dr = float4(0.0);
    if (rain > 0.001) {
        float2 lp = mainLight.w > 0.0 ? mainLight.xy : (lightCount > 0.0 ? lights[0].xy : float2(iResolution.x / iResolution.y * 0.7, 0.2));
        dr = drops(p, lp) * rainAlpha;
    }
    float3 col = sceneCol(p + dr.xy, blur);
    float3 W = float3(0.299, 0.587, 0.114);
    float lum = dot(col, W);
    // 반짝임은 빛이 있는 곳에서만: 바탕보다 밝은 만큼(빛의 몫)에 비례. 빛 없는 바탕에선 거의 보이지 않는다.
    if (dr.z > 0.0) {
        float lit = smoothstep(0.08, 0.30, lum - dot(bgCol(p), W));
        col += float3(1.0, 0.97, 0.92) * dr.z * 0.45 * (1.0 + 0.8 * sparkle) * lit;
    }
    col += col * dr.w * 0.06;
    if (sideGlow.x + sideGlow.y > 0.0) {
        // 깜빡이: 그 방향 가장자리에 호박색 빛이 은은하게 번짐(세로로는 가운데가 진하게)
        float ar = iResolution.x / iResolution.y;
        float vy = exp(-pow((p.y - 0.45) / 0.45, 2.0));
        float g = (sideGlow.x * exp(-p.x / 0.14) + sideGlow.y * exp(-(ar - p.x) / 0.14)) * vy;
        col += (1.0 - col) * float3(1.0, 0.70, 0.36) * g * 0.50;
    }
    // 유리: 어두운 바탕에선 살짝 밝게, 밝은 바탕(노을 아래쪽 등)에선 살짝 어둡게 → 흰 선 아이콘이 늘 읽힌다
    if (edge > 0.5) col = mix(mix(col, float3(1.0), 0.07), col * 0.78, smoothstep(0.35, 0.55, lum));
    col *= master;
    // 필름 그레인(픽셀마다 고정)
    col += (hash(fc * 0.731 + 17.0) - 0.5) * (2.2 / 255.0);
    return half4(half3(col), 1.0);
}
"""

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun create(): RuntimeShader = RuntimeShader(SRC)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun apply(shader: RuntimeShader, u: Map<String, FloatArray>) {
        for ((k, v) in u) shader.setFloatUniform(k, v)
    }
}
