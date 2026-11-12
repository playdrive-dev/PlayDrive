#!/usr/bin/env python3
"""
시연 영상 싱크 확인(tools/record_demo.sh가 끝에 자동 실행).

정답(소리): 앱의 오디오 스레드가 WAV에 쓰면서 남긴 "mark drums-on wavMs=…" = 드럼이 켜진 정확한 샘플 위치.
            영상 속 시각 = (소리 시작 지연 + wavMs × 오디오 시계 비율) / 1000.
화면: app_with_audio.mp4(1920×1080) 앱 창의 메인 빛 자리 60×60 평균 밝기에서, 그 근처 가장 큰 상승(드롭 +35%)의 시작.
     30fps라 ±17ms 오차.

  python3 -I tools/check_sync.py docs/demo/app_with_audio.mp4 docs/demo/events.log --audio-delay-ms <ms>
"""
import argparse
import re
import subprocess
import sys

import numpy as np

CX, CY = 1920 // 2, int(1080 * 0.40)   # 메인 빛 자리(앱 창 가운데, 앱 높이 40%)


def video_lum(path, t0, t1):
    cmd = ["ffmpeg", "-v", "error", "-ss", f"{t0:.3f}", "-to", f"{t1:.3f}", "-i", path, "-an",
           "-vf", f"crop=60:60:{CX - 30}:{CY - 30},format=gray", "-f", "rawvideo", "-"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    lum = np.frombuffer(raw, np.uint8).reshape(-1, 3600).mean(axis=1)
    return t0 + np.arange(len(lum)) / 30.0, lum


def env(e, attack, decay):
    if e < 0: return 0.0
    if e < attack: k = e / attack; return k * k * (3 - 2 * k)
    if e < attack + decay: k = 1 - (e - attack) / decay; return k * k
    return 0.0


def drop_bias_ms(frac=0.2):
    """앱의 드롭 밝기 곡선(LiveMotion: +35%·상승 150ms, 킥 +10%·상승 80ms가 같은 순간 시작)이 꼭짓점의 frac에 닿는 시각.
    밝기 상승 시작을 frac 지점으로 재므로 그만큼 늦게 잡힌다 → 빼서 보정."""
    g = [0.35 * env(ms, 150, 600) + 0.10 * env(ms, 80, 400) for ms in range(0, 400)]
    peak = max(g)
    return next(ms for ms, x in enumerate(g) if x >= peak * frac)


def rise_start(t, v, after, before, frac=0.2):
    """[after, before)에서 가장 크게 오르는 곳의 상승 시작(직전 0.4초 바닥 + 상승폭의 20%를 넘는 순간, 선형 보간)."""
    idx = np.where((t >= after) & (t < before))[0]
    if len(idx) < 3:
        return None, 0.0
    # 가장 큰 '오름'(0.3초 안 상승폭)이 있는 곳
    best, pk = 0.0, None
    for k in idx:
        lo = v[max(0, k - 9):k + 1].min()
        if v[k] - lo > best:
            best, pk = v[k] - lo, k
    if pk is None:
        return None, 0.0
    base = v[max(0, pk - 9):pk + 1].min()
    thr = base + (v[pk] - base) * frac
    k = pk
    while k > 0 and v[k - 1] > thr:
        k -= 1
    a, b = v[k - 1], v[k]
    return t[k - 1] + (t[k] - t[k - 1]) * ((thr - a) / (b - a) if b != a else 0), best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("log")
    ap.add_argument("--audio-delay-ms", type=float, required=True, help="영상 시작 대비 WAV 0번 샘플 시각(ms)")
    ap.add_argument("--audio-scale", type=float, default=1.0, help="WAV 시간 → 실제 시간 비율(오디오 시계 드리프트 보정)")
    a = ap.parse_args()

    marks = [float(m.group(1)) for l in open(a.log, encoding="utf-8", errors="replace")
             for m in [re.search(r"mark drums-on wavMs=([\d.]+)", l)] if m]
    if not marks:
        print("드럼 진입 기록(mark drums-on)이 없습니다"); return 1
    worst = 0.0
    bias = drop_bias_ms()
    print(f"화면 반응 시작 = 밝기 상승폭 20% 지점 − {bias}ms(앱의 드롭 밝기 곡선이 20%에 닿는 시간)\n")
    print("| 드럼 진입(소리, 영상 시각) | 화면 반응 시작 | 화면 − 소리 |")
    print("|---|---|---|")
    for w in marks:
        ta = (a.audio_delay_ms + w * a.audio_scale) / 1000.0
        tv, lv = video_lum(a.video, ta - 1.0, ta + 1.0)
        vs, amp = rise_start(tv, lv, ta - 0.6, ta + 0.6)
        if vs is None or amp < 3:
            print(f"| {ta:.3f}s | (뚜렷한 반응 없음, 상승 {amp:.1f}) | — |"); continue
        vs -= bias / 1000.0
        d = (vs - ta) * 1000
        worst = max(worst, abs(d))
        print(f"| {ta:.3f}s | {vs:.3f}s | {d:+.0f}ms |")
    print(f"\n최대 차이 {worst:.0f}ms → {'50ms 안 ✅' if worst <= 50 else '50ms 넘음 ❌'} (화면은 30fps라 ±17ms 오차)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
