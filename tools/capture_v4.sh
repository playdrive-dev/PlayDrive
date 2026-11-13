#!/usr/bin/env bash
# v4 스크린샷·시연 영상(연주 기록·음성 안내) → docs/screens/v4/
#
#   01~09_*.png        앱 창(1682×1210, Pleos가 앱을 띄우는 영역 — 상태바·하단 바·왼쪽 패널·디버그 버튼 없음)
#   panel/01~06_*.png  세로형 보조 패널(1:1.6, 756×1210) — 앱을 세로 상자 안에 실제로 배치해 찍음(debug panel:on)
#   full/*.png         같은 순간의 차량 화면 전체 2560×1440(시스템 UI 포함, 참고용)
#   demo_safe.mp4      demo:safe(내비 안내, 6분) 편집본 약 88초: 안내 시작 카운트인·출발·차선 변경 / 깔끔한 회전 / 보호구역 통과 / 마지막 1km·두 번째 곡 완주·도착·결과(S·A)·음성
#   demo_rough.mp4     demo:rough(4분) 편집본 약 42초: 흔들림 → 악기 빠짐 → 위험 음성 → 정지(테이프스톱)·음성 → 15초 뒤 음성·카운트인 → 다시 완주(C) → 결과·음성
#   (녹화 때는 음성을 녹음 음성(OGG)으로: Pleos TTS 소리는 앱 믹스 밖이라 앱 소리 WAV에 담기지 않는다)
#   raw/               편집 전 원본 녹화·앱 소리 WAV·로그(git 제외 권장)
#
# 사용: tools/capture_v4.sh [shots|retake|video|all]   (기본 all; retake = 01·04·05·06·09 + panel 01·04·05·06만)
# 준비: 에뮬레이터(-gpu host 권장), 디버그 빌드 설치, ffmpeg, python3
# Pleos Connect는 일반 앱을 (853,90)-(2535,1300) 창에 고정한다(멀티 윈도우, 몰입 모드 무시) → 2560×1440 전체를 앱으로 채울 수 없다.
set -euo pipefail

PKG=app.playdrive
ACT=$PKG/.MainActivity
OUT=docs/screens/v4
WHAT=${1:-all}
APP_CROP=1682:1210:853:90          # 앱 창 전체
PANEL_CROP=756:1210:1316:90        # panel:on 일 때 가운데 세로 상자(1210/1.6 = 756)
VID_CROP=1680:1206:855:92          # 영상: 창 테두리 2px 제외
FPS=30

mkdir -p "$OUT/panel" "$OUT/full" "$OUT/raw"
OUT_ABS=$(cd "$OUT" && pwd)
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
wait_s() { python3 -c "import time; time.sleep($1)"; }
am() { adb shell "am start -n $ACT --es debug '$1'" >/dev/null 2>&1; }
until_s() { python3 -c "import time; d=$1/1000 - time.time(); time.sleep(max(0,d))"; }   # 맥 시각(ms)까지 기다림

shot() { # shot <이름> [panel]
  local name=$1 tmp="$OUT_ABS/full/$1.png"
  adb exec-out screencap -p > "$tmp"
  ffmpeg -hide_banner -loglevel error -y -i "$tmp" -vf "crop=$APP_CROP" "$OUT_ABS/$name.png"
  echo "  $name.png"
}
panel_shot() { # 세로 패널로 바꿔 찍고 되돌림
  local name=$1
  am "panel:on"; wait_s 1.6
  adb exec-out screencap -p > "$OUT_ABS/full/panel_$name.png"
  ffmpeg -hide_banner -loglevel error -y -i "$OUT_ABS/full/panel_$name.png" -vf "crop=$PANEL_CROP" "$OUT_ABS/panel/$name.png"
  am "panel:off"
  echo "  panel/$name.png"
}
fresh() { # 앱을 새로 띄움(웰컴 7초) + 공통 설정
  adb shell am force-stop $PKG; wait_s 1
  am "icon:debug:off;icon:demo:off;effect:live;voice:on;$1"
  wait_s 9
}
CUR_USER=$(adb shell am get-current-user | tr -d '\r')
adb shell input keyevent KEYCODE_WAKEUP; adb shell svc power stayon true   # 촬영 중 화면이 꺼지지 않게(꺼지면 검은 캡처)
adb shell pm grant --user "$CUR_USER" $PKG android.car.permission.CAR_SPEED 2>/dev/null || true

# ================================================================ 스크린샷
if [[ $WHAT == shots || $WHAT == all || $WHAT == retake ]]; then
  echo "[1·4·5] 노을 · demo:safe"
  fresh "seed:albums;seed:play;daypart:EVENING"
  T0=$(now_ms); am "demo:safe"
  until_s $((T0 + 49000)); shot 01_driving_sunset_combo        # 16마디 필인 순간(이어진 마디는 소리로만 — 주행 중 글자·숫자 없음)
  until_s $((T0 + 74000)); shot 04_stopped_redlight            # 부드러운 정지 후 신호 대기(브레이크다운)
  panel_shot 04_stopped_redlight
  until_s $((T0 + 134500)); panel_shot 01_driving_sunset_combo # 48마디 필인 순간
  until_s $((T0 + 368000)); shot 05_result_s                   # 도착·주차 → 굿바이 → 오늘의 연주(S·A)
  panel_shot 05_result_s
  wait_s 8

  if [[ $WHAT != retake ]]; then
  echo "[2] 밤 · 데모 드라이브"
  fresh "daypart:NIGHT"
  T0=$(now_ms); am "demo"
  until_s $((T0 + 20000)); shot 02_driving_night
  panel_shot 02_driving_night
  am "stop"

  echo "[3] 아침 + 비"
  fresh "daypart:MORNING"
  T0=$(now_ms); am "demo"
  until_s $((T0 + 49500)); shot 03_driving_morning_rain
  panel_shot 03_driving_morning_rain
  am "stop"

  fi
  echo "[6] demo:rough"
  fresh "daypart:auto"
  T0=$(now_ms); am "demo:rough"
  until_s $((T0 + 258000)); shot 06_result_failed            # 미완주 1곡 + 다시 완주(C)
  panel_shot 06_result_failed
  wait_s 4

  echo "[7·8·9] 기록·배지·앨범"
  if [[ $WHAT != retake ]]; then
    am "screen:records:2"; wait_s 2; shot 07_records_friends
    am "screen:records:3"; wait_s 2; shot 08_badges_unlock
  fi
  am "screen:album"; wait_s 3; shot 09_mind_album
  am "screen:mood"
fi

# ================================================================ 녹화
# 앱 소리는 에뮬레이터 화면 녹화에 담기지 않아(차량 오디오 버스) 앱 최종 믹스를 WAV로 저장해 합친다(record:start|stop, 디버그 빌드).
record_flow() { # record_flow <safe|rough>
  local flow=$1; local RAW="$OUT_ABS/raw/$flow"
  mkdir -p "$RAW"; rm -f "$RAW"/*
  # 기기−맥 시계 차이
  local SKEW; SKEW=$(python3 - <<'PY'
import subprocess, time
best = None
for _ in range(9):
    t0 = time.time() * 1000
    d = int(subprocess.check_output(["adb", "shell", "echo $EPOCHREALTIME"]).decode().strip().replace(".", "")[:13])
    t1 = time.time() * 1000
    if best is None or t1 - t0 < best[0]: best = (t1 - t0, d - (t0 + t1) / 2)
print(int(best[1]))
PY
)
  adb logcat -c
  fresh "seed:albums;seed:play;daypart:auto;record:start"
  local T0 WINDOWS CUTS
  # 녹화 구간(데모 시작 기준 초 "시작:끝")과 편집 구간("구간번호:시작:끝")
  if [[ $flow == safe ]]; then
    WINDOWS="0:46 132:232 340:376"
    CUTS="1:0:40 2:136:148 2:220:228 3:344:372"
    am "voice:ogg"; T0=$(now_ms); am "demo:safe"
  else
    WINDOWS="0:66 224:266"
    CUTS="1:26:44 1:53:61 2:227:231 2:250:262"
    am "voice:ogg"; T0=$(now_ms); am "demo:rough"
  fi
  local k=0 VS=()
  for w in $WINDOWS; do
    k=$((k + 1)); IFS=: read -r ws we <<< "$w"
    until_s $((T0 + ws * 1000 - 500))
    VS[$k]=$(now_ms); adb emu screenrecord start --time-limit $((we - ws + 3)) --fps $FPS "$RAW/rec$k.webm" >/dev/null
    until_s $((T0 + we * 1000)); adb emu screenrecord stop >/dev/null || true; wait_s 1
  done
  wait_s 1; am "record:stop"; am "voice:tts"; wait_s 2
  adb logcat -d -v epoch > "$RAW/events.log"
  adb exec-out run-as $PKG --user "$CUR_USER" cat files/demo_audio.wav > "$RAW/app_audio.wav"
  local A0; A0=$(adb exec-out run-as $PKG --user "$CUR_USER" cat files/demo_audio.txt | tr -d '\r' | sed 's/startEpochMs=//')
  read -r FIT_A FIT_B < <(python3 -I - "$RAW/events.log" "$A0" <<'PY'
import re, sys
a0 = float(sys.argv[2]); xs = []; ys = []
for l in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = re.search(r"mark sync audibleMs=([\d.]+) wavMs=([\d.]+)", l)
    if m: ys.append(float(m.group(1)) - a0); xs.append(float(m.group(2)))
if len(xs) < 5: print(0, 1); sys.exit()
n = len(xs); mx = sum(xs) / n; my = sum(ys) / n
b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
print(round(my - b * mx, 1), f"{b:.7f}")
PY
)
  local STRETCH="asetrate=$(python3 -c "print(48000 / $FIT_B)"),aresample=48000:filter_size=64:cutoff=0.97"
  local X264="-c:v libx264 -preset slow -crf 14 -pix_fmt yuv420p -r $FPS"
  # 녹화 조각마다 소리를 맞춰 붙인다
  for k in "${!VS[@]}"; do
    [[ -f "$RAW/rec$k.webm" ]] || continue
    local V=${VS[$k]}
    local D; D=$(python3 -c "print(round($A0 + $FIT_A - $SKEW - $V))")
    local AF; if (( D >= 0 )); then AF="$STRETCH,adelay=${D}|${D},apad"; else AF="atrim=start=$(python3 -c "print(${D#-}/1000)"),asetpts=PTS-STARTPTS,$STRETCH,apad"; fi
    ffmpeg -hide_banner -loglevel error -y -i "$RAW/rec$k.webm" -i "$RAW/app_audio.wav" -map 0:v -map 1:a \
      -vf "crop=$VID_CROP,scale=-2:1080:flags=lanczos,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:black,setsar=1" $X264 \
      -af "$AF" -c:a aac -b:a 256k -ar 48000 -shortest "$RAW/seg$k.mp4"
    echo "  조각 $k: 소리 지연 ${D}ms"
  done
  # 편집: 구간을 잘라 0.25초 페이드로 잇는다(데모 시작 기준 초 → 조각 영상 시각)
  local LIST="$RAW/concat.txt"; : > "$LIST"; local i=0
  for c in $CUTS; do
    IFS=: read -r k a b <<< "$c"
    local V=${VS[$k]}
    local ss; ss=$(python3 -c "print(($T0 - $V)/1000 + $a)"); local dur=$((b - a))
    ffmpeg -hide_banner -loglevel error -y -ss "$ss" -i "$RAW/seg$k.mp4" -t "$dur" \
      -vf "fade=t=in:st=0:d=0.25,fade=t=out:st=$(python3 -c "print($dur-0.25)"):d=0.25" \
      -af "afade=t=in:st=0:d=0.25,afade=t=out:st=$(python3 -c "print($dur-0.25)"):d=0.25" $X264 -c:a aac -b:a 256k -ar 48000 "$RAW/cut$i.mp4"
    echo "file 'cut$i.mp4'" >> "$LIST"; i=$((i + 1))
  done
  ffmpeg -hide_banner -loglevel error -y -f concat -safe 0 -i "$LIST" -c copy -movflags +faststart "$OUT_ABS/demo_$flow.mp4"
  echo "  demo_$flow.mp4: $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT_ABS/demo_$flow.mp4")초"
  ffmpeg -hide_banner -i "$OUT_ABS/demo_$flow.mp4" -af volumedetect -f null - 2>&1 | grep -E "mean_volume|max_volume" | sed 's/.*\] /    소리 /'
}

if [[ $WHAT == video || $WHAT == all ]]; then
  echo "[A] demo:safe 녹화"; record_flow safe
  echo "[B] demo:rough 녹화"; record_flow rough
fi
echo "결과: $OUT_ABS"; ls "$OUT_ABS" "$OUT_ABS/panel"
