#!/usr/bin/env python3
"""녹화 영상의 실제 화면 밝기 점검(운전 방해 기준): 프레임별 평균 밝기(Y)로
 - 1초 동안의 밝기 변화(직전 1초 대비, 상대 %) 최댓값 — 기준 10% 이내
 - 밝아졌다 어두워지는 왕복(1% 이상)의 최대 빈도(어느 1초 안) — 기준 3회 미만(3Hz 이상 깜빡임 없음)
편집 이음매(페이드 0.25초)는 빼고 본다: --skip "시작초:끝초,..."

사용: python3 tools/check_luma.py 영상.mp4 [--skip 7.75:8.25]
"""
import re
import subprocess
import sys


def lumas(path):
    out = subprocess.run(["ffmpeg", "-hide_banner", "-i", path, "-vf", "signalstats,metadata=print:key=lavfi.signalstats.YAVG", "-an", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    t = [float(x) for x in re.findall(r"pts_time:([\d.]+)", out)]
    y = [float(x) for x in re.findall(r"YAVG=([\d.]+)", out)]
    return list(zip(t, y))


def main():
    path = sys.argv[1]
    skip = []
    if "--skip" in sys.argv:
        for w in sys.argv[sys.argv.index("--skip") + 1].split(","):
            a, b = w.split(":"); skip.append((float(a), float(b)))
    fr = [(t, y) for t, y in lumas(path) if not any(a <= t <= b for a, b in skip)]
    worst = (0, 0)
    j = 0
    for i, (t, y) in enumerate(fr):
        while j < len(fr) and fr[j][0] < t + 1.0: j += 1
        if j >= len(fr): break
        if any(t < a <= fr[j][0] for a, _ in skip): continue
        d = abs(fr[j][1] - y) / max(y, 1)
        if d > worst[0]: worst = (d, t)
    # 왕복(1% 이상) 세기
    flips = []; rising = True; hi = lo = fr[0][1]
    for t, y in fr:
        if rising:
            hi = max(hi, y)
            if hi - y > 0.01 * hi: flips.append(t); rising = False; lo = y
        else:
            lo = min(lo, y)
            if y - lo > 0.01 * max(lo, 1): rising = True; hi = y
    per_s = max((sum(1 for f in flips if t <= f < t + 1) for t, _ in fr), default=0)
    ys = [y for _, y in fr]
    print(f"{path}: 프레임 {len(fr)}, 평균 밝기 {sum(ys)/len(ys):.1f}/255 (최소 {min(ys):.1f}, 최대 {max(ys):.1f})")
    print(f"  1초 밝기 변화 최대 {worst[0]*100:.1f}% (t={worst[1]:.1f}s) — 기준 10%: {'통과' if worst[0] <= 0.10 else '초과'}")
    print(f"  밝기 왕복(1%↑) 어느 1초 최대 {per_s}회 — 기준 3회 미만: {'통과' if per_s < 3 else '초과'}")


if __name__ == "__main__":
    main()
