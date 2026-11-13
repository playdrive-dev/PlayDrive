#!/usr/bin/env python3
"""
시연 영상 타임라인: tools/record_demo.sh가 남긴 기기 로그(logcat -v epoch)에서 장면별 시작 시각을 뽑아
영상 기준(영상 첫 프레임 = 0) 시각으로 바꾼다. 출력은 Markdown 표.

  python3 tools/demo_timeline.py docs/demo/events.log --video-start-ms <맥 ms> --skew-ms <기기−맥 ms> [--launch-ms <맥 ms>]

영상 시각 = (기기 로그 시각 − skew) − 영상 시작 시각.
"""
import argparse
import re
import sys

LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+([\w.]+)\s*:\s*(.*)$")

# (표에 쓸 이름, 로그 태그, 메시지 패턴, 몇 번째로 나온 것(0부터) 또는 'last')
SCENES = [
    ("출발 드롭(드럼 들어옴)", "PlayDrive.Mood", r"장면: 드럼 들어옴", 0),
    ("출발(Departed)", "PlayDrive.Drive", r"이벤트 Departed", 0),
    ("깜빡이 ON", "PlayDrive.Drive", r"이벤트 TurnSignalOn", 0),
    ("차선 변경", "PlayDrive.Drive", r"이벤트 LaneChange", 0),
    ("신호 대기(정차)", "PlayDrive.Drive", r"이벤트 Stopped", 0),
    ("재출발", "PlayDrive.Drive", r"이벤트 Departed", 1),
    ("재출발 드롭(드럼 들어옴)", "PlayDrive.Mood", r"장면: 드럼 들어옴", 1),
    ("비 시작(와이퍼 ON)", "PlayDrive.Drive", r"이벤트 RainStarted", 0),
    ("비 그침", "PlayDrive.Drive", r"이벤트 RainStopped", 0),
    ("급제동(테이프스톱)", "PlayDrive.Drive", r"이벤트 HarshBraking", 0),
    ("회복(레이어 복귀)", "PlayDrive.UI", r"장면: 회복", 0),
    ("정차", "PlayDrive.Drive", r"이벤트 Stopped", "last"),
    ("주차(P)", "PlayDrive.Drive", r"이벤트 Parked", "last"),
    ("굿바이(빛이 가라앉음)", "PlayDrive.UI", r"장면: 굿바이", "last"),
    ("드라이브 앨범", "PlayDrive.UI", r"장면: 드라이브 앨범", "last"),
    ("앨범 상세", "PlayDrive.UI", r"장면: 앨범 상세", "last"),
]


def fmt(sec):
    m, s = divmod(max(sec, 0.0), 60)
    return f"{int(m):02d}:{s:06.3f}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--video-start-ms", type=int, required=True)
    ap.add_argument("--skew-ms", type=int, required=True)
    ap.add_argument("--launch-ms", type=int)
    a = ap.parse_args()

    rows = []
    for raw in open(a.log, encoding="utf-8", errors="replace"):
        m = LINE.match(raw)
        if m:
            rows.append((float(m.group(1)) * 1000, m.group(2), m.group(3)))

    def video_sec(dev_ms):
        return (dev_ms - a.skew_ms - a.video_start_ms) / 1000.0

    # 마지막 주차 이후에 나온 장면만 "last"로(앞선 seed·준비 과정의 로그와 섞이지 않게)
    out = []
    if a.launch_ms:
        out.append(("웰컴(앱 실행 → 검은 화면에서 빛이 피어남)", (a.launch_ms - a.video_start_ms) / 1000.0))
    for name, tag, pat, which in SCENES:
        hits = [t for t, tg, msg in rows if tg == tag and re.search(pat, msg)]
        if not hits:
            continue
        t = hits[-1] if which == "last" else (hits[which] if which < len(hits) else None)
        if t is not None:
            out.append((name, video_sec(t)))
    rec = [t for t, tg, msg in rows if tg == "PlayDrive.Rec" and "record start" in msg]

    out.sort(key=lambda x: x[1])
    print("# 시연 영상 타임라인\n")
    print("영상 첫 프레임 = 00:00.000. 시각은 기기 로그(앱이 이벤트를 처리한 순간) 기준이며, 소리의 드롭은 다음 다운비트(최대 2.7초 뒤)에 들어온다.")
    print("'드럼 들어옴'은 믹서가 실제로 드럼을 내기 시작한 순간 = 화면의 드롭 반응 순간.\n")
    print("| 장면 | 영상 시각 |")
    print("|---|---|")
    for name, t in out:
        print(f"| {name} | {fmt(t)} |")
    if rec:
        print(f"\n앱 소리(app_audio.wav) 첫 샘플 = 영상 {fmt(video_sec(rec[-1]))}")


if __name__ == "__main__":
    sys.exit(main())
