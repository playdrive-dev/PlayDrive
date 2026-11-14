package app.playdrive.ui.light

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 흐르는 그라데이션 물결(AGSL, API 33+). 주행 화면과 드라이브 앨범·결과 커버가 같은 셰이더를 쓴다(coverMode).
 * 차량 실내 앰비언트 라이트와 이어지게: 위는 어둡고, 아래로 갈수록 물결이 겹치며 은은하게 밝아지고, 맨 아래엔 앰비언트 선.
 *
 * 물결 한 줄 = 악기 하나(ui/WaveMotion). 뒤 → 앞: 패드(넓은 면) · 하이햇(맨 위 가는 결) · 코드 · 베이스 · 드럼, 그리고 멜로디의 가는 선.
 *  - 면 밝기는 물결 높이와 무관하게 정규화(물결이 오르내려도 화면 평균 밝기는 그대로 → 밝기 변화는 악기 수·어두워짐에서만, 초당 10% 이내)
 *  - 타격(킥·스네어)·보상은 모양만 움직인다(파동). 반짝이는 입자·원형 빛·링 없음
 *  + 유리 패널(재생 버튼 뒤): 그 자리만 살짝 밝게/어둡게 해 흰 아이콘이 늘 읽히게
 */
object LightShader {
    const val SRC = """
uniform float2 iResolution;
uniform float iTime;
uniform float coverMode;
uniform float3 bgTop;      // OKLCh
uniform float3 bgBottom;   // OKLCh
uniform float3 waveCol;
uniform float3 accent;
uniform float4 band;       // 노을 지평선 쪽 물결 끝의 얇은 주황(rgb, 세기). 세기 0이면 없음
uniform float4 amb;        // 바닥 앰비언트 선(rgb, 킥에 맞춘 밝아짐 0~1)
uniform float4 L[6];       // 물결: (기준 y, 진폭, 위상, 파수)
uniform float4 M[6];       // (가장자리 보임, 면 밝기, 부풀음, 접힘)
uniform float4 X[6];       // (색 변화, 잔물결, 모양 위상, 0)
uniform float4 mel[8];     // 멜로디 음 높이 32점(0~1)
uniform float4 melA[8];    // 멜로디 보임 32점
uniform float3 melBase;    // (기준 y, 높이 폭, 스크롤 0~1)
uniform float4 P[6];       // 박자 파동(진행파): (정점으로부터 ms, 진폭, 시작 가로 위치 0~1, 종류 0 킥·1 넓은·2 스네어)
uniform float4 G;          // (평평, 어두움, 처짐, 바램)
uniform float4 G2;         // (곡 진행, 비, 깜빡이 왼쪽, 오른쪽)
uniform float4 scars[4];   // 커버: 흔들림 자국 (x, 세기)
uniform float scarCount;
uniform float4 glass[4];
uniform float4 glassRad;
uniform float glassCount;
uniform float master;

const float TAU = 6.2831853;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float sdRound(float2 q, float4 r, float rad) {
    float2 c = (r.xy + r.zw) * 0.5;
    float2 hs = (r.zw - r.xy) * 0.5 - rad;
    float2 d = abs(q - c) - hs;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - rad;
}

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

float lum(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }

// 악기별 면 밝기 몫(ui/WaveState.WEIGHT와 같은 비율)
float weight(int i) {
    if (i == 0) return 0.16;
    if (i == 1) return 0.06;
    if (i == 2) return 0.15;
    if (i == 3) return 0.13;
    return 0.14;
}

float3 sat(float3 c, float k) { float l = lum(c); return clamp(float3(l) + (c - float3(l)) * k, 0.0, 1.0); }

// 물결 색(같은 계열 안에서 악기마다): 뒤(패드·코드) = 바탕보다 조금 밝은 옅은 색, 베이스 = 가장 진하고 채도 높게,
// 드럼 = 바닥 앰비언트 쪽으로, 하이햇 = 포인트 쪽 옅은 결
float3 lineColor(int i, float tint, float3 bottomCol) {
    if (i == 0) return mix(bottomCol, waveCol, 0.45);
    if (i == 1) return mix(waveCol, accent, 0.35);
    if (i == 2) {
        float3 c = mix(bottomCol, waveCol, 0.70);
        float3 t = tint > 0.0 ? mix(c, float3(0.95, 0.80, 0.62), 0.30) : mix(c, float3(0.55, 0.72, 0.98), 0.30);
        return mix(c, t, abs(tint) * 0.5);
    }
    if (i == 3) return sat(waveCol * 0.80, 1.45);
    return mix(waveCol, amb.rgb, 0.30);
}

// 파동 포락선(ui/WaveGeom.env와 같은 식): 정점 80ms 전부터 부드럽게 차오름 → 박자에 정점 → 약 0.8초에 걸쳐 풀림
float env(float t) {
    if (t < -80.0) return 0.0;
    if (t < 0.0) { float u = (t + 80.0) / 80.0; return u * u * (3.0 - 2.0 * u); }
    float k = t / 170.0;
    return k > 12.0 ? 0.0 : (1.0 + k) * exp(-k);
}

float pw(int kind, int i) {
    if (kind == 0) return i == 0 ? 0.30 : (i == 1 ? 0.20 : (i == 2 ? 0.45 : (i == 3 ? 0.70 : 1.0)));
    if (kind == 1) return i == 0 ? 0.80 : (i == 1 ? 0.60 : (i == 2 ? 0.90 : 1.0));
    return i <= 1 ? 0.0 : (i == 2 ? 0.30 : (i == 3 ? 0.70 : 1.0));
}

// 진행파(ui/WaveGeom.disp와 같은 식): 시작점에서 좌우로 번지고, 앞쪽 물결 먼저·뒤쪽 나중, 겹치면 쌓이되 부드러운 상한
float pulses(float x, float aspect, int i) {
    float delay = i == 0 ? 180.0 : (i == 1 ? 135.0 : (i == 2 ? 90.0 : (i == 3 ? 45.0 : 0.0)));
    float s = 0.0;
    for (int k = 0; k < 6; k++) {
        float4 q = P[k];
        if (q.y > 0.0) {
            int kind = int(q.w + 0.5);
            float w = pw(kind, i);
            float dx = abs(x - q.z * aspect);
            float reach = kind == 1 ? 1.6 : 0.9;
            float r = dx / reach;
            float fall = 0.4 + 0.6 * exp(-r * r);
            s += q.y * w * fall * env(q.x - dx / 1.3 * 1000.0 - delay);
        }
    }
    float d = -s / (1.0 + abs(s) / 0.012);
    for (int k = 0; k < 4; k++) {
        if (float(k) < scarCount) {
            float z = (x - scars[k].x) / 0.06;
            d += scars[k].y * 0.012 * exp(-z * z) * sin(z * 3.2 + float(i));
        }
    }
    return d;
}

float melAt(float4 v, int c) { return c == 0 ? v.x : (c == 1 ? v.y : (c == 2 ? v.z : v.w)); }

half4 main(float2 fc) {
    float aspect = iResolution.x / iResolution.y;
    float2 p = fc / iResolution.y;
    float flt = G.x; float dim = G.y; float sag = G.z; float fade = G.w;
    float rise = G2.x; float rain = G2.y;

    float3 topCol = fromLch(bgTop);
    float3 bottomCol = fromLch(bgBottom);
    float3 col = fromLch(mix(bgTop, bgBottom, smoothstep(0.0, 1.0, p.y)));

    // 물결(뒤 → 앞). 면은 물결 아래쪽, 깊이에 따라 옅어지고, 높이와 무관하게 정규화.
    for (int i = 0; i < 5; i++) {
        float4 Li = L[i]; float4 Mi = M[i]; float4 Xi = X[i];
        if (Mi.x + Mi.y > 0.002) {
            float y = Li.x;
            float a = Li.y * (1.0 - Mi.w);
            y = mix(y, 0.80, flt); a *= 1.0 - flt;
            y += sag * 0.06; a *= 1.0 - 0.6 * sag;
            a *= 1.0 + 0.3 * Mi.z; y -= 0.004 * Mi.z;
            float k = Li.w;
            float curve = y + a * (0.62 * sin(k * p.x + Li.z) + 0.38 * sin(1.73 * k * p.x - 0.7 * Li.z + Xi.z + 1.1 * float(i)));
            curve += pulses(p.x, aspect, i) * (1.0 - 0.7 * flt);
            curve += Xi.y * 0.0026 * sin(p.x * 92.0 + Li.z * 9.0) * (0.6 + 0.4 * sin(p.x * 7.0 - Li.z * 3.0));
            curve += rain * 0.0016 * sin(p.x * 150.0 + float(i) * 1.7 + iTime * 0.62832) * sin(p.x * 23.0 - iTime * 0.31416);
            float dd = p.y - curve;
            // 악기별 질감: 면 깊이(두께)·불투명도·마루 빛
            float depth = 0.12; float alpha = 1.0; float crest = 0.9;
            if (i == 0) { depth = 0.30; alpha = 0.55; crest = 0.35; }
            else if (i == 1) { depth = 0.035; alpha = 0.80; crest = 0.9; }
            else if (i == 2) { depth = 0.24; alpha = 0.60; crest = 0.45; }
            else if (i == 3) { depth = 0.20; alpha = 1.30; crest = 0.60; }
            // 가장자리는 선 없이 2~4px로 부드럽게, 윗부분은 같은 색으로 은은하게 밝게(실크·오로라 띠)
            float soft = 2.5 / iResolution.y + 0.002 * Mi.w;
            float inside = smoothstep(-soft, soft, dd);
            float below = max(dd, 0.0);
            float topGlow = exp(-below / 0.016);
            if (i == 1) topGlow *= 0.85 + 0.15 * sin(p.x * 160.0 + Li.z * 14.0) * sin(p.x * 23.0 - Li.z * 5.0);   // 하이햇: 고운 결
            // 물결이 오르내려도 화면 평균 밝기는 같게(면의 총량으로 나눔)
            float span = max(1.0 - y, 0.05);
            float total = depth * (1.0 - exp(-span / depth)) + crest * 0.016 * 1.1;
            float norm = 0.16 / total;
            float body = Mi.y * weight(i) * alpha * exp(-below / depth);
            float glowA = (Mi.y * 0.6 + 0.4 * Mi.x * (1.0 - 0.75 * Mi.w)) * weight(i) * crest * 1.1 * topGlow;
            float aa = inside * (body + glowA) * norm * (1.0 + 0.05 * Mi.z) * (1.0 - 0.5 * flt) * (1.0 + 0.7 * coverMode);   // 커버(작은 정사각형)는 더 또렷하게
            float3 lc = lineColor(i, Xi.x, bottomCol);
            lc = mix(lc, sat(lc * 1.12 + 0.02, 1.15), rise * 0.5);
            float3 gc = lc;
            // 겹칠수록 밝게: 섞기 + 약한 가산
            col = mix(col, gc, clamp(aa * 0.75, 0.0, 0.8)) + gc * aa * 0.22;
            // 노을: 지평선 쪽(맨 뒤) 물결 끝에만 주황이 얇게
            if (i == 0 && band.w > 0.0) col = mix(col, band.rgb, clamp(band.w * 0.55 * inside * exp(-below / 0.010) * Mi.x * (1.0 - flt), 0.0, 1.0));
        }
    }

    // 멜로디: 가는 밝은 선 하나(음 높이를 따라, 오른쪽이 지금)
    float ws = 0.0; float ys = 0.0; float asum = 0.0;
    for (int q = 0; q < 8; q++) {
        float4 Y = mel[q]; float4 A = melA[q];
        for (int c = 0; c < 4; c++) {
            float j = float(q * 4 + c);
            float xj = (j - melBase.z) / 31.0 * aspect * 1.04 - 0.02;
            float z = (p.x - xj) / 0.05;
            float w = exp(-z * z);
            ws += w; ys += w * melAt(Y, c); asum += w * melAt(A, c);
        }
    }
    if (asum > 0.001) {
        float ym = melBase.x - melBase.y * (ys / ws);
        ym = mix(ym, 0.80, flt) + sag * 0.06;
        float al = (asum / ws) * smoothstep(0.0, aspect * 0.18, p.x) * (1.0 - flt);
        // 가는 리본(시간대 포인트 색): 심 2~3px + 옅은 번짐
        float z = (p.y - ym) / (3.0 / iResolution.y + 0.0012);
        float halo = (p.y - ym) / 0.012;
        col = col + (1.0 - col) * accent * al * (exp(-z * z) * 0.70 + exp(-halo * halo) * 0.12);
    }

    // 바닥 앰비언트 선(대시보드 아래 청록 라인과 이어지게) — 늘 같은 밝기
    float bz = (1.0 - p.y) / 0.008;
    float bh = (1.0 - p.y) / 0.045;
    float bw = (1.0 - p.y) / 0.14;
    float ambK = (1.0 + 0.15 * amb.w) * (1.0 - coverMode * 0.5);
    col = col + (1.0 - col) * amb.rgb * (exp(-bz * bz) * 0.85 + exp(-bh * bh) * 0.22 + exp(-bw * bw) * 0.06) * ambK;

    // 깜빡이: 그 방향 가장자리에 은은한 호박색(고정 밝기, 깜빡이지 않음)
    if (G2.z + G2.w > 0.0) {
        float vy = exp(-pow((p.y - 0.62) / 0.40, 2.0));
        float g = (G2.z * exp(-p.x / 0.12) + G2.w * exp(-(aspect - p.x) / 0.12)) * vy;
        col = col + (1.0 - col) * float3(1.0, 0.70, 0.36) * g * 0.35;
    }

    // 위험: 색이 바래고 먹먹하게
    col = mix(col, float3(lum(col)) * 0.92, fade * 0.45);
    col *= 1.0 - dim;

    // 유리: 어두운 바탕에선 살짝 밝게, 밝은 바탕에선 살짝 어둡게
    for (int i = 0; i < 4; i++) {
        if (float(i) < glassCount) {
            float rad = i == 0 ? glassRad.x : (i == 1 ? glassRad.y : (i == 2 ? glassRad.z : glassRad.w));
            if (sdRound(fc, glass[i], rad) < 0.0) col = mix(mix(col, float3(1.0), 0.06), col * 0.80, smoothstep(0.35, 0.55, lum(col)));
        }
    }
    col *= master;
    col += (hash(fc * 0.731 + 17.0) - 0.5) * (2.2 / 255.0);   // 필름 그레인(픽셀 고정) — 밴딩 제거
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
