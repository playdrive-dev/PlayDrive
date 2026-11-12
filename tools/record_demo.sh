#!/usr/bin/env bash
# PlayDrive 시연 영상 녹화(에뮬레이터).
#
#   웰컴(검은 화면 → 빛이 피어남 + 차임) → 74초 데모 드라이브 → 굿바이 → 주차 후 제안 카드
#   → 1분 호흡(앞 10초) → 마음 앨범  을 한 번에 녹화하고, 앱 창 영역만 잘라 mp4로 만든다.
#
# 사용:
#   tools/record_demo.sh                 전체 시연(약 1분 50초)
#   tools/record_demo.sh --test 10       지금 화면을 10초만(화질·소리 확인용)
#   옵션: --out <폴더>(기본 demo_out/)  --install <디버그 APK>  --keep-albums(예시 앨범 초기화 안 함)
#
# 준비:
#   - 에뮬레이터: emulator -avd Pleos_Connect_v2.1.0_API34 -no-snapshot-load -gpu host -timezone Asia/Seoul
#   - 디버그 빌드 설치(앱 소리 저장 명령 rec:audio는 디버그 빌드에만 있음): ./gradlew assembleDebug
#     (release가 깔려 있으면 서명이 달라 --install 이 먼저 지우고 깐다)
#   - ffmpeg, python3
#
# 소리: 에뮬레이터 화면 녹화(adb emu screenrecord)와 scrcpy 모두 AAOS 차량 오디오 버스의 앱 소리를 담지 못해
#       (측정: 무음 -62~-91dB) 앱이 AudioTrack에 쓰는 PCM을 그대로 WAV로 저장한 뒤 영상에 합친다.
#       시간 맞춤 = WAV 첫 샘플 시각(기기 벽시계) − 기기·맥 시계 차이 − 영상 시작 시각.
set -euo pipefail

PKG=app.playdrive
ACT=$PKG/.MainActivity
OUT=demo_out
TEST=0
APK=""
SEED=1
CROP=${CROP:-1680:1206:855:92}   # Pleos Connect v2.1.0(2560×1440)의 앱 창(853,90)-(2535,1300)에서 테두리 2px를 뺀 영역 w:h:x:y
FPS=30

while [[ $# -gt 0 ]]; do
  case $1 in
    --test) TEST=${2:-10}; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --install) APK=$2; shift 2 ;;
    --keep-albums) SEED=0; shift ;;
    *) echo "알 수 없는 옵션: $1"; exit 1 ;;
  esac
done

now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
wait_s() { python3 -c "import time; time.sleep($1)"; }
am() { adb shell "am start -n $ACT --es debug '$1'" >/dev/null; }   # ';'가 기기 셸에서 끊기지 않게 따옴표

# 화면에서 글자/설명으로 버튼을 찾아 가운데를 누른다(최대 $2초 기다림)
tap_ui() {
  local label=$1 timeout=${2:-10} t0; t0=$(date +%s)
  while (( $(date +%s) - t0 < timeout )); do
    adb shell uiautomator dump /sdcard/pd_ui.xml >/dev/null 2>&1 || true
    local b; b=$(adb shell cat /sdcard/pd_ui.xml 2>/dev/null | python3 -c '
import sys, re
x = sys.stdin.read(); lab = sys.argv[1]
for n in re.findall(r"<node [^>]*>", x):
    if f"text=\"{lab}\"" in n or f"content-desc=\"{lab}\"" in n:
        a = list(map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n).groups()))
        print((a[0]+a[2])//2, (a[1]+a[3])//2); break' "$label")
    if [[ -n $b ]]; then adb shell input tap $b; echo "  누름: $label ($b)"; return 0; fi
    wait_s 0.5
  done
  echo "  못 찾음: $label"; return 1
}

# ---------------------------------------------------------------- 점검
adb get-state >/dev/null || { echo "에뮬레이터가 연결되어 있지 않습니다"; exit 1; }
[[ $(adb shell getprop sys.boot_completed | tr -d '\r') == 1 ]] || { echo "부팅이 끝나지 않았습니다"; exit 1; }
TZ_DEV=$(adb shell getprop persist.sys.timezone | tr -d '\r')
echo "기기 시간대: $TZ_DEV  ($(adb shell date | tr -d '\r'))"
[[ $TZ_DEV == Asia/Seoul ]] || echo "경고: 시간대가 Asia/Seoul이 아닙니다. 에뮬레이터를 -timezone Asia/Seoul 로 다시 켜세요(앨범 제목·색이 시간대를 따름)."

if [[ -n $APK ]]; then
  adb uninstall $PKG >/dev/null 2>&1 || true
  adb install -r "$APK" >/dev/null && echo "설치: $APK"
fi
adb shell dumpsys package $PKG | grep -q "DEBUGGABLE" || { echo "디버그 빌드가 아닙니다(앱 소리 저장 불가). --install app/build/outputs/apk/debug/app-debug.apk"; exit 1; }
CUR_USER=$(adb shell am get-current-user | tr -d '\r')
adb shell pm grant --user "$CUR_USER" $PKG android.car.permission.CAR_SPEED 2>/dev/null || true   # 권한 창이 영상에 안 나오게

mkdir -p "$OUT"
dev_cat() { adb exec-out run-as $PKG --user "$CUR_USER" cat "files/$1"; }   # 앱 내부 파일(디버그 빌드만 run-as 가능)
VIDEO_RAW="$OUT/video_raw.webm"; AUDIO="$OUT/app_audio.wav"; FINAL="$OUT/$( ((TEST)) && echo test_${TEST}s || echo demo ).mp4"
rm -f "$VIDEO_RAW" "$AUDIO" "$FINAL"
# 에뮬레이터 녹화 파일은 맥 쪽에 쓰인다(절대 경로)
VIDEO_ABS=$(cd "$OUT" && pwd)/video_raw.webm

# 기기 벽시계 − 맥 시계(왕복 시간의 절반 보정)
SKEW=$(python3 - <<'EOF'
import subprocess, time
best = None
for _ in range(5):
    t0 = time.time() * 1000
    d = int(subprocess.check_output(["adb", "shell", "echo $EPOCHREALTIME"]).decode().strip().replace(".", "")[:13])
    t1 = time.time() * 1000
    rtt = t1 - t0; s = d - (t0 + t1) / 2
    if best is None or rtt < best[0]: best = (rtt, s)
print(int(best[1]))
EOF
)
echo "기기−맥 시계 차이: ${SKEW}ms"

# ---------------------------------------------------------------- 녹화
if (( TEST )); then
  LEN=$TEST
  echo "테스트 녹화 ${LEN}초(지금 화면)"
  am "rec:audio:on"; wait_s 0.5
  V0=$(now_ms); adb emu screenrecord start --time-limit $LEN --fps $FPS "$VIDEO_ABS" >/dev/null
  wait_s $((LEN + 1))
  am "rec:audio:off"
else
  LEN=150   # 안전 상한. 장면이 끝나면 직접 멈춘다
  if (( SEED )); then
    adb shell am force-stop $PKG; am "seed:albums"; wait_s 4   # 예시 앨범 6장으로 초기화
  fi
  adb shell am force-stop $PKG; wait_s 1
  adb shell input keyevent KEYCODE_HOME 2>/dev/null || true; wait_s 1
  echo "녹화 시작"
  V0=$(now_ms); adb emu screenrecord start --time-limit $LEN --fps $FPS "$VIDEO_ABS" >/dev/null
  wait_s 1
  # 웰컴: 디버그·데모 버튼을 첫 프레임부터 숨기고, 음악이 준비되면 앱 소리 저장 시작
  am "icon:debug:off;icon:demo:off;rec:audio:on"
  wait_s 7          # 검은 화면 → 3초 동안 빛이 피어남 → 차임과 함께 음악
  echo "데모 드라이브(74초)"
  am "demo"
  wait_s 74         # 주행(화면 정지) → 정차 장면 → 주차
  echo "굿바이 → 제안 카드 기다림"
  tap_ui "좋아요" 30 || true      # 제안 카드가 뜨면(마지막 정차 장면 4초 + 굿바이 2초 뒤) 잠깐 보여 주고 누름
  echo "호흡 10초"
  wait_s 10
  am "screen:album" # 호흡을 닫고 마음 앨범(방금 생긴 앨범이 첫 칸에 강조)
  wait_s 6
  am "rec:audio:off"; wait_s 0.5
  adb emu screenrecord stop >/dev/null || true
fi
wait_s 2

# ---------------------------------------------------------------- 소리 가져와 합치기
dev_cat demo_audio.wav > "$AUDIO"
A0=$(dev_cat demo_audio.txt | tr -d '\r' | sed 's/startEpochMs=//')
DELAY=$(( A0 - SKEW - V0 ))
echo "영상 시작 대비 소리 시작: ${DELAY}ms"
(( DELAY < 0 )) && { echo "소리가 영상보다 먼저 시작함 → 앞부분을 잘라 맞춤"; }

if (( DELAY >= 0 )); then AF="adelay=${DELAY}|${DELAY},apad"; else AF="atrim=start=$(python3 -c "print(${DELAY#-}/1000)"),asetpts=PTS-STARTPTS,apad"; fi
ffmpeg -hide_banner -loglevel error -y -i "$VIDEO_RAW" -i "$AUDIO" \
  -map 0:v -map 1:a -vf "crop=$CROP,format=yuv420p" -r $FPS \
  -c:v libx264 -preset slow -crf 16 -c:a aac -b:a 192k -af "$AF" -shortest -movflags +faststart "$FINAL"

echo
echo "결과: $FINAL"
ffprobe -v error -show_entries format=duration:stream=codec_name,width,height,r_frame_rate -of compact "$FINAL"
ffmpeg -hide_banner -i "$FINAL" -af volumedetect -vn -f null - 2>&1 | grep -E "mean_volume|max_volume" | sed 's/.*\] /  소리 /'
echo "원본: $VIDEO_RAW (2560×1440, 에뮬레이터 마이크 소리 — 쓰지 않음), $AUDIO (앱 소리)"
