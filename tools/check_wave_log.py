#!/usr/bin/env python3
"""기기에서 남긴 프레임별 물결 높이 로그(debug wavelog:on → logcat PlayDrive.Wave) 점검.
한 프레임 이동량(화면 높이 비율)을 실제 프레임 간격으로 60fps 기준(16.7ms)에 맞춰 보고,
프레임 사이 속도 변화(튐)가 큰 구간을 찾는다. 프레임이 밀린 구간(> 40ms)도 따로 센다.

사용: python3 tools/check_wave_log.py events.log
"""
import re
import sys


def main():
    rows = []
    for l in open(sys.argv[1], encoding="utf-8", errors="replace"):
        m = re.search(r"PlayDrive\.Wave: t=(\d+) h=(\S+(?: \S+){4})", l)
        if m:
            rows.append((int(m[1]), [float(v) for g in m[2].split() for v in g.split(",")]))
    if len(rows) < 3:
        print("로그 없음"); return
    gaps = [b[0] - a[0] for a, b in zip(rows, rows[1:])]
    worst_v = (0, 0); worst_j = (0, 0)
    prev_v = None
    for (t0, h0), (t1, h1) in zip(rows, rows[1:]):
        dt = max(t1 - t0, 1)
        v = [(b - a) * 16.7 / dt for a, b in zip(h0, h1)]   # 60fps 한 프레임 기준 이동
        mv = max(abs(x) for x in v)
        if mv > worst_v[0]: worst_v = (mv, t1)
        if prev_v is not None:
            j = max(abs(a - b) for a, b in zip(v, prev_v))
            if j > worst_j[0]: worst_j = (j, t1)
        prev_v = v
    fps = 1000 * (len(rows) - 1) / (rows[-1][0] - rows[0][0])
    print(f"프레임 {len(rows)}개, 평균 {fps:.1f}fps, 밀린 프레임(>40ms) {sum(g > 40 for g in gaps)}개, 최대 간격 {max(gaps)}ms")
    print(f"한 프레임 최대 이동 {worst_v[0]:.4f}(화면 높이 비율, ≈{worst_v[0]*1210:.1f}px) — 기준 0.010: {'통과' if worst_v[0] < 0.010 else '초과'}")
    print(f"프레임 사이 속도 변화 최대 {worst_j[0]:.4f}(≈{worst_j[0]*1210:.1f}px) — 기준 0.003: {'통과' if worst_j[0] < 0.003 else '초과'}")


if __name__ == "__main__":
    main()
