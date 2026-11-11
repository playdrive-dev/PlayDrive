package app.playdrive.ui.light

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 빛을 재료로 쓰는 화면(AGSL, API 33+). 무드 화면과 마음 앨범 커버가 같은 셰이더를 쓴다(coverMode).
 *
 * 뒤 → 앞:
 *  1) 시간대 색온도 그라데이션 + 바닥 반사 띠 근처의 옅은 빛
 *  2) 메인 빛: 가우시안 세 겹(심·몸통·후광) + 가장자리 노이즈로 흩어짐
 *  3) 보조 빛: 깊이(크기·흐림·세기)가 다른 빛들(오늘 커버의 겹)
 *  4) 물결: 빛이 바닥에 반사된 듯한 가는 하이라이트 띠(빛 아래일수록 밝음), 테이프스톱 자국은 띠가 한 번 흐트러진 곳
 *  5) 비: 앞유리 물방울(작은 원형 렌즈 → 뒤의 빛이 뒤집혀 일그러짐) + 아주 옅은 흐름 자국
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
uniform float4 lights[8];
uniform float4 lightCol[8];
uniform float lightCount;
uniform float4 ring;
uniform float4 scars[4];
uniform float scarCount;
uniform float bandY;
uniform float bandWave;
uniform float rain;
uniform float rainSeed;
uniform float4 glass[2];
uniform float4 glassRad;
uniform float glassCount;
uniform float master;

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

float3 sceneCol(float2 p, float blur) {
    float3 bg = mix(bgTop, bgBottom, smoothstep(0.0, 1.0, p.y));
    float hz = (p.y - bandY) / 0.20;
    bg += horizon * exp(-hz * hz) * 0.14;

    // 느린 흩어짐: 600초에 한 바퀴 도는 원 위에서 노이즈를 읽는다(순환해도 이어짐)
    float2 drift = 0.6 * float2(cos(TAU * iTime / 600.0), sin(TAU * iTime / 600.0));
    float n = noise(p * 3.2 + drift);

    float3 light = float3(0.0);
    float3 refl = float3(0.0);
    for (int i = 0; i < 8; i++) {
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
    float broken = 1.0;
    for (int k = 0; k < 4; k++) {
        if (float(k) < scarCount) {
            float sx = (p.x - scars[k].x) / 0.06;
            float s = scars[k].y * exp(-sx * sx);
            disp += s * (noise(float2(p.x * 110.0, float(k) * 7.0)) - 0.5) * 0.024;
            broken *= 1.0 - s * 0.55 * step(0.45, noise(float2(p.x * 55.0, float(k) * 3.0 + 1.0)));
        }
    }
    float bw = 0.0022 + blur * 0.03;
    float b1 = (p.y - bandY - disp) / bw;
    float b2 = (p.y - bandY - 0.032 - disp * 1.4) / (bw * 1.4);
    float band = exp(-b1 * b1) + 0.35 * exp(-b2 * b2);
    float under = smoothstep(bandY, bandY + 0.25, p.y) * (1.0 - smoothstep(bandY + 0.05, bandY + 0.35, p.y));
    float3 floorGlow = refl * (band * broken * 0.75 + under * 0.08);

    float3 total = light + floorGlow;
    // 빛은 '스크린' 합성으로 더한다(바탕 색을 지키며 밝게)
    return bg + (1.0 - bg) * (1.0 - exp(-total * 1.1));
}

// 앞유리 물방울: (굴절 오프셋 xy, 테두리 그늘, 반짝임)
float4 drops(float2 p) {
    float4 res = float4(0.0);
    for (int k = 0; k < 2; k++) {
        float cell = k == 0 ? 0.060 : 0.034;
        float2 g = p / cell;
        float2 id = floor(g);
        float2 f = fract(g) - 0.5;
        float h = hash(id + rainSeed + float(k) * 17.0);
        if (h < rain * (k == 0 ? 0.42 : 0.55)) {
            float2 c = (float2(hash(id + 3.1), hash(id + 7.7)) - 0.5) * 0.36;
            float rad = 0.12 + 0.14 * hash(id + 11.3);
            float2 v = (f - c) / rad;
            float d = length(v);
            if (d < 1.0) {
                float z = sqrt(1.0 - d * d);
                res.xy += -v * (0.6 + 0.4 * z) * rad * cell * 2.8;
                res.z = max(res.z, smoothstep(0.7, 1.0, d) * 0.6);
                float2 sp = (v - float2(-0.35, -0.42)) / 0.22;
                res.w = max(res.w, exp(-dot(sp, sp)));
            } else if (h < rain * 0.18) {
                // 아주 옅은 흐름 자국: 물방울 위로 가는 세로 줄
                float tx = (f.x - c.x) / (rad * 0.16);
                float above = smoothstep(-0.5, c.y - rad, f.y) * step(f.y, c.y - rad);
                res.x += exp(-tx * tx) * above * 0.0015;
                res.z = max(res.z, exp(-tx * tx) * above * 0.08);
            }
        }
    }
    return res;
}

half4 main(float2 fc) {
    float2 p = fc / iResolution.y;
    float blur = 0.0;
    float edge = 0.0;
    for (int i = 0; i < 2; i++) {
        if (float(i) < glassCount) {
            float rad = i == 0 ? glassRad.x : glassRad.y;
            float s = sdRound(fc, glass[i], rad);
            if (s < 0.0) { blur = 0.045; edge = 1.0; }
        }
    }
    float4 dr = float4(0.0);
    if (rain > 0.001) dr = drops(p);
    float3 col = sceneCol(p + dr.xy, blur);
    col *= 1.0 - dr.z * 0.35;
    col += float3(0.9, 0.95, 1.0) * dr.w * 0.18;
    if (edge > 0.5) col = mix(col, float3(1.0), 0.07);
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
