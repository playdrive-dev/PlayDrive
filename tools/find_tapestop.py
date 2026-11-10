#!/usr/bin/env python3
"""테이프스톱 원샷에서 실제로 '멈추는'(음높이가 계속 내려가는) 구간 찾기.

방법: 로그 주파수 스펙트럼(1/24옥타브, 60Hz~8kHz)을 50ms마다 구하고, 이웃 프레임 사이
스펙트럼이 아래로 이동한 양(반음 단위, 교차상관 최대 지연)을 누적한다.
가장 큰 누적 하강(=피치가 꾸준히 내려가는 구간)을 찾고, 에너지가 사그라드는 끝점까지를 '멈춤'으로 본다.
사용: python3 tools/find_tapestop.py assets-src/suno_raw/sfx_tapestop_A.wav [...]
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import analyze_stems as A  # noqa: E402

SR = A.SR


def logspec(x, hop_s=0.05, n=8192, bins_per_oct=24, fmin=60.0, fmax=8000.0):
    hop = int(hop_s * SR)
    win = np.hanning(n)
    freqs = np.fft.rfftfreq(n, 1 / SR)
    nb = int(np.log2(fmax / fmin) * bins_per_oct)
    edges = fmin * 2 ** (np.arange(nb + 1) / bins_per_oct)
    idx = np.digitize(freqs, edges) - 1
    ok = (idx >= 0) & (idx < nb)
    frames = []
    for s in range(0, len(x) - n, hop):
        m = np.abs(np.fft.rfft(x[s:s + n] * win)) ** 2
        frames.append(np.log1p(np.bincount(idx[ok], weights=m[ok], minlength=nb) * 1e3))
    return np.array(frames), hop_s, bins_per_oct


def analyze(path: Path) -> dict:
    x = A.decode(path)
    S, hop_s, bpo = logspec(x)
    energy = S.sum(axis=1)
    shifts = [0.0]
    for i in range(1, len(S)):
        a, b = S[i - 1] - S[i - 1].mean(), S[i] - S[i].mean()
        lags = range(-12, 4)  # 아래로 최대 반옥타브/프레임
        cc = [np.dot(a[max(0, -l):len(a) - max(0, l)], b[max(0, l):len(b) - max(0, -l)]) for l in lags]
        shifts.append(lags[int(np.argmax(cc))] * 12 / bpo)  # 반음
    shifts = np.array(shifts)
    # 누적 하강이 가장 큰 연속 구간(상승 1반음 이상이면 끊김)
    best = (0.0, 0, 0)
    for i in range(len(shifts)):
        tot = 0.0
        for j in range(i, len(shifts)):
            if shifts[j] > 1.0:
                break
            tot += min(shifts[j], 0)
            if -tot > best[0]:
                best = (-tot, i, j)
    drop, i, j = best
    # 끝점: 하강 구간 이후 에너지가 피크 대비 -25dB 아래로 떨어지는 첫 프레임
    e_db = 10 * np.log10(np.exp(energy / len(S[0])) + 1e-12)
    tail = j
    while tail + 1 < len(e_db) and e_db[tail] > e_db[i:j + 1].max() - 25:
        tail += 1
    return {"file": path.name, "duration_s": round(len(x) / SR, 2),
            "descent_start_s": round(i * hop_s, 2), "descent_end_s": round(j * hop_s, 2),
            "pitch_drop_semitones": round(drop, 1), "fade_end_s": round(tail * hop_s, 2),
            "shifts_semitones": [round(v, 1) for v in shifts[max(0, i - 4): j + 3]]}


if __name__ == "__main__":
    for p in sys.argv[1:]:
        print(json.dumps(analyze(Path(p)), ensure_ascii=False))
