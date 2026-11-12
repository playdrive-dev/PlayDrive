#!/usr/bin/env bash
# PlayDrive 시연 영상 원본 녹화(에뮬레이터). 편집용 원본이라 품질 우선.
#
#   웰컴(빛 피어남) → 74초 데모 드라이브(LIVE 효과) → 주차·굿바이 → 1분 숨 고르기 제안 → 호흡 10초 → 마음 앨범 → 앨범 상세
#
# 결과(기본 docs/demo/, git 제외):
#   video_raw.webm            에뮬레이터 녹화 원본(2560×1440, VP9, 30fps)
#   app_screen_2560x1440.mp4  차량 화면 전체 원본 해상도(H.264 CRF 12)
#   app_screen.mp4            앱 창만 잘라 1920×1080 안에 맞춤(남는 곳 검정, H.264 CRF 12)
#   app_audio.wav             앱 최종 믹스(스템 + 연주음 + 효과음), 32bit float 48kHz 스테레오
#   app_with_audio.mp4        app_screen + app_audio 싱크 맞춤(AAC 320k)
#   timeline.md               장면별 시작 시각(영상 기준)
#   sync_check.md             드롭 순간 화면 반응 vs 드럼 진입(소리) 차이
#   events.log, sync.txt      시각 계산에 쓴 기기 로그·시계 값
#
# 사용: tools/record_demo.sh [--out <폴더>] [--install <디버그 APK>] [--keep-albums]
#       tools/record_demo.sh --test 10     지금 화면 10초만(화질·소리 확인)
#
# 준비: emulator -avd Pleos_Connect_v2.1.0_API34 -no-snapshot-load -gpu host -timezone Asia/Seoul
#       디버그 빌드 설치(앱 소리 저장 record:start|stop 은 디버그 빌드에만). ffmpeg, python3(numpy).
#
# 소리: 에뮬레이터 화면 녹화·scrcpy는 AAOS 차량 오디오 버스의 앱 소리를 담지 못한다(측정: −62~−91dB, 사실상 무음).
#       그래서 앱이 AudioTrack에 쓰는 최종 믹스를 그대로 WAV로 저장해 합친다.
#       시간 맞춤 = WAV 첫 샘플 시각(기기 벽시계) − (기기 − 맥 시계 차이) − 영상 시작 시각.
#       화면 효과도 같은 믹서 위치(박·드럼)를 보고 반응하므로 같은 기준으로 맞는다. 확인: tools/check_sync.py
set -euo pipefail

PKG=app.playdrive
ACT=$PKG/.MainActivity
OUT=docs/demo
TEST=0
APK=""
SEED=1
CROP=${CROP:-1680:1206:855:92}   # Pleos Connect v2.1.0(2560×1440)의 앱 창(853,90)-(2535,1300)에서 테두리 2px를 뺀 영역 w:h:x:y
FPS=30
HERE=$(cd "$(dirname "$0")" && pwd)

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
am() { adb shell "am start -n $ACT --es debug '$1'" >/dev/null 2>&1; }   # ';'가 기기 셸에서 끊기지 않게 따옴표

# 화면에서 글자/설명으로 버튼을 찾는다(최대 $2초). 찾으면 가운데 좌표를 출력.
find_ui() {
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
    if [[ -n $b ]]; then echo "$b"; return 0; fi
    wait_s 0.5
  done
  return 1
}

# ---------------------------------------------------------------- 점검
adb get-state >/dev/null || { echo "에뮬레이터가 연결되어 있지 않습니다"; exit 1; }
[[ $(adb shell getprop sys.boot_completed | tr -d '\r') == 1 ]] || { echo "부팅이 끝나지 않았습니다"; exit 1; }
TZ_DEV=$(adb shell getprop persist.sys.timezone | tr -d '\r')
echo "기기 시간대: $TZ_DEV  ($(adb shell date | tr -d '\r'))"
[[ $TZ_DEV == Asia/Seoul ]] || echo "경고: 시간대가 Asia/Seoul이 아닙니다. 에뮬레이터를 -timezone Asia/Seoul 로 다시 켜세요(앨범 제목·색이 시간대를 따름)."
adb shell getprop ro.boot.qemu.gles 2>/dev/null | grep -q . && echo "GPU 모드: $(adb shell getprop ro.boot.qemu.gltransport | tr -d '\r')"

if [[ -n $APK ]]; then
  adb uninstall $PKG >/dev/null 2>&1 || true
  adb install -r "$APK" >/dev/null && echo "설치: $APK"
fi
adb shell dumpsys package $PKG | grep -q "DEBUGGABLE" || { echo "디버그 빌드가 아닙니다(앱 소리 저장 불가). --install app/build/outputs/apk/debug/app-debug.apk"; exit 1; }
CUR_USER=$(adb shell am get-current-user | tr -d '\r')
adb shell pm grant --user "$CUR_USER" $PKG android.car.permission.CAR_SPEED 2>/dev/null || true   # 권한 창이 영상에 안 나오게
dev_cat() { adb exec-out run-as $PKG --user "$CUR_USER" cat "files/$1"; }   # 앱 내부 파일(디버그 빌드만 run-as 가능)

mkdir -p "$OUT"
OUT_ABS=$(cd "$OUT" && pwd)
VIDEO_RAW="$OUT_ABS/video_raw.webm"; AUDIO="$OUT_ABS/app_audio.wav"
rm -f "$VIDEO_RAW" "$AUDIO" "$OUT_ABS"/app_*.mp4 "$OUT_ABS"/timeline.md "$OUT_ABS"/events.log "$OUT_ABS"/sync*.{txt,md}

# 기기 벽시계 − 맥 시계(왕복 시간이 가장 짧은 측정의 가운데 기준)
SKEW=$(python3 - <<'PY'
import subprocess, time
best = None
for _ in range(9):
    t0 = time.time() * 1000
    d = int(subprocess.check_output(["adb", "shell", "echo $EPOCHREALTIME"]).decode().strip().replace(".", "")[:13])
    t1 = time.time() * 1000
    rtt = t1 - t0; s = d - (t0 + t1) / 2
    if best is None or rtt < best[0]: best = (rtt, s)
print(int(best[1]))
PY
)
echo "기기−맥 시계 차이: ${SKEW}ms"

# ---------------------------------------------------------------- 녹화
adb logcat -c
if (( TEST )); then
  echo "테스트 녹화 ${TEST}초(지금 화면)"
  am "record:start"; wait_s 0.5
  V0=$(now_ms); adb emu screenrecord start --time-limit "$TEST" --fps $FPS "$VIDEO_RAW" >/dev/null
  wait_s $((TEST + 1))
  am "record:stop"
else
  if (( SEED )); then adb shell am force-stop $PKG; am "seed:albums"; wait_s 4; fi   # 예시 앨범 6장으로 초기화
  adb shell am force-stop $PKG; wait_s 1
  adb shell input keyevent KEYCODE_HOME 2>/dev/null || true; wait_s 1
  echo "녹화 시작"
  V0=$(now_ms); adb emu screenrecord start --time-limit 170 --fps $FPS "$VIDEO_RAW" >/dev/null
  wait_s 1
  # 웰컴: 디버그·데모·설정 버튼을 첫 프레임부터 숨기고, 효과 LIVE, 음악이 준비되면 앱 소리 저장 시작
  L0=$(now_ms); am "icon:debug:off;icon:demo:off;effect:live;record:start"
  wait_s 7          # 검은 화면 → 3초 동안 빛이 피어남 → 차임과 함께 음악
  echo "데모 드라이브(74초)"
  am "demo"
  wait_s 70
  echo "주차·굿바이 → 숨 고르기 제안 기다림"
  if B=$(find_ui "좋아요" 40); then wait_s 3; adb shell input tap $B; echo "  좋아요 누름"; else echo "  제안 카드를 못 찾음"; fi
  echo "호흡 10초"
  wait_s 10
  am "screen:album"; wait_s 5     # 마음 앨범(방금 생긴 앨범이 첫 칸에 강조)
  am "screen:detail"; wait_s 6    # 앨범 상세(방금 생긴 앨범)
  am "record:stop"; wait_s 0.5
  adb emu screenrecord stop >/dev/null || true
fi
wait_s 3
adb logcat -d -v epoch > "$OUT_ABS/events.log"

# ---------------------------------------------------------------- 결과물
dev_cat demo_audio.wav > "$AUDIO"
A0=$(dev_cat demo_audio.txt | tr -d '\r' | sed 's/startEpochMs=//')
# 1초마다 남긴 (WAV 위치, 들리는 시각)을 직선으로 맞춰 오프셋·기울기(오디오 시계 드리프트)를 구한다
read -r FIT_A FIT_B < <(python3 -I - "$OUT_ABS/events.log" "$A0" <<'PY'
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
DELAY=$(python3 -c "print(round($A0 + $FIT_A - $SKEW - $V0))")
echo "영상 시작 대비 소리 시작: ${DELAY}ms (직선 맞춤 오프셋 ${FIT_A}ms, 오디오 시계 비율 ${FIT_B})"
STRETCH="asetrate=$(python3 -c "print(48000 / $FIT_B)"),aresample=48000:filter_size=64:cutoff=0.97,"
if (( DELAY >= 0 )); then AF="${STRETCH}adelay=${DELAY}|${DELAY},apad"; else AF="atrim=start=$(python3 -c "print(${DELAY#-}/1000)"),asetpts=PTS-STARTPTS,${STRETCH}apad"; fi

X264="-c:v libx264 -preset slow -crf 12 -pix_fmt yuv420p -r $FPS -movflags +faststart"
ffmpeg -hide_banner -loglevel error -y -i "$VIDEO_RAW" -an $X264 "$OUT_ABS/app_screen_2560x1440.mp4"
ffmpeg -hide_banner -loglevel error -y -i "$VIDEO_RAW" -an \
  -vf "crop=$CROP,scale=-2:1080:flags=lanczos,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:black,setsar=1" $X264 "$OUT_ABS/app_screen.mp4"
ffmpeg -hide_banner -loglevel error -y -i "$OUT_ABS/app_screen.mp4" -i "$AUDIO" -map 0:v -map 1:a -c:v copy \
  -af "$AF" -c:a aac -b:a 320k -ar 48000 -shortest -movflags +faststart "$OUT_ABS/app_with_audio.mp4"

printf 'video_start_ms=%s\nskew_ms=%s\naudio_start_ms=%s\nfit_offset_ms=%s\naudio_clock_ratio=%s\naudio_delay_ms=%s\n' "$V0" "$SKEW" "$A0" "$FIT_A" "$FIT_B" "$DELAY" > "$OUT_ABS/sync.txt"
if (( ! TEST )); then
  echo; echo "싱크 확인(드럼 진입 vs 화면 드롭 반응):"
  python3 -I "$HERE/check_sync.py" "$OUT_ABS/app_with_audio.mp4" "$OUT_ABS/events.log" --audio-delay-ms "$DELAY" --audio-scale "$FIT_B" | tee "$OUT_ABS/sync_check.md" || true
  python3 "$HERE/demo_timeline.py" "$OUT_ABS/events.log" --video-start-ms "$V0" --skew-ms "$SKEW" --launch-ms "${L0:-$V0}" > "$OUT_ABS/timeline.md"
  echo; cat "$OUT_ABS/timeline.md"
fi

echo
echo "결과: $OUT_ABS"
ls -la "$OUT_ABS"
for f in app_screen.mp4 app_with_audio.mp4; do echo "$f: $(ffprobe -v error -show_entries format=duration:stream=codec_name,width,height,r_frame_rate -of compact "$OUT_ABS/$f" | tr '\n' ' ')"; done
ffmpeg -hide_banner -i "$AUDIO" -af volumedetect -f null - 2>&1 | grep -E "mean_volume|max_volume" | sed 's/.*\] /  소리 /'
