#!/usr/bin/env python3
"""음성 안내 녹음 음성 만들기.

  ENGINE=say (기본)    macOS 기본 한국어 음성(`say -v Yuna -r 185`). 지금 저장소·APK에 들어 있는 음성.
                       주의: macOS 사용권은 시스템 음성 출력을 개인·비상업 용도로만 허락한다(2026-11-15 사용자가 알고 결정).
                       정식 출시 전에는 상업 이용 가능한 음성으로 바꿔야 한다.
  ENGINE=espeak        eSpeak NG(GPLv3 도구 — 합성 결과에는 라이선스 제한 없음). 이전 임시 음성.

결과: voice_*.ogg (48kHz 모노, 앞뒤 무음 정리, -16 LUFS 근처, oggenc -q 4)
나중에 사람 목소리 등으로 바꿀 때는 같은 파일 이름으로 덮어쓰기만 하면 된다(앱 코드는 이름만 본다).

  voice_stop          연주가 멈췄어요. 부드럽게 달리면 다시 시작할게요.
  voice_resume        다시 시작할게요.
  voice_danger        조금만 부드럽게요.
  voice_grade_s|a|b|c 오늘의 연주는 에스(에이/비/씨) 등급이에요.
  voice_score_0..100  N점.
  voice_nosong        이번엔 끝까지 연주한 곡이 없어요. 다음엔 한 곡 완주해 봐요.
  voice_weekly_best   이번 주 최고 기록이에요.
  voice_badge         새 배지를 얻었어요.

사용: python3 tools/make_voice.py [이름...] [--out 폴더]   (macOS say, ffmpeg, oggenc 필요)
환경 변수 ENGINE(espeak|say), VOICE(say, 기본 Yuna), RATE(say, 기본 185).
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

ENGINE = os.environ.get("ENGINE", "say")
VOICE = os.environ.get("VOICE", "Yuna")
RATE = os.environ.get("RATE", "185")

# 앞뒤 무음 정리(-50dB 아래) → 대역 정리 → 음량 정규화 → 끝에 짧은 여백
FILTER = ("silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.02,"
          "areverse,silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.05,areverse,"
          "highpass=f=80,loudnorm=I=-16:TP=-1.5:LRA=7,apad=pad_dur=0.08")

def main():
    args = sys.argv[1:]
    out = OUT
    if "--out" in args:
        i = args.index("--out"); out = args[i + 1]; del args[i:i + 2]
    os.makedirs(out, exist_ok=True)
    only = set(args)
    n = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name, text in LINES.items():
            if only and name not in only: continue
            norm = os.path.join(tmp, name + "_48k.wav")
            if ENGINE == "say":
                raw = os.path.join(tmp, name + ".aiff")
                run(["say", "-v", VOICE, "-r", RATE, "-o", raw, text])
            else:  # -s 155: 조금 천천히, -p 45: 낮은 음높이, -g 2: 어절 사이 짧은 쉼
                raw = os.path.join(tmp, name + ".wav")
                run(["espeak-ng", "-v", "ko", "-s", "155", "-p", "45", "-g", "2", "-w", raw, text])
            run(["ffmpeg", "-y", "-i", raw, "-af", FILTER, "-ar", "48000", "-ac", "1", norm])
            run(["oggenc", "-Q", "-q", "4", "-o", os.path.join(out, name + ".ogg"), norm])
            n += 1
    print(f"{n}개 → {out}")

if __name__ == "__main__":
    main()
