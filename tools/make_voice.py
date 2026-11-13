#!/usr/bin/env python3
"""음성 안내 임시 음원 만들기(espeak-ng, GPLv3 도구 — 합성 결과 음성에는 라이선스 제한이 없음).

결과: app/src/main/res/raw/voice_*.ogg (48kHz 모노, -18 LUFS 근처, oggenc -q 4)
나중에 사람 목소리 등으로 바꿀 때는 같은 파일 이름으로 덮어쓰기만 하면 된다(앱 코드는 이름만 본다).

  voice_stop          연주가 멈췄어요. 부드럽게 달리면 다시 시작할게요.
  voice_resume        다시 시작할게요.
  voice_danger        조금만 부드럽게요.
  voice_grade_s|a|b|c 오늘의 연주는 에스(에이/비/씨) 등급이에요.
  voice_score_0..100  N점.
  voice_nosong        이번엔 끝까지 연주한 곡이 없어요. 다음엔 한 곡 완주해 봐요.
  voice_weekly_best   이번 주 최고 기록이에요.
  voice_badge         새 배지를 얻었어요.

사용: python3 tools/make_voice.py   (espeak-ng, ffmpeg, oggenc 필요)
"""
import os, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/res/raw")

DIGITS = ["", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"]

def sino(n: int) -> str:
    if n == 0: return "영"
    if n == 100: return "백"
    tens, ones = divmod(n, 10)
    s = ""
    if tens: s += ("" if tens == 1 else DIGITS[tens]) + "십"
    s += DIGITS[ones]
    return s

LINES = {
    "voice_stop": "연주가 멈췄어요. 부드럽게 달리면 다시 시작할게요.",
    "voice_resume": "다시 시작할게요.",
    "voice_danger": "조금만 부드럽게요.",
    "voice_grade_s": "오늘의 연주는 에스 등급이에요.",
    "voice_grade_a": "오늘의 연주는 에이 등급이에요.",
    "voice_grade_b": "오늘의 연주는 비 등급이에요.",
    "voice_grade_c": "오늘의 연주는 씨 등급이에요.",
    "voice_nosong": "이번엔 끝까지 연주한 곡이 없어요. 다음엔 한 곡 완주해 봐요.",
    "voice_weekly_best": "이번 주 최고 기록이에요.",
    "voice_badge": "새 배지를 얻었어요.",
}
for n in range(101):
    LINES[f"voice_score_{n}"] = f"{sino(n)} 점."

def run(cmd):
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def main():
    only = set(sys.argv[1:])
    with tempfile.TemporaryDirectory() as tmp:
        for name, text in LINES.items():
            if only and name not in only: continue
            raw = os.path.join(tmp, name + ".wav"); norm = os.path.join(tmp, name + "_48k.wav")
            # -s 155: 조금 천천히, -p 45: 낮은 음높이, -g 2: 어절 사이 짧은 쉼
            run(["espeak-ng", "-v", "ko", "-s", "155", "-p", "45", "-g", "2", "-w", raw, text])
            run(["ffmpeg", "-y", "-i", raw, "-af",
                 "highpass=f=90,lowpass=f=7500,loudnorm=I=-18:TP=-2:LRA=7,apad=pad_dur=0.08",
                 "-ar", "48000", "-ac", "1", norm])
            run(["oggenc", "-Q", "-q", "4", "-o", os.path.join(OUT, name + ".ogg"), norm])
    print(f"{len(LINES) if not only else len(only)}개 → {OUT}")

if __name__ == "__main__":
    main()
