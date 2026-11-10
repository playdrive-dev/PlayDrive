#!/usr/bin/env python3
"""Suno 스템 분석: 실제 BPM, 첫 박·첫 다운비트 위치, 앞뒤 무음, 라우드니스.

의존성: numpy, ffmpeg(PATH). librosa 없이 동작한다.

방법
- ffmpeg로 모노 float32 48kHz 디코딩
- STFT(2048/hop 256) → 스펙트럼 플럭스(onset 강도). 드럼은 저역(<200Hz) 플럭스도 따로
- 템포: 80~100 BPM을 0.01 간격으로 훑으며 "등간격 펄스열 × onset 강도" 점수가 최대인 (BPM, 위상) 선택
- 다운비트: 박 4개 위상 후보 중 마디 단위 점수가 가장 큰 것
    드럼 = 저역 플럭스(킥), 그 외 = 크로마 변화량(코드 바뀌는 지점)
사용: python3 tools/analyze_stems.py assets-src/suno_raw [--json out.json]
"""
import json
import subprocess
import sys
from pathlib import Path

import numpy as np

SR = 48_000
HOP = 256
NFFT = 2048


def decode(path: Path, sr: int = SR, mono: bool = True) -> np.ndarray:
    cmd = ["ffmpeg", "-v", "error", "-i", str(path), "-f", "f32le", "-ar", str(sr)]
    cmd += ["-ac", "1"] if mono else ["-ac", "2"]
    cmd += ["-"]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    x = np.frombuffer(raw, dtype=np.float32)
    return x if mono else x.reshape(-1, 2)


def stft_mag(x: np.ndarray) -> np.ndarray:
    win = np.hanning(NFFT).astype(np.float32)
    n = 1 + max(0, (len(x) - NFFT) // HOP)
    idx = np.arange(NFFT)[None, :] + HOP * np.arange(n)[:, None]
    frames = x[idx] * win
    return np.abs(np.fft.rfft(frames, axis=1))


def flux(mag: np.ndarray, lo_hz=0, hi_hz=SR / 2) -> np.ndarray:
    freqs = np.fft.rfftfreq(NFFT, 1 / SR)
    band = (freqs >= lo_hz) & (freqs < hi_hz)
    m = np.log1p(100 * mag[:, band])
    d = np.diff(m, axis=0, prepend=m[:1])
    f = np.maximum(d, 0).sum(axis=1)
    f -= np.convolve(f, np.ones(32) / 32, mode="same")  # 국소 평균 제거
    f = np.maximum(f, 0)
    return f / (f.max() + 1e-9)


CHROMA_NFFT = 16384  # 2.9Hz 간격 — 저음(베이스) 음높이를 구분하려면 필요


def chroma_frames(x: np.ndarray) -> np.ndarray:
    """고해상도 크로마(hop은 onset과 같은 256 → 프레임 인덱스 호환)."""
    win = np.hanning(CHROMA_NFFT).astype(np.float32)
    pad = np.concatenate([np.zeros(CHROMA_NFFT // 2, np.float32), x, np.zeros(CHROMA_NFFT // 2, np.float32)])
    n = 1 + max(0, (len(x) - NFFT) // HOP)
    freqs = np.fft.rfftfreq(CHROMA_NFFT, 1 / SR)
    ok = (freqs > 50) & (freqs < 4000)
    pc = ((np.round(12 * np.log2(freqs[ok] / 440.0)) + 9) % 12).astype(int)
    out = np.zeros((n, 12))
    step = 8  # 8프레임(43ms)마다 계산 후 반복 — 속도
    for i in range(0, n, step):
        c0 = i * HOP + NFFT // 2  # 원래 STFT 프레임 중심
        seg = pad[c0: c0 + CHROMA_NFFT]
        if len(seg) < CHROMA_NFFT:
            seg = np.pad(seg, (0, CHROMA_NFFT - len(seg)))
        m = np.abs(np.fft.rfft(seg * win))[ok] ** 2
        v = np.bincount(pc, weights=m, minlength=12)
        out[i:i + step] = v / (np.linalg.norm(v) + 1e-9)
    return out


def chroma(mag: np.ndarray) -> np.ndarray:
    freqs = np.fft.rfftfreq(NFFT, 1 / SR)
    ok = (freqs > 55) & (freqs < 4000)
    pc = (np.round(12 * np.log2(freqs[ok] / 440.0)) + 9) % 12
    c = np.zeros((mag.shape[0], 12))
    for k in range(12):
        c[:, k] = mag[:, ok][:, pc == k].sum(axis=1)
    return c / (np.linalg.norm(c, axis=1, keepdims=True) + 1e-9)


def smooth(env: np.ndarray, sigma=1.5) -> np.ndarray:
    k = np.arange(-6, 7)
    g = np.exp(-0.5 * (k / sigma) ** 2)
    return np.convolve(env, g / g.sum(), mode="same")


def sample_at(env: np.ndarray, pos: np.ndarray) -> np.ndarray:
    return np.interp(pos, np.arange(len(env)), env, left=0, right=0)


def best_tempo(env: np.ndarray, lo=80.0, hi=100.0, step=0.01):
    fps = SR / HOP
    env = smooth(env)
    best = (-1.0, 0.0, 0.0)
    for bpm in np.arange(lo, hi + 1e-9, step):
        p = 60.0 / bpm * fps
        phases = np.arange(0, p, 0.25)
        k = np.arange(0, int((len(env) - 1) / p) + 1)
        pos = phases[:, None] + k[None, :] * p
        valid = pos < len(env) - 1
        vals = sample_at(env, np.where(valid, pos, 0)) * valid
        score = vals.sum(axis=1) / valid.sum(axis=1)
        i = int(np.argmax(score))
        if score[i] > best[0]:
            best = (float(score[i]), float(bpm), float(phases[i]))
    _, bpm, phase = best
    return bpm, phase / fps  # 첫 박 시각(s)


def downbeat(beat0_s: float, bpm: float, novelty: np.ndarray, dur: float) -> tuple[float, list]:
    fps = SR / HOP
    beat = 60.0 / bpm
    scores = []
    nov = smooth(novelty, 2.0)
    for j in range(4):
        t0 = beat0_s + j * beat
        ts = np.arange(t0, dur - 0.05, 4 * beat)
        scores.append(float(sample_at(nov, ts * fps).mean()) if len(ts) else 0.0)
    j = int(np.argmax(scores))
    return beat0_s + j * beat, scores


def chroma_novelty(c: np.ndarray, beat_frames: float) -> np.ndarray:
    w = max(4, int(beat_frames))
    nov = np.zeros(len(c))
    for i in range(w, len(c) - w):
        a = c[i - w:i].mean(axis=0); b = c[i:i + w].mean(axis=0)
        nov[i] = 1 - np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9)
    return nov / (nov.max() + 1e-9)


# 코드 템플릿(피치 클래스, A=9): Am7 – Fmaj7 – Cmaj7 – G
PROGRESSION = [("Am7", [9, 0, 4, 7]), ("Fmaj7", [5, 9, 0, 4]), ("Cmaj7", [0, 4, 7, 11]), ("G", [7, 11, 2])]


def refine_tempo(env: np.ndarray, bpm: float, beat0_s: float, dur: float):
    """예측 박 주변 ±40ms에서 onset 피크를 찾아 선형회귀 → (bpm, 첫 박, 잔차 ms, 사용한 박 수)."""
    fps = SR / HOP
    period = 60 / bpm
    n = int((dur - beat0_s) / period) + 1
    win = int(0.04 * fps)
    idx, times = [], []
    for k in range(n):
        c = int(round((beat0_s + k * period) * fps))
        lo, hi = max(0, c - win), min(len(env), c + win + 1)
        if hi - lo < 3:
            continue
        seg = env[lo:hi]
        j = int(np.argmax(seg))
        if seg[j] < 0.15:  # 약한 박은 제외
            continue
        # 포물선 보간으로 서브프레임 위치
        if 0 < j < len(seg) - 1:
            a, b, cc = seg[j - 1], seg[j], seg[j + 1]
            off = 0.5 * (a - cc) / (a - 2 * b + cc + 1e-12)
        else:
            off = 0.0
        idx.append(k); times.append((lo + j + off) / fps)
    if len(idx) < 8:
        return bpm, beat0_s, None, len(idx)
    k = np.array(idx, float); t = np.array(times)
    slope, icpt = np.polyfit(k, t, 1)
    resid = t - (slope * k + icpt)
    return 60 / slope, float(icpt), float(np.std(resid) * 1000), len(idx)


def harmonic_alignment(c: np.ndarray, beat0_s: float, bpm: float, dur: float):
    """다운비트 오프셋(0~3박) × 진행 회전(0~3)별 마디 크로마-템플릿 일치도 → Am7 마디 시작 시각."""
    fps = SR / HOP
    beat = 60 / bpm
    tmpl = np.zeros((4, 12))
    for i, (_, pcs) in enumerate(PROGRESSION):
        tmpl[i, pcs] = 1
    tmpl /= np.linalg.norm(tmpl, axis=1, keepdims=True)
    best = (-1, 0, 0)
    for j in range(4):
        starts = np.arange(beat0_s + j * beat, dur - 4 * beat + 1e-6, 4 * beat)
        bars = []
        for t0 in starts:
            a, b = int(t0 * fps), int((t0 + 4 * beat) * fps)
            v = c[a:b].mean(axis=0); bars.append(v / (np.linalg.norm(v) + 1e-9))
        if len(bars) < 4:
            continue
        bars = np.array(bars)
        for r in range(4):
            score = np.mean([bars[b] @ tmpl[(r + b) % 4] for b in range(len(bars))])
            if score > best[0]:
                best = (score, j, r)
    score, j, r = best
    t_first_bar = beat0_s + j * beat
    k = (-r) % 4  # 첫 Am7 마디까지 몇 마디
    first_am = t_first_bar + k * 4 * beat
    if first_am + 16 * beat > dur + 1e-3 and first_am - 16 * beat >= -1e-3:
        first_am -= 16 * beat
    return first_am, float(score), PROGRESSION[r][0]


def silence_edges(x: np.ndarray, thr_db=-50.0):
    win = 480  # 10ms
    n = len(x) // win
    rms = np.sqrt((x[: n * win].reshape(n, win) ** 2).mean(axis=1) + 1e-12)
    db = 20 * np.log10(rms)
    loud = np.where(db > thr_db)[0]
    if len(loud) == 0:
        return 0.0, 0.0
    return loud[0] * win / SR, (n - 1 - loud[-1]) * win / SR


def loudness(path: Path) -> dict:
    r = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", str(path), "-af", "ebur128=peak=true", "-f", "null", "-"],
                       capture_output=True, text=True).stderr
    tail = r[r.rfind("Summary:"):]
    def grab(key):
        for line in tail.splitlines():
            if line.strip().startswith(key):
                return float(line.split(":")[1].split()[0])
        return None
    return {"lufs": grab("I:"), "true_peak_db": grab("Peak:")}


def analyze(path: Path) -> dict:
    x = decode(path)
    dur = len(x) / SR
    lead, trail = silence_edges(x)
    out = {"file": path.name, "duration_s": round(dur, 3), "lead_silence_s": lead, "trail_silence_s": trail}
    out.update(loudness(path))
    if path.name.startswith("sfx_"):
        return out
    mag = stft_mag(x)
    is_drum = path.name.startswith(("drums", "hat"))
    env = flux(mag) + (flux(mag, 0, 200) if path.name.startswith("drums") else 0)
    bpm, beat0 = best_tempo(env)
    bpm_fine, beat0_fine = best_tempo(env, bpm - 0.2, bpm + 0.2, 0.002)
    beat_frames = 60 / bpm_fine * SR / HOP
    novelty = flux(mag, 0, 200) if path.name.startswith("drums") else (flux(mag) if is_drum else chroma_novelty(chroma_frames(x), beat_frames))
    bpm_r, beat0_r, resid_ms, nb = refine_tempo(smooth(env, 1.0), bpm_fine, beat0_fine, dur)
    if resid_ms is not None and resid_ms < 15 and abs(bpm_r - bpm_fine) < 0.5:
        bpm_fine, beat0_fine = bpm_r, beat0_r % (60 / bpm_r)
    db, scores = downbeat(beat0_fine, bpm_fine, novelty, dur)
    am_start = am_score = first_chord = None
    if not path.name.startswith(("drums", "hat")):
        am_start, am_score, first_chord = harmonic_alignment(chroma_frames(x), beat0_fine, bpm_fine, dur)
    beats_total = (dur - db) * bpm_fine / 60
    out.update({
        "beat_resid_ms": None if resid_ms is None else round(resid_ms, 1),
        "beats_used": nb,
        "am7_bar_start_s": None if am_start is None else round(am_start, 3),
        "chord_match": None if am_score is None else round(am_score, 3),
        "first_full_bar_chord": first_chord,
        "bpm": round(bpm_fine, 2),
        "first_beat_s": round(beat0_fine, 3),
        "first_downbeat_s": round(db, 3),
        "downbeat_scores": [round(s, 3) for s in scores],
        "bars_after_downbeat": round(beats_total / 4, 2),
        "bars_total_at_bpm": round(dur * bpm_fine / 60 / 4, 2),
    })
    return out


def main():
    src = Path(sys.argv[1] if len(sys.argv) > 1 else "assets-src/suno_raw")
    rows = [analyze(p) for p in sorted(src.glob("*.wav"))]
    for r in rows:
        print(json.dumps(r, ensure_ascii=False))
    if "--json" in sys.argv:
        Path(sys.argv[sys.argv.index("--json") + 1]).write_text(json.dumps(rows, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
