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
   - **데모 드라이브(74초)**: 출발 드롭 → 부드러운 가속(아르페지오) → 정속 → 방향지시등(셰이커)·차선 변경 → 부드러운 제동(근음+5도) → 신호 대기 브레이크다운 → 재출발 드롭 → 비 → 급제동(테이프스톱) → 회복 → 정차 → 주차 순서로 차량 신호를 주입해 음악 반응을 보여줍니다.
   - **실제 차량 신호**: 차량(또는 에뮬레이터 Virtual Console)의 속도·기어·브레이크·방향지시등·와이퍼·조향·착좌 신호로 연주합니다. 속도 권한(`android.car.permission.CAR_SPEED`)을 허용해야 합니다.

## 화면

| 무드(정차, 오늘 커버가 은은하게) | 무드(주행 중 — 완전 정지) | 주차 후 1분 제안 | 1분 호흡 |
|---|---|---|---|
| ![](docs/screens/01_mood_parked.png) | ![](docs/screens/02_mood_driving.png) | ![](docs/screens/03_park_offer.png) | ![](docs/screens/04_breath.png) |
| **마음 앨범(새 앨범 강조)** | **앨범 상세(회고 있음)** | **앨범 상세(회고 입력)** | **같은 날 두 번째 주행** |
| ![](docs/screens/05_album_grid.png) | ![](docs/screens/06_album_detail.png) | ![](docs/screens/07_album_detail_input.png) | ![](docs/screens/08_album_second_trip.png) |
| **정차 직전(주행 중 정지 화면)** | **정차 순간: 지난 구간이 커버에 한 겹** | **정차 3초 뒤** | **마지막 정차: 빗줄기·흐트러진 물결 자국** |
| ![](docs/screens/09_stop_before.png) | ![](docs/screens/10_stop_reveal.png) | ![](docs/screens/11_stop_after.png) | ![](docs/screens/12_last_stop_reveal.png) |

앱 창 영역(1682×1210dp)만 자른 이미지입니다. 차량 화면 전체(Pleos Connect v2.1.0 프로필 2560×1440)는 같은 이름의 `*_full.png`를 보세요.

- 주행 중(CarUxRestrictions `requiresDistractionOptimization`)에는 단색 배경과 추상 그래픽, 재생/일시정지 버튼만 보이고 **그래픽이 완전히 멈춥니다**(Android 차량 앱 품질 기준 SA-1). 음악 상태 변화는 정차하는 순간 한 번에 반영됩니다.
- "운전 중엔 귀로, 멈추면 눈으로": 주행하며 오늘의 앨범 커버를 계속 그려 두고(화면엔 반영 안 함), 정차하는 순간 지난 구간이 커버에 한 겹 추가되는 장면을 보여줍니다. 부드럽게 달린 구간은 둥근 원이 크게, 비 온 구간은 빗줄기, 급가속·급제동(테이프스톱)은 물결이 흐트러진 자국. 주차하면 그 커버가 그대로 마음 앨범의 새 앨범이 됩니다.
- 주차(P단) 후 운전석에만 사람이 있을 때 "1분 숨 고르기"를 제안합니다. 동승자가 있으면 제안하지 않습니다.
- 마음 앨범: 주행 한 번이 앨범 한 장이 됩니다. 정사각 커버 격자에 제목 한 줄, 커버를 누르면 큰 커버·날짜·회고 한 줄을 봅니다. 커버는 그날 운전의 부드러움·비·시간대로 그린 추상 그래픽이고, 회고 한 줄은 정차 중에만 남길 수 있습니다. 같은 날 같은 시간대 제목이 겹치면 "…빗속의 두 번째 길"처럼 구분합니다. 기록은 기기 안에만 저장합니다.
- 차량 화면 기준: 터치 영역 76dp 이상, 본문 24sp·제목 32sp 이상.

## 테스트 환경 및 필요한 설정

- 에뮬레이터: Pleos Connect v2.1.0 (API 34, arm64), 차량 서비스 2.3.22. AVD 하드웨어 프로필은 공식 가이드대로 **Pleos Connect v2.1.0**(2560×1440, 160dpi), RAM 4GB, Cold boot
- Virtual Console: `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`에서 속도·기어·브레이크·방향지시등·와이퍼·조향·착좌를 조작
- 사용 SDK: Pleos Vehicle SDK `ai.pleos.playground:Vehicle:2.3.2`(Vehicle State, 읽기 전용), AAOS `CarPropertyManager`
- 사용 권한(읽기 전용): `android.car.permission.CAR_SPEED`, `android.car.permission.CAR_POWERTRAIN`, `pleos.car.permission.CAR_DRIVE`, `CAR_LIGHTS`, `CAR_WINDOWS`, `CAR_SEATS`
- 단위 테스트: `./gradlew :app:testDebugUnitTest`
- 디버그 빌드 화면 바로 열기: `adb shell am start -n app.playdrive/.MainActivity --es debug <명령>`
  - `demo`(74초 데모 드라이브), `screen:mood`, `screen:drive`(주행 중 모습), `screen:park`(주차 후 1분 제안), `screen:breath`, `screen:album`, `seed:albums`(예시 앨범 3장)

## APK

- 위치: `apk/` 폴더(제출용 release 빌드 예정). 용량이 크면 GitHub Releases에 올립니다.

## 음원

- 음원: Suno 유료 플랜(Pro)으로 생성, 상업적 이용 가능.
- 기본 음원(SONG): Suno로 만든 90 BPM 로파이 곡 한 곡을 악기별 스템(drums, bass, keys, guitar, synth, perc)으로 분리한 뒤, 모든 스템을 같은 8마디 구간으로 잘라 `app/src/main/res/raw/song_*.ogg`(OGG Vorbis)로 넣었습니다.
- 보조 음원(LOOPS): 스템별로 따로 생성한 4마디 루프 `lofi_*.ogg`, 효과음(테이프 스톱, 스크래치) 원샷.
- 사용할 세트는 `app/src/main/java/app/playdrive/music/stems/StemConfig.kt`에서 고릅니다(SONG / LOOPS / SYNTH).
- 분석·가공 스크립트: `tools/analyze_song.py`, `tools/process_stems.py`, `tools/find_tapestop.py`
- 원본 WAV는 저장소에 포함하지 않습니다.
- 반응형 연주 레이어(로즈풍 FM 음, 셰이커)는 앱 안에서 코드로 합성합니다(외부 음원 없음).
