# PlayDrive

**평소처럼 안전하게 운전하면, 그 운전이 실시간으로 한 곡의 음악이 되는 차량용 사운드 앱.**
페달·핸들·방향지시등이 악기가 되고, 부드럽게 운전할수록 연주가 완성됩니다. 급가속·급제동은 음악을 흐트러뜨리고, 다시 부드럽게 달리면 한 마디씩 회복합니다.

- 플랫폼: Pleos Connect (Android Automotive OS, API 34)
- 카테고리: Music/Audio

## 실행 방법

1. Android Studio(2025 이상), JDK 17, Android SDK Platform 35를 설치합니다.
2. Pleos Connect 에뮬레이터를 준비합니다.
   - SDK Manager > SDK Update Sites에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`을 추가합니다.
   - API 34 *Pleos Connect v2.1.0* system image를 설치한 뒤 Automotive AVD를 만듭니다(RAM 4GB, Cold boot).
   - Apple Silicon Mac에서 부팅 직후 멈추면 `-gpu swiftshader_indirect` 옵션으로 실행하세요.
3. 빌드·설치:
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
4. 앱을 실행하면 음원을 불러온 뒤(첫 실행 약 2초) 버튼이 활성화됩니다.
   - **데모 드라이브(60초)**: 출발 → 부드러운 가속 → 정속 → 차선 변경 → 급제동 → 회복 → 비 → 정차 → 주차 순서로 차량 신호를 주입해 음악 반응을 보여줍니다.
   - **실제 차량 신호**: 차량(또는 에뮬레이터 Virtual Console)의 속도·기어·브레이크·방향지시등·와이퍼·조향·착좌 신호로 연주합니다. 속도 권한(`android.car.permission.CAR_SPEED`)을 허용해야 합니다.

## 테스트 환경 및 필요한 설정

- 에뮬레이터: Pleos Connect v2.1.0 (API 34, arm64), 차량 서비스 2.3.22
- Virtual Console: `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`에서 속도·기어·브레이크·방향지시등·와이퍼·조향·착좌를 조작
- 사용 SDK: Pleos Vehicle SDK `ai.pleos.playground:Vehicle:2.3.2`(Vehicle State, 읽기 전용), AAOS `CarPropertyManager`
- 사용 권한(읽기 전용): `android.car.permission.CAR_SPEED`, `android.car.permission.CAR_POWERTRAIN`, `pleos.car.permission.CAR_DRIVE`, `CAR_LIGHTS`, `CAR_WINDOWS`, `CAR_SEATS`
- 단위 테스트: `./gradlew :app:testDebugUnitTest`

## APK

- 위치: `apk/` 폴더(제출용 release 빌드 예정). 용량이 크면 GitHub Releases에 올립니다.

## 음원

- 음원: Suno 유료 플랜(Pro)으로 생성, 상업적 이용 가능.
- 기본 음원(SONG): Suno로 만든 90 BPM 로파이 곡 한 곡을 악기별 스템(drums, bass, keys, guitar, synth, perc)으로 분리한 뒤, 모든 스템을 같은 8마디 구간으로 잘라 `app/src/main/res/raw/song_*.ogg`(OGG Vorbis)로 넣었습니다.
- 보조 음원(LOOPS): 스템별로 따로 생성한 4마디 루프 `lofi_*.ogg`, 효과음(테이프 스톱, 스크래치) 원샷.
- 사용할 세트는 `app/src/main/java/app/playdrive/music/stems/StemConfig.kt`에서 고릅니다(SONG / LOOPS / SYNTH).
- 분석·가공 스크립트: `tools/analyze_song.py`, `tools/process_stems.py`, `tools/find_tapestop.py`
- 원본 WAV는 저장소에 포함하지 않습니다.
