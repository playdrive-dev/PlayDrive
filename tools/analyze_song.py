#!/usr/bin/env python3
"""한 곡에서 분리한 스템(assets-src/suno_song_stems) 분석 → 8마디 루프 구간 후보.

- 박자 격자는 곡 전체에서 한 번만 구한다(드럼+베이스 합의 onset으로 템포·위상 회귀, 다운비트는 킥 저역).
  → 모든 스템을 같은 샘플 범위로 자르기 위함. 스템별 위치 추정은 하지 않는다.
- 마디별: 코드 라벨(베이스+키보드 고해상도 크로마 vs Am7/Fmaj7/Cmaj7/G 템플릿), 스템별 RMS(dBFS),
  드럼 패턴 유사도(마디 onset 패턴 vs 곡 전체 중앙값 패턴 — 낮으면 필인/변주).
- 후보 점수(8마디 창, 다운비트 시작): 코드 일치(Am7 F C G ×2) + 4마디 주기 크로마 유사도 + 전 스템 활동 + 드럼 안정.

사용: python3 tools/analyze_song.py [assets-src/suno_song_stems] [--json build/analysis/song.json]
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import analyze_stems as A  # noqa: E402

SR = A.SR
STEMS = {"drums": "1 Drums.wav", "bass": "2 Bass.wav", "guitar": "3 Guitar.wav", "keys": "4 Keyboard.wav",
         "perc": "5 Percussion.wav", "synth": "6 Synth.wav"}
NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']
PROG = ["Am7", "Fmaj7", "Cmaj7", "G"]
TEMPLATES = {n: pcs for n, pcs in A.PROGRESSION}


def chroma_of(x: np.ndarray, lo=50, hi=4000) -> np.ndarray:
    n = 16384
    seg = x[:n] if len(x) >= n else np.pad(x, (0, n - len(x)))
    # 구간 전체를 덮도록 여러 창 평균
    hop = n // 2
    acc = np.zeros(12)
    freqs = np.fft.rfftfreq(n, 1 / SR)
    ok = (freqs > lo) & (freqs < hi)
    pc = ((np.round(12 * np.log2(freqs[ok] / 440)) + 9) % 12).astype(int)
    win = np.hanning(n)
    for s in range(0, max(1, len(x) - n + 1), hop):
        m = np.abs(np.fft.rfft(x[s:s + n] * win))[ok] ** 2
        acc += np.bincount(pc, weights=m, minlength=12)
    del seg
    return acc / (np.linalg.norm(acc) + 1e-12)


def label(c: np.ndarray):
    scores = {k: c[v].sum() / np.sqrt(len(v)) for k, v in TEMPLATES.items()}
    best = max(scores, key=scores.get)
    return best, scores[best]


def main():
    src = Path(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else "assets-src/suno_song_stems")
    x = {k: A.decode(src / f) for k, f in STEMS.items()}  # 모노
    dur = len(x["drums"]) / SR

    # --- 격자: 드럼+베이스 onset
    mix = x["drums"] + 0.5 * x["bass"]
    mag = A.stft_mag(mix)
    env = A.flux(mag) + A.flux(mag, 0, 200)
    bpm0, beat0 = A.best_tempo(env, 88, 92, 0.01)
    bpm, beat0, resid, nb = A.refine_tempo(A.smooth(env, 1.0), bpm0, beat0, dur)
    beat = 60 / bpm
    kick = A.flux(A.stft_mag(x["drums"]), 0, 150)
    db, scores = A.downbeat(beat0 % beat, bpm, kick, dur)
    bar = 4 * beat
    nbars = int((dur - db) / bar)
    print(f"tempo {bpm:.4f} BPM (resid {resid:.1f}ms, {nb} beats), first beat {beat0 % beat:.4f}s, first downbeat {db:.4f}s, bars {nbars}")

    # --- 마디별 특징
    fps = SR / A.HOP
    dflux = A.flux(A.stft_mag(x["drums"]))
    bars = []
    for i in range(nbars):
        a, b = int((db + i * bar) * SR), int((db + (i + 1) * bar) * SR)
        harm = x["bass"][a:b] * 1.0 + x["keys"][a:b]
        c = chroma_of(harm)
        lab, sc = label(c)
        rms = {k: float(20 * np.log10(np.sqrt(np.mean(v[a:b] ** 2)) + 1e-9)) for k, v in x.items()}
        pat = dflux[int(a / A.HOP):int(a / A.HOP) + int(bar * fps)]
        bars.append({"i": i, "t": round(db + i * bar, 3), "chord": lab, "chord_score": round(float(sc), 3),
                     "top": "/".join(NAMES[k] for k in np.argsort(-c)[:3]), "chroma": c, "rms": rms, "drum_pat": pat})
    L = min(len(b["drum_pat"]) for b in bars)
    med = np.median(np.array([b["drum_pat"][:L] for b in bars]), axis=0)
    for b in bars:
        p = b["drum_pat"][:L]
        b["drum_sim"] = round(float(np.dot(p, med) / (np.linalg.norm(p) * np.linalg.norm(med) + 1e-9)), 3)

    print("\nbar  time    chord  top      drum_sim  rms(dB): " + " ".join(f"{k:>6}" for k in STEMS))
    for b in bars:
        print(f"{b['i']:>3} {b['t']:>7.2f}  {b['chord']:6} {b['top']:8} {b['drum_sim']:>6}   " + " ".join(f"{b['rms'][k]:>6.1f}" for k in STEMS))

    # --- 8마디 후보
    expect = PROG * 2
    cands = []
    for s in range(0, nbars - 7):
        w = bars[s:s + 8]
        chord_match = sum(w[k]["chord"] == expect[k] for k in range(8)) / 8
        period = np.mean([float(np.dot(w[k]["chroma"], w[k + 4]["chroma"])) for k in range(4)])
        active = min(min(b["rms"][k] for b in w) for k in ("drums", "bass", "guitar", "keys"))
        weak = {k: round(float(np.mean([b["rms"][k] for b in w])), 1) for k in ("synth", "perc")}
        drum_min = min(b["drum_sim"] for b in w)
        drum_mean = float(np.mean([b["drum_sim"] for b in w]))
        score = 2 * chord_match + period + (1 if active > -45 else 0) + drum_mean + 0.5 * drum_min
        cands.append({"start_bar": s, "start_s": w[0]["t"], "end_s": round(w[0]["t"] + 8 * bar, 3),
                      "start_sample": int(round(w[0]["t"] * SR)), "chords": " ".join(b["chord"] for b in w),
                      "chord_match": round(chord_match, 3), "period_sim": round(period, 3),
                      "min_main_rms_db": round(active, 1), "synth_perc_mean_db": weak,
                      "drum_sim_mean": round(drum_mean, 3), "drum_sim_min": round(drum_min, 3), "score": round(score, 3)})
    cands.sort(key=lambda c: -c["score"])
    print("\nTop candidates:")
    for c in cands[:6]:
        print(json.dumps(c, ensure_ascii=False))
    if "--json" in sys.argv:
        out = {"bpm": bpm, "beat_resid_ms": resid, "first_downbeat_s": db, "bar_s": bar, "nbars": nbars,
               "bars": [{k: v for k, v in b.items() if k not in ("chroma", "drum_pat")} for b in bars], "candidates": cands[:10]}
        Path(sys.argv[sys.argv.index("--json") + 1]).write_text(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
