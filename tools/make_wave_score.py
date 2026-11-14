#!/usr/bin/env python3
"""주행 화면 물결용 '악보' 만들기: 실제 스템(res/raw/song_*.ogg)에서 악기별 움직임을 뽑는다.

화면의 물결 한 줄 = 악기 하나. 물결은 장식이 아니라 지금 들리는 소리로 움직여야 하므로,
앱이 재생하는 바로 그 루프(8마디, 90 BPM, 1,024,000 프레임)를 1/32박(1000프레임 ≈ 20.8ms) 단위로 분석한다.

출력: app/src/main/res/raw/wave_score.txt (한 줄 = 이름 + 1024개 값 0~255)
  pad, keys, bass, perc, guitar : 음량 포락선(RMS, 각자 최댓값 기준 0~255)
  kick, snare                   : 드럼 스템의 저역(<150Hz)·중고역(1.5~6kHz) 온셋 세기(0~255, 온셋 순간에만 값)
  hat                           : 퍼커션 스템 고역(>5kHz) 온셋 세기
  bass_pitch, guitar_pitch      : 음 높이(MIDI×2, 0 = 음 없음). YIN.
  bass_on                       : 베이스 음이 새로 칠 때 세기
앱(ui/WaveScore.kt)은 (들리는 격자 위치 − 곡 시작) mod 루프 → 칸 번호로 읽는다.

사용: python3 tools/make_wave_score.py
"""
import subprocess
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "app/src/main/res/raw"
SR = 48_000
PAD = 4096
LOOP = 1_024_000
HOP = 1000
STEPS = LOOP // HOP  # 1024


def decode(name: str) -> np.ndarray:
    cmd = ["ffmpeg", "-v", "error", "-i", str(RAW / f"song_{name}.ogg"), "-f", "f32le", "-ar", str(SR), "-ac", "1", "-"]
    x = np.frombuffer(subprocess.run(cmd, check=True, capture_output=True).stdout, dtype=np.float32)
    return x[PAD:PAD + LOOP].astype(np.float64)


def frames(x: np.ndarray, win: int) -> np.ndarray:
    """칸마다 [칸 시작 - win/2, 칸 시작 + win/2) 창(순환)."""
    xx = np.concatenate([x[-win:], x, x[:win]])
    idx = np.arange(STEPS) * HOP + win - win // 2
    return np.stack([xx[i:i + win] for i in idx])


def rms_env(x: np.ndarray) -> np.ndarray:
    f = frames(x, 2048)
    r = np.sqrt((f ** 2).mean(axis=1))
    # 맨 앞 한 번의 큰 소리(패드 첫 타격 등)에 끌려가지 않게 97% 지점을 최대로 본다
    return np.clip(r / (np.percentile(r, 97) + 1e-12), 0, 1)


def band_energy(x: np.ndarray, lo: float, hi: float) -> np.ndarray:
    f = frames(x, 1024) * np.hanning(1024)
    s = np.abs(np.fft.rfft(f, axis=1)) ** 2
    fr = np.fft.rfftfreq(1024, 1 / SR)
    return s[:, (fr >= lo) & (fr < hi)].sum(axis=1)


def onsets(e: np.ndarray, rel: float = 0.18) -> np.ndarray:
    """에너지 → 온셋 세기(상승분, 국소 최대만). 순환."""
    d = np.log1p(e / (np.median(e) + 1e-12))
    flux = np.maximum(0, d - np.roll(d, 1))
    peak = (flux >= np.roll(flux, 1)) & (flux >= np.roll(flux, -1)) & (flux > rel * flux.max())
    out = np.where(peak, flux, 0.0)
    # 3칸(≈60ms) 안의 더 작은 온셋은 지움(한 타격 = 한 번)
    for i in np.argsort(-out):
        if out[i] <= 0: break
        for k in (-3, -2, -1, 1, 2, 3):
            j = (i + k) % STEPS
            if out[j] < out[i]: out[j] = 0
    return out / (out.max() + 1e-12)


def yin_pitch(x: np.ndarray, fmin: float, fmax: float, env: np.ndarray, gate: float = 0.08, thr: float = 0.15) -> np.ndarray:
    win = 2048
    f = frames(x, win * 2)
    tmin, tmax = int(SR / fmax), int(SR / fmin)
    out = np.zeros(STEPS)
    for i in range(STEPS):
        if env[i] < gate: continue
        s = f[i]
        a = s[:win]
        # 차이 함수 d(t) = Σ (x_j − x_{j+t})²
        e0 = np.cumsum(np.concatenate([[0], s ** 2]))
        corr = np.fft.irfft(np.fft.rfft(s, 4 * win) * np.conj(np.fft.rfft(a, 4 * win)))[:tmax + 2]
        t = np.arange(tmax + 2)
        d = e0[win] + (e0[t + win] - e0[t]) - 2 * corr
        d[0] = 0
        cm = d[1:] * np.arange(1, len(d)) / (np.cumsum(d[1:]) + 1e-12)
        cm = np.concatenate([[1], cm])
        cand = np.where(cm[tmin:tmax] < thr)[0]
        if len(cand) == 0: continue
        tau = cand[0] + tmin
        while tau + 1 < tmax and cm[tau + 1] < cm[tau]: tau += 1
        if 0 < tau < len(cm) - 1:  # 포물선 보간
            a0, b0, c0 = cm[tau - 1], cm[tau], cm[tau + 1]
            den = a0 - 2 * b0 + c0
            tau = tau + (0.5 * (a0 - c0) / den if abs(den) > 1e-12 else 0)
        hz = SR / tau
        out[i] = 69 + 12 * np.log2(hz / 440)
    # 한두 칸짜리 튐 제거(중앙값 5칸)
    sm = out.copy()
    for i in range(STEPS):
        w = out[[(i + k) % STEPS for k in range(-2, 3)]]
        v = w[w > 0]
        sm[i] = np.median(v) if out[i] > 0 and len(v) >= 3 else (out[i] if len(v) >= 3 else 0)
    return sm


def q(v: np.ndarray) -> list:
    return [int(round(min(255, max(0, a * 255)))) for a in v]


def main():
    pad, keys, bass, drums, perc, guitar = (decode(n) for n in ("synth", "keys", "bass", "drums", "perc", "guitar"))
    rows = {}
    rows["pad"] = q(rms_env(pad))
    rows["keys"] = q(rms_env(keys))
    be = rms_env(bass)
    rows["bass"] = q(be)
    rows["perc"] = q(rms_env(perc))
    ge = rms_env(guitar)
    rows["guitar"] = q(ge)
    rows["kick"] = q(onsets(band_energy(drums, 20, 150)))
    kick = np.array(rows["kick"])
    sn = onsets(band_energy(drums, 1500, 6000), 0.3)
    # 킥과 같은 순간의 중고역은 킥의 일부 → 킥이 약한(또는 없는) 순간만 스네어
    for i in np.nonzero(sn)[0]:
        if max(kick[(i + k) % STEPS] for k in range(-2, 3)) > 100: sn[i] = 0
    rows["snare"] = q(sn / (sn.max() + 1e-12))
    rows["hat"] = q(onsets(band_energy(perc, 5000, 16000), 0.25))
    bp = yin_pitch(bass, 30, 260, be, 0.10)
    gp = yin_pitch(guitar, 140, 1300, ge, 0.10)
    rows["bass_pitch"] = [int(round(min(255, p * 2))) for p in bp]
    rows["guitar_pitch"] = [int(round(min(255, p * 2))) for p in gp]
    rows["bass_on"] = q(onsets(band_energy(bass, 30, 400), 0.25))
    out = RAW / "wave_score.txt"
    with open(out, "w") as fh:
        fh.write(f"# wave score v1 steps={STEPS} hop={HOP} loop={LOOP} (tools/make_wave_score.py)\n")
        for k, v in rows.items():
            assert len(v) == STEPS
            fh.write(k + " " + " ".join(map(str, v)) + "\n")
    # 점검 출력: 마디별 킥·스네어 위치(박 단위), 음 높이 범위
    per_bar = STEPS // 8
    for name in ("kick", "snare", "hat", "bass_on"):
        v = np.array(rows[name])
        print(name, [[round((i % per_bar) / 32, 2) for i in np.nonzero(v[b * per_bar:(b + 1) * per_bar])[0] + b * per_bar] for b in range(2)])
    for name in ("bass_pitch", "guitar_pitch"):
        v = np.array(rows[name]) / 2
        nz = v[v > 0]
        print(name, "voiced", len(nz), "/", STEPS, "range", (round(nz.min(), 1), round(nz.max(), 1)) if len(nz) else None)
    print("wrote", out)


if __name__ == "__main__":
    main()
