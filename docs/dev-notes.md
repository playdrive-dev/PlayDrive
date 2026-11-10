# 개발 노트

개발하면서 확인한 실제 API 이름, 권한, 에뮬레이터 동작, 성능 수치를 여기에 기록한다.

## 2026-11-10 — 1단계: 개발 환경 확인

| 항목 | 요구 | 확인 결과 | 상태 |
|---|---|---|---|
| Android Studio | 설치 | 2026.1 (`/Applications/Android Studio.app`, 내장 JBR 25.0.2) | ✅ |
| JDK 17 | 17 | 없음. 설치된 JDK: JBR 21.0.11(기본), Zulu 21.0.2, Corretto 8 | ❌ |
| Android SDK Platform 34 | 설치 | `platforms/android-34` | ✅ |
| Android SDK Platform 35 | 설치 | `platforms/android-35` (+ build-tools 35.0.0) | ✅ |
| minSdk 28 플랫폼 | 참고 | `platforms/android-28` 있음 | ✅ |
| Emulator / platform-tools | 설치 | Emulator 37.1.11.0, adb 있음, cmdline-tools latest | ✅ |
| Pleos SDK Update Site | 등록 | 등록된 사이트 없음 (`~/.android`에 Pleos 관련 설정 없음) | ❌ |
| Pleos Connect system image (API 34) | 설치 | 없음. `system-images/android-36/google_apis`만 존재(내용 비어 있음) | ❌ |
| Pleos Connect AVD | 생성 | `~/.android/avd` 비어 있음, AVD 0개 | ❌ |

- 셸 환경: `ANDROID_HOME` 미설정, `sdkmanager`/`avdmanager`/`emulator`/`gradle`이 PATH에 없음(SDK 경로: `~/Library/Android/sdk`). Android Studio에서 빌드하면 문제없음.
- 막힌 점: Pleos 에뮬레이터 URL은 Pleos Playground 가입·프로젝트 생성 후 받아야 함 → 사용자 조치 필요.
- 막힌 점: 현재 git 커밋 작성자 정보가 개인 이메일로 설정되어 있음. 제출 저장소는 익명이어야 하므로(8번) 첫 커밋 전에 저장소 로컬 user.name/user.email을 익명 값으로 바꿔야 함.
- `docs/from-planning.md`: 아직 없음(새 지시 없음).
- 진행: Pleos Playground 프로젝트 생성 완료(프로젝트 ID, SDK 클라이언트 ID/시크릿, 테스트용 CRN 발급 확인). **키 값은 저장소에 커밋하지 않는다** → 프로젝트 생성 시 `local.properties`(gitignore) 또는 환경변수로 주입 예정. 에뮬레이터 SDK Update Site URL은 아직 미확인.

## 2026-11-10 — 앱 이름 확정 · JDK · git 설정

- 앱 이름을 **PlayDrive**로 확정. CLAUDE.md의 이름을 바꿨고, 지원서는 `docs/PlayDrive_접수양식_초안.docx`를 쓴다(옛 PlayDriving 초안은 삭제). 폴더 이름은 나중에 바꾼다.
- JDK 17: `brew install --cask temurin@17` 다운로드는 됐지만, pkg 설치 단계에서 sudo 비밀번호가 필요해 실패함 → 사용자가 터미널에서 직접 실행해야 함. (미완료)
- Pleos 에뮬레이터: SDK Update Site `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml` → API 34 Pleos Connect system image → Automotive AVD(Pleos Connect v2.1.0, RAM 4GB, Cold boot). 사용자가 Android Studio에서 직접 설치. (진행 중)
- git: 이 저장소에만(`--local`) 작성자 이름 `playdrive-dev`, 이메일은 익명 noreply로 설정함. 전역 설정은 건드리지 않음. 원격 `origin` = https://github.com/playdrive-dev/PlayDrive.git. 아직 커밋·push 안 함.
- `.gitignore`가 없어서 새로 만듦: local.properties, *.keystore/*.jks, .idea/, build/, .gradle/, *.apk(단, 제출용 `apk/*_release.apk`는 예외) 등.
- 막힌 점: GitHub 인증 방식(gh CLI 또는 토큰)은 아직 정하지 않음. gh는 설치되어 있지 않음. 전역 credential helper가 osxkeychain이라, 기존 개인 GitHub 자격증명이 쓰일 수 있으니 주의.
- GitHub 인증은 gh CLI로 하기로 함. gh 2.103.3 설치 완료. playdrive-dev 계정 로그인(`gh auth login`)은 사용자가 직접 할 차례.

## 2026-11-10 — 인증 확인 · 2단계: 빈 Automotive 프로젝트 · 빌드

- JDK 17 설치 확인: Temurin 17.0.21 (`/Library/Java/JavaVirtualMachines/temurin-17.jdk`). 기본 `java`는 여전히 JBR 21이라, 빌드할 때는 `JAVA_HOME=$(/usr/libexec/java_home -v 17)`을 지정하거나 Android Studio에서 Gradle JDK를 17로 설정한다.
- gh 로그인 확인: 계정 playdrive-dev (scopes: repo, read:org, gist).
- 이 저장소에서만(local) GitHub 자격증명 도우미를 키체인 대신 `gh auth git-credential`로 바꿈. `git credential fill`을 실행하면 username=playdrive-dev가 나오는 것을 확인함. 원격 저장소는 접근 가능하고 아직 비어 있음.
- 프로젝트 구성: AGP 8.9.1, Gradle 8.11.1(wrapper), Kotlin 2.2.10. compileSdk/targetSdk 35, minSdk 28. 패키지명/applicationId는 `app.playdrive`(실명·회사명 없음).
- 매니페스트: `android.hardware.type.automotive` required. Activity에 `distractionOptimized` 메타데이터를 넣음(주행 중 화면 정책은 7단계에서 구현).
- Pleos: settings.gradle.kts에 Maven 저장소를 추가하고 `ai.pleos.playground:Vehicle:2.3.2`를 의존성으로 넣음. 저장소는 **인증 없이** 받아짐. 같이 들어오는 의존성은 vehicle-aidl 1.1.1, vehicle-data 1.1.11, kotlinx-coroutines-android 1.6.3, gson 2.10.1, kotlin-reflect 1.9.25.
- `./gradlew assembleDebug assembleRelease` **BUILD SUCCESSFUL**(45초). release는 아직 서명하지 않음(9단계에서 서명 설정).
- SDK AAR에서 확인한 사실(지원서 5번에 반영할 것):
  - SDK 매니페스트가 `ai.pleos.playground.service.vehicle.VEHICLE_BINDING` 권한과 `ai.pleos.playground.vehicle.VEHICLE_START` queries를 **자동으로 병합**함.
  - SDK가 요구하는 차량 서비스 최소 버전은 2.3.13(versionCode 65) → 에뮬레이터의 Vehicle 서비스 버전을 확인해야 함.
  - `Vehicle(context)` → `initialize()` / `release()`. 도메인 객체는 `getTurnSignal()`, `getBrake()`, `getSteeringWheel()`, `getWiper()`, `getSeat()`, `getGear()`, `getOdometer()` 등.
  - TurnSignal: `checkTurnSignalStateCapabilities(onComplete:(CapabilityConfig)->Unit, onFailed:(Exception)->Unit)`, `getCurrentTurnSignalState(onComplete:(TurnSignalValue)->Unit, onFailed)`, `registerTurnSignalState(TurnSignalStateListener)` / `unregisterTurnSignalState(같은 리스너)`.
  - Pleos Vehicle SDK 안에도 `Gear` API가 있음 → 기어 신호를 CarPropertyManager로 받을지 비교 검토(3단계).
- 익명성 점검: 커밋할 텍스트 파일에서 사용자명·개인 이메일·실명이 나오지 않는 것을 확인함. 다만 지원서 docx의 문서 속성(마지막 수정자)에 실명·소속으로 보이는 값이 있음 → `docs/*.docx`를 .gitignore에 추가해 저장소에서 뺌. 이메일로 제출하기 전에 Word에서 문서 속성을 정리할 것(파일 > 정보 > 문제 확인 > 문서 검사).
- 첫 커밋 `636f110`(브랜치 main, 작성자 playdrive-dev, 익명 noreply). 아직 push 안 함. 다음 단계: 에뮬레이터가 준비되면 3단계(방향지시등 Capability·현재값 호출).

## 2026-11-10 — 3단계 시도: 에뮬레이터 연결 (막힘)

- `adb devices`: 연결된 기기 없음. 실행 중인 에뮬레이터(qemu) 프로세스도 없음.
- `~/.android/avd`가 비어 있음(AVD 0개). `system-images`에도 Pleos Connect API 34 이미지가 없음(android-36/google_apis 빈 폴더만 있음).
- → 이 Mac에서는 Pleos 시스템 이미지 설치와 AVD 생성이 아직 끝나지 않은 것으로 보임. 차량 서비스 버전 확인, Virtual Console, 3단계는 진행하지 못함.

## 2026-11-10 — Pleos 에뮬레이터 터미널 설치

- 시스템 이미지 목록 XML(arm64): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
  - 패키지 경로 `system-images;android-34;pleos_car_emulator;arm64-v8a`, tag `android-automotive`("Pleos Connect v2.1.0 APIs"), vendor `pleos`, revision 2, emulator 24 이상 필요.
  - 아카이브: `.../connect-avd/2.x/crp_car_arm64_emulator.zip`, 4,669,762,816 B(4.67GB), sha1 `24d28eb2f18eba5e87c861a7eb2bdccebb049419`.
  - 라이선스: 42dot Pleos Connect SDK License(CRP 호환 앱 개발 용도로 한정, 재배포·역공학 금지).
- 설치 방식: sdkmanager CLI는 Studio의 SDK Update Site를 읽지 않음 → zip을 직접 받아 SDK 경로에 풀기로 함. 다운로드 속도 약 5MB/s(약 15분).
- 다운로드 완료(약 13분), sha1 일치. 압축을 풀면 약 20GB(system.img 18.3GB, userdata 1.15GB, vendor 0.54GB). 디스크 여유가 51GB → 27GB로 줄어듦.
- zip 안 폴더 `crp_avd_out_arm64-v8a`를 `~/Library/Android/sdk/system-images/android-34/pleos_car_emulator/arm64-v8a`로 옮김. zip에 든 source.properties: TagId=android-automotive, VendorId=42dot, ApiLevel 34, 권장 RAM 8192.
- 막힌 점과 해결: zip에는 package.xml이 없어서 avdmanager가 이 경로를 `android-automotive`로 잘못 인식하고 AVD 생성을 거부함 → 원격 XML의 remotePackage 정보로 package.xml을 직접 만들어 넣으니 해결됨(Studio가 설치할 때 만드는 것과 같은 정보).
- AVD `Pleos_Connect_v2.1.0_API34`: 기기 automotive_1024p_landscape(1024x768), RAM 4096MB, `fastboot.forceColdBoot=yes`, GPU host.
- 실행 명령: `emulator -avd Pleos_Connect_v2.1.0_API34 -no-snapshot-load`

## 2026-11-10 — 에뮬레이터 부팅 · 3단계: 방향지시등 API 확인 ✅

### 에뮬레이터
- 첫 실행(`-gpu host`, 기본값): 부팅 직후 "detected a hanging thread 'QEMU2 main loop'" 오류가 나고 **exit 139로 비정상 종료**.
- 다시 실행(`-gpu swiftshader_indirect`): 정상 부팅(cold boot 약 7.5분)했고 60초 이상 안정적이었음. **이 Mac(M2, RAM 16GB)에서는 소프트웨어 GPU로 실행한다.**
  `emulator -avd Pleos_Connect_v2.1.0_API34 -no-snapshot-load -gpu swiftshader_indirect`
- 소프트웨어 GPU로 실행하면 시스템 앱 DrivingView(Unreal, `ai.umos.drivingview`)가 SIGSEGV로 반복해서 죽음. 우리 앱과는 무관함.
- 빌드: `Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/...:user/test-keys`
- **차량 서비스 `ai.pleos.playground.service.vehicle` 2.3.22 (versionCode 74)** → SDK 요구치 2.3.13(65)을 충족함.

### Virtual Console (`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088/)
- 접속됨(HTTP 200, 제목 "Pleos Connect Virtual Console"). 기능 목록: `GET /vehicle/catalog`, 현재 상태: `GET /vehicle/state`.
- **브라우저 세션 heartbeat가 필요함**: 쓰기 요청은 `POST /browser/session?client=<id>`를 1초마다 보내고 있어야 동작함. 안 보내면 `{"error":"emulator_inactive"}`. 쓰기는 `PUT`.
- 우리에게 필요한 항목은 모두 있음:

| 항목 | Console 기능 / 쓰기 API | 값 | CarProperty |
|---|---|---|---|
| 방향지시등 | `PUT /vehicle/turn-signal?mode=` | Off, BlinkLeft, BlinkRight, FlashLeft, FlashRight | TURN_SIGNAL_STATE 0x11400408 |
| 차량 속도 | `PUT /vehicle/controls?speed=` (별도 카드 없음, Motion 페달 영역) | 0~240 km/h | (DDS topic …DRIVINGINFO_ACTUAL_SPEED) |
| 브레이크 | `PUT /vehicle/controls?brake=` | 0~100 % | BRAKE_PEDAL_STATUS(vendor) 0x21200409 |
| 조향각 | `/vehicle/controls?steering=` | -540~540° | PERF_STEERING_ANGLE |
| 와이퍼 | `PUT /vehicle/body?front_wiper=` | Off, Auto, Low, High | WINDSHIELD_WIPERS_SWITCH 0x13400BC7 |
| 좌석 착좌 | `PUT /vehicle/seat?kind=occupied&area=driver&value=` | true/false, area: driver, passenger, rear_left, rear_right | SEAT_OCCUPANCY 0x15400BB0 |
| 기어 | `PUT /vehicle/gear?gear=` | Park, Reverse, Neutral, Drive | VENDOR_GEAR_SELECTION 0x21400400 |
- 그 밖에 정속주행·주행거리·주행시간·비상등·제동등 등이 있음. 속도/브레이크/조향은 `controls.dds_ready`가 false로 나오는데, 실제로 반영되는지는 6단계에서 확인할 것.

### 3단계 결과: 방향지시등 (TurnSignal)
- 권한 `pleos.car.permission.CAR_LIGHTS`: 정의한 곳은 `ai.pleos.playground.service.vehicle`, **protectionLevel=normal** → 설치하면 자동으로 허용됨(런타임 요청 불필요). SDK 메서드에도 `@RequiresPermission("pleos.car.permission.CAR_LIGHTS")`가 붙어 있음.
- 실제 API(Vehicle SDK 2.3.2):
  - `vehicle.getTurnSignal().checkTurnSignalStateCapabilities({ config: CapabilityConfig -> }, { e: Exception -> })` → `CapabilityConfig(accessType=READ)`
  - `getCurrentTurnSignalState({ value: TurnSignalValue? -> }, { e -> })` → `OFF`
  - `registerTurnSignalState(listener: TurnSignalStateListener)` → 등록 직후 현재값(OFF)이 한 번 들어옴. 그 뒤로는 변경될 때마다 호출됨.
  - `TurnSignalStateListener`는 fun interface가 **아님**: `onTurnSignalStateUpdated(value: TurnSignalValue?)`와 `onFailed(e: Exception)`(VehicleListener 상속) 두 메서드를 구현해야 함 → `object :`로 구현.
  - `TurnSignalValue` enum: `OFF(id=0)`, `RIGHT(id=1)`, `LEFT(id=2)`. Console의 BlinkLeft→LEFT, BlinkRight→RIGHT, Off→OFF로 확인함. Flash* 매핑은 아직 확인 안 함.
  - 콜백은 **메인이 아닌 SDK 바인더 스레드**에서 호출됨 → UI 갱신은 runOnUiThread/Flow로 메인에 넘겨야 함.
- 실행 로그(발췌):
  ```
  Vehicle.initialize() 완료
  Capability: accessType=READ (CapabilityConfig(accessType=READ))
  리스너 등록 완료
  현재값: OFF (id=0)
  변경 이벤트: LEFT (id=2)     ← Console BlinkLeft
  변경 이벤트: OFF (id=0)      ← Console Off
  ```
- 앱 콜드 스타트(am start -W TotalTime): 2.2~3.6초(소프트웨어 GPU 에뮬레이터 기준).

## 2026-11-10 — 핵심 차량 신호 확인 (속도 ✅)

- GitHub: 비공개 저장소 `playdrive-dev/PlayDrive`에 main을 push. 원격 커밋 작성자·커미터가 모두 playdrive-dev(익명 noreply)인 것을 확인함.
- 테스트 방법: `SignalProbes`(app/signal)로 Capability → 현재값 → 리스너를 확인. Virtual Console에 `PUT`으로 값을 바꾸고 logcat 시각으로 지연을 잼(heartbeat 필요, 위 Virtual Console 항목 참고).
- AAOS는 앱이 **user 10**으로 실행됨 → adb로 권한을 줄 때는 `pm grant --user 10 app.playdrive …`. user 0에만 주면 앱에서는 거부 상태로 보임.

| 신호 | 동작 | 실제 API · 권한(보호 수준) | 값 형식 · 주기 · 지연(콘솔→앱) |
|---|---|---|---|
| **차량 속도** | ✅ | `CarPropertyManager` `PERF_VEHICLE_SPEED`(0x11600207), `getProperty`/`registerCallback(cb, id, rate)` · `android.car.permission.CAR_SPEED` (**dangerous → 런타임 요청 필요**) | Float **m/s** (30km/h → 8.333). CONTINUOUS, 샘플링 10~20Hz(20Hz로 등록해도 실제로는 **약 10Hz**, 간격 중앙값 99ms·p95 202ms). 지연 76~270ms |
| 기어 | ✅ | `CarPropertyManager` `GEAR_SELECTION` · `android.car.permission.CAR_POWERTRAIN` (normal) | Integer, ON_CHANGE. **P=4, D=8** (AAOS VehicleGear 상수). 지연 약 45ms(D), 약 600ms(P) |
| 기어(SDK) | ✅ | `getGear().getCurrentGearSelectionState`, `registerGearSelection(GearSelectionListener.onSelected)` · `CAR_DRIVE`(normal) | `VehicleGear` enum: GEAR_PARK/GEAR_DRIVE/GEAR_REVERSE/GEAR_NEUTRAL… 지연 약 30ms. Capability 함수 없음 |
| 브레이크 페달 | ✅ | `getBrake().checkBrakePedalStatusCapabilities` / `getCurrentBrakePedalStatus` / `registerBrakePedalStatus(BrakePedalStatusListener.onBrakePedalStatusUpdated(Boolean?))` · `pleos.car.permission.CAR_DRIVE`(normal) | **Boolean(밟음/안 밟음)만** 제공, 밟은 깊이(%)는 없음. 콘솔 brake>0 → true. ON_CHANGE, 지연 90~170ms. 앱 시작 시 초기값 true(P단) |
| 와이퍼 | ✅ | `getWiper().checkWiperStateCapabilities` / `getCurrentWiperState(WindowArea(FRONT_WINDSHIELD))` / `registerWiperState(area, WiperStateListener.onWiperStateUpdated(WiperState?))` · `pleos.car.permission.CAR_WINDOWS`(normal) | `WiperState` OFF/ON/SERVICE/OTHER. 콘솔 Low → ON, Off → OFF. 단수(Low/High)는 `getCurrentWiperSwitch`로 조회만 되고 리스너 없음. 지연 약 40ms |
| 좌석 착좌 | ⚠️ 값 반대 | `getSeat().checkSeatOccupancyCapabilities` / `getCurrentSeatOccupancy(SeatArea(SeatValue.X))` / `registerSeatOccupancy(area, SeatOccupancyListener.onSeatOccupancyUpdated(Boolean?))` · `pleos.car.permission.CAR_SEATS`(normal) | 영역: 운전석=ROW_1_LEFT, 조수석=ROW_1_RIGHT, 뒷좌석=ROW_2_LEFT/RIGHT(/CENTER). **SDK Boolean이 실제와 반대**: 콘솔 occupied=true → SDK false. 원시 VHAL 값은 정상(OCCUPIED=2, VACANT=1) → **SDK 2.3.2의 변환 버그로 추정**. 지연 약 150ms |
| 조향각 | ✅ (부호 주의) | `getSteeringWheel().checkSteeringWheelAngleCapabilities` / `getCurrentSteeringWheelAngle` / `registerSteeringWheelAngle(SteeringWheelAngleListener.onSteeringWheelAngleUpdated(Float?))` · `pleos.car.permission.CAR_DRIVE`(normal) | Float 도(°), 범위 -470~500. **콘솔 +90 → SDK -90**(부호 반대). 값이 그대로여도 약 3초마다 같은 값이 다시 옴. 지연 약 80~170ms |
| 방향지시등 Flash | ✅ | (3단계와 같음) | 콘솔 FlashLeft → LEFT(2), FlashRight → RIGHT(1). Blink와 구분되지 않음 |

### 시사점(4~6단계 반영)
- **속도가 앱까지 들어오므로 시연 계획은 그대로 간다.** 가감속 판단은 m/s 값을 시간으로 미분해서 계산(10Hz 샘플 → 이동평균 필요). 0.3g ≈ 2.94 m/s².
- 콘솔에서 `controls?speed=`로 값을 바꾸면 속도가 **한 번에 점프**함(30→60km/h가 0.1초 안에) → 그대로 쓰면 "급가속"으로 판정됨. 부드러운 가감속을 시연하려면 시뮬레이션 패널이나 스크립트로 속도를 **조금씩 올리고 내리는 램프**를 보내야 함. 콘솔 화면의 페달 UI가 물리 시뮬레이션을 하는지는 6단계에서 확인.
- 브레이크는 Boolean뿐 → "부드러운/급한 제동"은 브레이크 on 상태에서의 감속도(속도 미분)로 판단.
- 좌석 착좌: 우리 신호 어댑터에서 값을 **뒤집는 보정 플래그**(`invertSeatOccupancy`, 기본 true)를 두고, 실차나 새 SDK에서 다시 확인. AAOS `SEAT_OCCUPANCY`를 직접 읽는 길은 `CONTROL_CAR_SEATS`(signature|privileged) 권한이라 불가. Pleos에 버그로 보고할 후보.
- 조향: 크기와 변화율만 쓰면 부호와 무관. 좌/우가 필요하면 부호 규약을 실차에서 확인.
- 주행 중 CPU 측정 시 속도 콜백(10Hz)을 메인 스레드에서 처리하지 말 것.

## 2026-11-10 — 4단계: DrivingEventEngine ✅ (단위 테스트 14/14 통과)

- 위치: `app/src/main/java/app/playdrive/model/Signals.kt`(신호 모델과 `VehicleSignalSource` 인터페이스), `engine/`(순수 Kotlin, 안드로이드 의존 없음).
- 입력 `SignalSample`: Speed(m/s), GearChanged, Brake(밟음 여부), TurnSignalChanged, Wiper(on/off), SeatOccupancy(보정 후), SteeringAngle(부호 보정 후, 양수=왼쪽).
- 출력 `DrivingEvent`: Departed, Stopped, CruiseStarted/Ended, SmoothAcceleration/Deceleration, HarshAcceleration, HarshBraking(브레이크 밟음 여부 포함), TurnSignalOn/Off, LaneChange, HarshSteering, RainStarted/Stopped, GearChanged, Parked, OccupancyChanged(운전자 탑승, 혼자 탑승). 상태 스냅샷은 `DrivingState`(harmony 포함).
- 판단 방식:
  - 가속도 = 300ms 이동평균 속도를 500ms 간격으로 미분. 한 샘플짜리 튐(±1m/s)은 걸러짐(테스트로 확인).
  - 급가속·급제동: ±2.94 m/s²(0.3g), 같은 종류는 2초 쿨다운.
  - 부드러운 가감속: 0.5~2.94 m/s²로 같은 방향이 1초 이상 이어지면(직전 1초 안에 급 이벤트가 없을 때).
  - 정속: 2 m/s 이상이고 |a| ≤ 0.3이 5초 이상 → 시작. |a| > 0.6이거나 정차하면 → 종료.
  - 정차: 0.3 m/s 미만이 1초 이상. 출발: 1.0 m/s 이상.
  - 차선 변경: 방향지시등 켬→끔이 10초 안이고, 끌 때 5 m/s 이상으로 주행 중일 때.
  - 급조향: 조향 변화율 150°/s 이상이고 5 m/s 이상일 때. 조향각은 바뀔 때만 오므로 변화율 계산의 dt를 100~500ms로 제한.
  - 혼자 탑승: 운전석 착좌 && 다른 좌석이 모두 비어 있음.
  - 하모니(0~100, 시작값 50): 주행 중 급 이벤트 후 5초가 지나면 초당 +0.8(정속 중에는 +1.5). 급가속 -10, 급제동 -15, 급조향 -8.
- 임계값은 모두 `engine/DrivingThresholds.kt` 한 곳에 모음(data class 기본값 → 테스트에서 덮어쓰기 가능).
- 테스트 실행: `./gradlew :app:testDebugUnitTest`

## 2026-11-10 — 5단계: AdaptiveMusicEngine ✅ (단위 테스트 10개 추가, 전체 통과)

### 구조
- 순수 Kotlin(`music/`): `MusicTiming`(48kHz, 90 BPM, 4/4, 4마디 루프 = 512,000프레임 ≈ 10.67초), `Layer`, `LayerSource`(인터페이스), `Composition`, `synth/`(합성), `LoopMixer`(믹서·효과), `MusicDirector`(운전 → 음악 명령), `LayerCache`.
- Android(`audio/`): `AudioOutput`(AudioTrack Float 48kHz 스테레오, 20ms 블록, URGENT_AUDIO 스레드), `PlaybackSession`(오디오 포커스 + MediaSession). 연결은 `DriveController`(엔진 전용 HandlerThread).
- 실제 신호원 `signal/PleosVehicleSignalSource`: 속도·기어는 CarPropertyManager, 나머지는 Pleos SDK. **착좌 반전·조향 부호를 보정**(`Corrections`). 실패한 신호는 그 신호만 빠짐. 3단계 확인용 프로브 코드는 삭제.

### 소리 설계(불협화음 방지)
- A 단조, 마디당 코드 Am–F–C–G. 베이스·패드·멜로디는 **각 마디 코드의 구성음만** 사용 → 어떤 조합으로 켜도 어울림(테스트로 검증). 드럼·하이햇·빗소리는 음높이가 없음.
- 레이어 7개: DRUMS, BASS, PAD, MELODY, HIHAT(기본 5개) + HIHAT_FILL(방향지시등, 박 단위 퀀타이즈), RAIN(빗소리).
- 켜고 끄기: 다음 마디 경계(필인은 다음 박)에서 페이드 인 60ms / 아웃 150ms.
- 효과:
  - 급제동 = 테이프 스톱(0.6초 동안 재생 속도·피치가 0까지 떨어지고, 0.15초 이상 쉰 뒤 다음 마디 머리에서 복귀)
  - 급가속 = 로우패스 딥(60ms 안에 380Hz까지 내려가 1박 유지, 2마디에 걸쳐 회복)
  - 급조향 = 멜로디 피치 흔들림(1마디, 6Hz, 약 ±1.5반음)
- 출력: 소프트 리미터로 |x| ≤ 1(레이어를 모두 켜도 클리핑 없음, 테스트로 확인).
- 연출(`MusicDirector`): 출발 전 = 패드 → 출발 = 드럼·베이스·패드 → 급 이벤트 없이 2마디마다 하이햇 → 멜로디 추가. 급제동·급가속이면 레벨 −2, 급조향이면 −1. 신호 대기 = 브레이크다운(드럼·하이햇 빠짐). 주차 = 패드만.

### 실제 스템(OGG)으로 바꾸는 방법
- `LayerSource`를 구현해 `load(timing)`에서 OGG를 MediaExtractor/MediaCodec으로 디코딩한다(모노 Float, 길이 = loopFrames, 48kHz). 그다음 `DriveController.prepare()`의 `SynthLayerSource.all()`을 교체하고, `SYNTH_VERSION`을 올려 캐시를 무효화한다.
- 스템 제작 규격: 90 BPM, A 단조, Am–F–C–G 4마디, 레이어별 단일 파일, 앞뒤 무음 없이 정확히 4마디(10.667초).

### 에뮬레이터 측정
- 레이어 합성: 처음엔 순차 합성으로 **9.8초**(실행 10초 기준에 아슬아슬함) → 4스레드 병렬 + 캐시 파일(`cacheDir/layers-v1`, 레이어당 2MB, 총 14MB)로 **첫 실행 3.5초, 이후 0.12초**. 첫 실행에는 화면에 "음악 준비 중…"을 표시.
- 오디오 경로: 오디오 포커스 GAIN 받음 → 트랙이 미디어 버스(`AUDIO_DEVICE_OUT_BUS`)로 활성, Float 48kHz, 게인 0dB. 차량 미디어 볼륨 그룹 20/45, 음소거 아님. 트랙 언더런 0.
- **에뮬레이터 소리 확인 방법**:
  1. 에뮬레이터를 `-no-audio` 없이 실행하면(기본값) 게스트 오디오가 Mac 기본 출력 장치로 나감.
  2. 앱 쪽 확인: `adb shell dumpsys audio`(포커스 보유자 = app.playdrive), `adb shell dumpsys media.audio_flinger`(AudioOut 스레드의 Tracks에 Active=yes, 미디어 버스 Standby: no)
  3. Mac 쪽 확인: `pmset -g assertions`에서 coreaudiod의 `audio-out BuiltInSpeakerDevice`가 활성이면 qemu가 출력 중
  4. 차량 볼륨: `adb shell dumpsys car_service | grep "Gain indexes"`. 에뮬레이터 화면의 볼륨 슬라이더로도 조절 가능.
  5. 들리지 않으면 Mac 시스템 출력의 음소거·볼륨부터 확인할 것(이번 확인 때 Mac 출력이 음소거 상태였음).
- 디버그 자동화(디버그 빌드 전용): `adb shell am start -n app.playdrive/.MainActivity --es debug demo|real|stop|layers:PAD,BASS,...|fx:TapeStop|LowpassDip|PitchWobble`

## 2026-11-10 — 시연 준비: 데모 드라이브 ✅

- `sim/DemoDriveScenario`(순수 Kotlin 타임라인) + `sim/DemoDriveSource`(10Hz 재생, `VehicleSignalSource` 구현 → 실제 신호원과 교체 가능).
- 시나리오(60초): P단·혼자 탑승 → 3s D단 → 5~15s 0→60km/h 부드러운 가속 → 15~30s 정속 → 21~25s 왼쪽 방향지시등 + 완만한 조향(차선 변경) → 30s 급제동 60→35km/h(약 4.6 m/s²) → 32.5~38.5s 부드러운 재가속 → 35s 비(와이퍼) → 46~52s 부드러운 감속, 정차 → 55s 비 그침 → 56s P단.
- 통합 테스트 `DemoDriveScenarioTest`(2개): 시나리오 → 엔진 → 연출을 거쳐 이벤트 순서와 시점별 레이어를 검증. **전체 단위 테스트 26/26 통과.**
- 에뮬레이터 실행 결과(`--es debug demo`), 시각은 데모 시작 기준:

| 시각 | 운전 이벤트 | 음악 |
|---|---|---|
| 0s | Parked, 혼자 탑승 | 인트로: PAD |
| 5.8s | Departed, SmoothAcceleration | + DRUMS, BASS |
| 11.1s / 16.4s | (급 이벤트 없이 2마디씩) | + HIHAT / + MELODY |
| 20.7s | CruiseStarted | — |
| 21.0s | TurnSignalOn | + HIHAT_FILL(다음 박) |
| 25.0s | LaneChange | − HIHAT_FILL |
| 30.5s | HarshBraking | **TapeStop**, − MELODY, − HIHAT |
| 34.1~35.0s | SmoothAcceleration, RainStarted | + RAIN |
| 35.8s / 41.3s | (회복) | + HIHAT / + MELODY |
| 47.3s / 53.0s | SmoothDeceleration / Stopped | 브레이크다운: − DRUMS, − HIHAT |
| 55.1s / 56.0s | RainStopped / Parked | − RAIN / 아웃트로: PAD만 |

- 반응: 신호 처리 → 음악 명령까지 같은 ms 안(로그 기준). 실제 소리 변화는 설계대로 다음 마디(최대 2.67초)나 다음 박(최대 0.67초)에서. 테이프 스톱은 다음 20ms 렌더 블록부터 바로 시작.
- 부하(소프트웨어 GPU 에뮬레이터 기준): 오디오 렌더링 = 실시간 대비 **2.5~12%**(한 코어 기준). 앱 프로세스 CPU(top, 5초 간격) 1~24% — 디버그 화면을 250ms마다 갱신하고 로그를 남기는 비용이 포함됨. 실차 기준 측정과 주행 화면(텍스트 갱신 없음)에서 다시 잴 것.
- AudioTrack underrunCount: 시작 직후 3~6회, 이후 늘지 않음.

## 2026-11-10 — 음원 연동: Suno 스템 → OGG → 앱

### 1. 분석(`tools/analyze_stems.py`, numpy + ffmpeg, librosa 없이)
- 방법: 스펙트럼 플럭스 onset → 80~100 BPM을 0.002 간격으로 펄스열 맞춤 → 박마다 실제 onset 피크로 선형회귀(박 잔차로 신뢰도 판단). 다운비트는 드럼이면 저역 킥, 음높이 스템이면 고해상도 크로마(FFT 16384)를 Am7–Fmaj7–Cmaj7–G 템플릿에 맞춰 찾음.
- 함정: 처음엔 크로마를 FFT 2048로 계산해 저음이 반음 위로 잡힘(110Hz → A#) → "F# 장조"로 잘못 나옴. 16384로 바꿔 해결.
- 결론: **길이 20.76초는 템포 차이가 아니라 7.8마디에서 잘린 것**(타악기 스템이 90.0±0.1 BPM, 박 잔차 3~7ms). drums_A만 실제로 87.94 BPM → 시간 늘이기 적용. pad_rain, melody_B는 onset이 약해 템포 추정을 믿기 어려움 → 프롬프트대로 90으로 간주.

| 원본 | 길이(s) | 추정 BPM | 박 잔차(ms)/사용 박 | 신뢰 | 첫 박(s) | 첫 다운비트(s) | 코드정렬 마디(s) | 처리 | 자른 시작(s) | LUFS→게인 |
|---|---|---|---|---|---|---|---|---|---|---|
| bass_A | 20.76 | 90.11 | 14.4/16 | O | 0.152 | 2.149 | 10.139 | atempo 0.99878 | 7.485 | -17.6→-2.4 dB |
| bass_B | 20.76 | 90.09 | 18.7/19 | △ | 0.064 | 1.396 | 10.054 | 90 그대로 | 10.054 | -16.9→-3.1 dB |
| chords_A | 20.76 | 90.13 | -/7 | △ | 0.165 | 0.165 | 9.486 | 90 그대로 | 9.486 | -18.0→-2.0 dB |
| chords_B | 20.76 | 89.98 | 10.4/11 | O | 0.296 | 2.297 | 6.298 | 90 그대로 | 6.298 | -18.1→-1.9 dB |
| drums_A | 19.92 | 87.94 | 12.4/28 | O | 0.351 | 0.351 | - | atempo 1.02343 | 0.343 | -17.4→-2.6 dB |
| drums_B | 10.96 | 89.96 | 5.5/16 | O | 0.3 | 0.3 | - | 90 그대로 | 0.293 | -16.6→-3.4 dB |
| hat_A | 20.76 | 89.97 | 3.4/29 | O | 0.654 | 1.988 | - | 90 그대로 | 1.988 | -16.6→-3.4 dB |
| hat_B | 20.76 | 89.91 | 7.4/26 | O | 0.572 | 1.907 | - | atempo 1.001 | 1.905 | -16.3→-3.7 dB |
| melody_A | 20.76 | 90.02 | 16.0/17 | △ | 0.048 | 0.048 | 10.046 | 90 그대로 | 10.046 | -18.3→-1.7 dB |
| melody_B | 10.72 | 84.44 | -/5 | △ | 0.008 | 1.429 | 0.008 | 90 그대로 | 0.008 | -16.9→-3.1 dB |
| pad_rain_A | 20.76 | 92.46 | -/2 | △ | 0.592 | 0.592 | 10.326 | 90 그대로 | 7.659 | -17.2→-2.8 dB |
| pad_rain_B | 20.76 | 81.01 | -/2 | △ | 0.084 | 2.306 | 8.972 | 90 그대로 | 8.972 | -16.3→-3.7 dB |

(신뢰: O = 박 잔차 < 15ms이고 8박 이상 → 90에서 0.05 넘게 벗어나면 atempo. △ = 추정 불안정 → 90으로 간주. 시작점이 원본 끝에 너무 가까우면 한 마디씩 앞당김)

### 2. 가공(`tools/process_stems.py`)
- 시작점부터 정확히 4마디(512,000샘플). 필요하면 ffmpeg `atempo`로 템포만 90에 맞춤(피치 유지).
- 이음매 8ms 크로스페이드: 루프 뒤에 이어지는 원본 8ms를 루프 머리에 섞음(원본에 여유가 없으면 앞쪽 프리롤을 꼬리에 섞음).
- 음량: 루프 −20 LUFS / 원샷 −18 LUFS 목표, 샘플 피크 −1 dBFS 이하.
- 인코딩: `oggenc -q 5`, 48kHz 스테레오(vorbis-tools 1.4.3, libvorbis). ffmpeg 내장 vorbis 인코더는 q5를 줘도 약 51kbps라 쓰지 않음.
- **루프 OGG 앞뒤에 4096샘플 순환 패딩**을 붙여 인코딩하고, 앱(`OggLayerSource`)이 디코딩 후 잘라냄. Vorbis가 파일 경계(=다운비트)에 만드는 왜곡을 피하기 위함 → 이 처리로 chords_b, melody_a, pad_rain_a의 이음매 문제가 해결됨.
- 결과: `app/src/main/res/raw/lofi_*.ogg` 16개, 총 2.9MB.

### 3. 끊김 검사(출하될 OGG를 다시 디코딩 → 3번 이어 붙인 `build/preview/*_x3.wav`, git 제외)
- 지표: jump(이음매 ±2샘플 1차 차분 / 주변 99.9퍼센타일, >1.5면 불연속), vs 다른 마디(이음매 2ms 고역 에너지 − 루프 안 2~4마디 시작점 중앙값), vs 원본(이음매 고역 에너지 − 원본 곡의 같은 지점). 판정: jump ≤ 1.5 그리고 vs 원본 ≤ +6dB(원본 비교가 불가능하면 vs 다른 마디 ≤ +6dB).

| 출력 OGG | 길이(샘플) | 가공 후 BPM | 이음매 jump | vs 다른 마디(dB) | vs 원본 같은 지점(dB) | 판정 | 크기 |
|---|---|---|---|---|---|---|---|
| lofi_bass_a | 512000 | 90.0 | 0.17 | +1.4 | -1.7 | ✅ OK | 181KB |
| lofi_bass_b | 512000 | 90.03 | 0.1 | +6.1 | - | ⚠️ 청취 확인 | 189KB |
| lofi_chords_a | 512000 | 90.03 | 0.09 | +7.7 | -0.9 | ✅ OK | 169KB |
| lofi_chords_b | 512000 | 90.0 | 0.2 | -3.9 | -1.4 | ✅ OK | 171KB |
| lofi_drums_a | 512000 | 89.99 | 0.03 | -2.8 | -1.3 | ✅ OK | 135KB |
| lofi_drums_b | 512000 | 90.0 | 0.09 | +9.8 | +14.1 | ⚠️ 청취 확인 | 138KB |
| lofi_hat_a | 512000 | 90.0 | 0.01 | -1.0 | -2.0 | ✅ OK | 170KB |
| lofi_hat_b | 512000 | 89.99 | 0.35 | +13.7 | +11.9 | ⚠️ 청취 확인 | 165KB |
| lofi_melody_a | 512000 | 90.02 | 0.38 | -3.2 | -1.2 | ✅ OK | 177KB |
| lofi_melody_b | 512000 | 90.0 | 0.18 | -0.1 | -0.9 | ✅ OK | 172KB |
| lofi_pad_rain_a | 512000 | 90.0 | 0.39 | -2.1 | -1.3 | ✅ OK | 188KB |
| lofi_pad_rain_b | 512000 | 89.96 | 0.17 | +3.2 | -2.7 | ✅ OK | 189KB |


- **기본값(A 세트) 6개 모두 통과.** 샘플 불연속(jump)은 16개 모두 없음(≤ 0.38).
- ⚠️ drums_b: 원본이 4.1마디뿐이라 루프 끝이 파일 끝이고, 마지막 20ms에 다음 타격의 시작이 잘려 들어가 있음(+14dB).
- ⚠️ hat_b: 이음매에만 1~2ms 고역 스파이크(+12dB).
- ⚠️ bass_b: 원본 비교 불가(뒤 여유 없음), 다른 마디 대비 +6.1dB로 경계선.
- 세 개 모두 B 버전 → B로 바꾸기 전에 `build/preview/<name>_x3.wav`를 들어볼 것.

|---|---|---|---|---|---|
| lofi_sfx_scratch_a | 0.0 | 0.0 | 5.96 | -0.1 dB | 102KB |
| lofi_sfx_scratch_b | 0.0 | 0.687 | 13.31 | -0.5 dB | 217KB |
| lofi_sfx_tapestop_a | 0.0 | 0.0 | 12.28 | -0.1 dB | 186KB |
| lofi_sfx_tapestop_b | 0.0 | 0.0 | 7.6 | -1.1 dB | 106KB |

### 4. 앱 연동
- `music/stems/StemConfig.kt`: 음원 `SOURCE = STEMS | SYNTH`(기존 신스는 그대로 두고 전환 가능), 스템별 A/B(기본 전부 A), 효과음 최대 재생 2.5초, `LOOP_PAD_FRAMES = 4096`.
- `music/stems/OggStems.kt`: `OggLayerSource`/`OggCueSource`(MediaExtractor + MediaCodec → Float 스테레오), 패딩을 잘라 정확히 512,000프레임만 사용.
- `LayerSource`가 `StereoPcm`을 반환하도록 바꿈(신스는 모노, 스템은 스테레오 그대로). 캐시 `cacheDir/pcm-v2/<cacheKey>.f32`(키 = 리소스 이름 + 파일 크기 → 스템을 바꾸면 자동 갱신).
- `LoopMixer`: 모든 레이어를 같은 격자 위치(readPos)에서 동시에 재생하고 볼륨만 페이드 → 박자가 어긋날 수 없음. 켜고 끄기는 마디 경계에서 **0.5마디 페이드**. `PlayCue`: 효과음 원샷을 즉시 재생(최대 2.5초, 끝 400ms 페이드) + 음악을 0.3으로 덕킹(80ms) → 효과음이 끝난 뒤 첫 마디 경계에서 0.5마디에 걸쳐 복귀. 효과음이 없으면(신스 모드) DSP 테이프 스톱·피치 흔들림으로 대체.
- `MusicDirector`(요구 매핑):
  - 0 정차·출발 = pad_rain
  - 1 부드러운 가속 = + drums + hat
  - 2 안정 주행(정속) = + bass + chords
  - 3 오래 부드러운 주행(단계2에서 4마디 동안 급 이벤트 없음) = + melody
  - 급제동·급가속 → sfx_tapestop, 급조향(급차선변경) → sfx_scratch. 직후엔 pad만 남기고 **다음 마디부터 한 마디에 하나씩** pad → drums → hat → bass → chords → melody 순으로 복구.
  - 신호 대기 = drums·hat 빠짐(브레이크다운), 주차 = pad만. 방향지시등은 스템 모드에서 hat 합류(신스 모드는 필인).
- 레이어 enum에 CHORDS 추가(신스에도 로즈풍 코드 스탭 추가). 단위 테스트 **31/31 통과**(믹서: 스테레오·효과음 덕킹·DSP 대체, 연출: 요구 매핑·한 마디씩 복구·브레이크다운).

### 5. 에뮬레이터 확인(데모 드라이브, 스템 A 세트)
- 음원 로드: 첫 실행 2.26초(OGG 8개 디코딩), 이후 0.21초(캐시).
- 0s pad → 6.3s 부드러운 가속에 drums·hat → 17.0s bass·chords → 27.7s melody → 30.5s 급제동: tapestop 효과음 + pad만 남김 → 33.2/35.8/38.5/41.2/43.9s에 한 마디씩 복구 → 53.0s 정차 브레이크다운 → 56.0s 주차 pad. 오디오 렌더 부하 2.8~6.8%, AudioTrack underrun 0, 오류 로그 0.
- 출력 경로: 미디어 버스 Active, Mac coreaudiod 내장 스피커 출력 중. **실제 청취(음색·균형·화성)는 사람이 확인해야 함.**
- debug·release(서명 전) APK 빌드 성공(release 11.1MB).

### 남은 문제
1. **스템끼리 코드 진행이 다름(가장 큼).** 박 단위 근음 분석 결과 bass_A ≈ F–(Am/G)–C–C, bass_B ≈ F–F–C–G 반복이고, chords·melody·pad도 서로 다른 순서. 모두 A단조/C장조 음계 안이라 심한 불협은 아니지만 "같은 코드 진행" 원칙에 어긋남. Suno가 스템을 따로 생성하며 진행을 지키지 않은 것으로 보임. 해결 제안: **Suno에서 90 BPM 곡 하나를 만든 뒤 Stems(분리) 기능으로 악기별로 뽑기** → 템포·진행·마디가 원래부터 일치. 또는 bass를 기준으로 진행이 맞는 스템만 고르기.
2. 다운비트·시작점은 자동 추정이라 스템 간 상대 정렬이 한두 박 어긋날 수 있음(특히 pad·chords). 청취 후 `process_stems.py`에 스템별 수동 시작점(오버라이드)을 넣는 기능이 필요할 수 있음.
3. 원샷이 6~14초로 길어서 앞 2.5초만 씀. 테이프 스톱의 "멈추는" 부분이 뒤쪽에 있을 수 있으니 들어보고 시작 오프셋을 정해야 함.
4. B 버전 3개(drums_b, hat_b, bass_b)는 이음매 청취 확인 필요.
5. 비(와이퍼)는 스템 모드에서 별도 레이어가 없음(pad_rain에 빗소리 포함). 방향지시등 필인용 스템도 없음 → 현재는 hat 합류로 대체.

## 2026-11-10 — 음원 v2: 한 곡에서 분리한 스템(SONG 세트)

### 1. 곡 분석(`tools/analyze_song.py`) — 격자는 곡 전체에서 한 번만
- 원본: `assets-src/suno_song_stems/` 7개(149.72초, 48kHz 스테레오). 0 Lead Vocals는 쓰지 않음.
- 템포: 드럼+베이스 onset 박 210개 회귀 → **90.0057 BPM**(박 잔차 3.1ms). 149초 동안 누적 오차 약 9ms → 90으로 간주하고 늘이기 없음. 첫 다운비트(킥) **2.0369초**, 전체 55마디.
- 코드: 마디 단위로는 **Am7 / Fmaj7가 번갈아** 나오고, 반 마디로 보면 키보드가 G → Am(add9/Cmaj7) → Am → Fmaj7 …로 **2박마다 바뀌는 2마디 주기**. 프롬프트의 "4마디 Am7–Fmaj7–Cmaj7–G"를 그대로 따르지는 않음 → "그 진행이 정확히 2번" 조건은 이 곡에 없음. 대신 4마디 주기 크로마 유사도가 0.97~0.98이라, 8마디(2마디 주기 × 4)로 자르면 화성상 자연스럽게 반복됨.
- 스템별 활동: synth는 곡 앞부분(0~30마디)에서 사실상 무음이고 31마디(약 84.7초)부터 들어옴. perc는 곡 전체에서 −50~−65dB로 매우 작음. 38마디(103.4초)에 드럼 필인(패턴 유사도 0.48), 53~54마디는 아웃트로.

| 후보 | 구간(초) | 마디 | 코드(마디 라벨) | 4마디 주기 유사도 | 드럼 패턴 유사도 최저/평균 | synth 평균/최저(dB) | perc 평균(dB) | 주요 스템 최저(dB) | 비고 |
|---|---|---|---|---|---|---|---|---|---|
| **A (선택)** | **119.363 – 140.695** | 44–51 | Am7 F Am7 F ×2 | 0.969 | 0.882 / 0.964 | **−39.6** / −66.9 | −52.9 | −27.0 | 전 스템 연주, 필인 없음, synth가 가장 큼 |
| B | 108.70 – 130.03 | 40–47 | Am7 F Am7 F ×2 | 0.935 | 0.882 / 0.963 | −47.0 / −67.8 | −52.2 | −26.7 | A와 비슷, synth가 더 작음 |
| C | 44.70 – 66.03 | 16–23 | Am7 F Am7 F ×2 | 0.980 | 0.891 / 0.952 | −78.5 / −108.8 | −65.4 | −29.8 | 반복성은 가장 좋지만 synth가 없음 → 제외 |

- **선택: 44마디 시작, 시작 샘플 5,729,413(119.3628초) ~ +1,024,000샘플(8마디 = 21.333초).** 모든 스템을 이 같은 샘플 범위로 자름(스템별로 위치를 따로 추정하지 않음).

### 2. 가공(`tools/process_stems.py --song assets-src/suno_song_stems --start-bar 44`)
- 같은 범위로 자르기 → 루프 머리에 원곡 연속분 8ms 크로스페이드 → LUFS 맞추기(피크 −1 dBFS 이하) → 4096샘플 순환 패딩 → `oggenc -q 5` → `res/raw/song_{drums,bass,keys,guitar,synth,perc}.ogg`(총 2.2MB).
- synth·perc는 원본이 작아서 목표를 낮춤(synth −22, perc −24 LUFS). perc는 순간 피크 때문에 +16.6dB에서 막혀 약 −32 LUFS → 앱 레벨로 보완.

| 출력 | 원본 | 원본 LUFS | 목표 | 게인 | 바닥잡음 전→후(dBFS) | jump | vs 다른 마디 | vs 원본 | 판정 | 가공 후 BPM | 크기 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| song_drums.ogg | 1 Drums.wav | -17.3 | -20 | -2.7 | -73.0→-75.7 | 0.01 | +1.6 | +5.3 | ✅ | 90.0 | 406KB |
| song_bass.ogg | 2 Bass.wav | -17.4 | -20 | -2.6 | -42.5→-45.1 | 0.04 | -0.5 | -3.4 | ✅ | 90.0 | 328KB |
| song_guitar.ogg | 3 Guitar.wav | -23.6 | -20 | +3.6 | -40.0→-36.4 | 0.17 | -0.1 | -1.9 | ✅ | 90.0 | 364KB |
| song_keys.ogg | 4 Keyboard.wav | -22.3 | -20 | +2.3 | -32.2→-29.9 | 0.34 | +3.5 | -1.4 | ✅ | 90.0 | 348KB |
| song_synth.ogg | 6 Synth.wav | -32.4 | -22 | +10.4 | -80.7→-70.3 | 0.09 | +2.9 | -1.6 | ✅ | 90.0 | 361KB |
| song_perc.ogg | 5 Percussion.wav | -48.4 | -24 | +16.6 (피크 제한) | -120.8→-104.3 | 0.0 | -0.1 | +0.0 | ✅ | 90.0 | 379KB |

믹스다운(앱 기본 레벨, master 0.8, 6개 모두 켬): 피크 -2.4 dBFS, -16.7 LUFS → 리미터가 개입하지 않음. 미리듣기 `build/preview/song_mix_x3.wav`

- 잡음: synth +10.4dB 후 바닥잡음 −70dBFS, perc +16.6dB 후 −104dBFS(쉬는 구간이 거의 디지털 무음) → **증폭으로 인한 잡음 문제 없음**. (keys·guitar·bass의 "바닥" 값이 높은 건 이 구간에서 쉬지 않고 연주하기 때문이지 잡음이 아님.)
- **이음매 검사: 6개 모두 통과**(샘플 불연속 없음, 원곡의 같은 지점 대비 +5.3dB 이하). 미리듣기 `build/preview/song_*_x3.wav`(git 제외).

### 3. 레이어 매핑(SONG 세트, `Arrangement.SONG`)
| 단계 | 조건 | 레이어(스템) |
|---|---|---|
| 0 정차·출발 | 출발 전, 주차 | CHORDS(keys) + PAD(synth) |
| 1 부드러운 가속 | SmoothAcceleration 또는 출발 후 2마디 | + DRUMS(drums) + HIHAT(perc) |
| 2 안정 주행 | CruiseStarted 또는 4마디 유지 | + BASS(bass) |
| 3 오래 부드러운 주행 | 단계2에서 4마디 동안 급 이벤트 없음 | + MELODY(guitar) |
| 비(와이퍼 ON) | — | 전체에 로우패스(18k → 1.8kHz) + 리버브(젖은 비율 0.18), 1마디에 걸쳐 걸고 풂 |
| 급제동·급가속 / 급조향 | — | 테이프스톱 / 스크래치 효과음 + 덕킹 → keys+synth만 남기고 한 마디에 하나씩 복구 |
| 신호 대기 / 주차 | — | drums·perc 빠짐 / keys+synth |

- 레이어별 음량(`StemConfig.SONG_LEVELS`, 정규화된 스템 기준): drums 0.80, bass 0.80, keys 0.70, guitar 0.60, synth 0.55, perc 0.50. 마스터 0.8.
- `StemConfig.SET = SONG(기본) | LOOPS | SYNTH`. 세트별로 마디 수(SONG 8 / 나머지 4), 편곡, 레벨이 자동으로 바뀜. 기존 `lofi_*.ogg`는 그대로 둠.

### 4. 테이프스톱 효과음(남은 문제 3번) — `tools/find_tapestop.py` + 피크·f0 추적
- 시도 1: 로그 주파수 스펙트럼의 프레임 간 이동량(50ms, 250ms 간격 교차상관) → 모든 지점에서 0반음. 정적인 성분이 지배적이어서 실패.
- 시도 2: 0.1초 간격 스펙트럼 피크 + 40ms 간격 자기상관 f0 추적:
  - A(12.3초): 52Hz 지속 → 4.94초 타격 → **5.03~5.06초에 갑자기 무음** → 5.26초 재시작. 끝부분 9.3~12초는 피크(87/134/175Hz)가 그대로인 채 음량만 줄어드는 페이드.
  - B(7.6초): 55Hz가 유지된 채 4.4~5.3초 동안 음량만 −16 → −31dB(페이드).
- **결론: 두 파일 모두 피치가 내려가는 진짜 '테이프 스톱' 구간이 없음.** 가장 '멈추는' 느낌이 나는 곳을 쓰도록 자름: A = 4.00~5.06초(1.06초, 타격 후 뚝 끊김, 끝 10ms 페이드), B = 3.90~5.32초(1.42초, 서서히 사라짐). `process_stems.py`의 `SFX_RANGES`.
- 대안: `StemConfig.TAPESTOP_MODE = DSP`로 바꾸면 효과음 대신 **음악 자체가 0.6초 동안 피치·속도가 떨어지며 멈췄다가 다음 마디에 복귀**(진짜 피치 하강). 기본값은 요청대로 SFX.

### 5. 확인
- 단위 테스트 **34/34 통과**(SONG 편곡, 비 효과 로우패스·리버브 꼬리, SONG 데모 시나리오 추가).
- 에뮬레이터(SONG 세트): 음원 로드 첫 실행 3.4초 / 이후 0.14초, Java 힙 약 59MB(8마디 스테레오 Float 6개 ≈ 49MB). 데모: 0s keys+synth → 6.2s drums+perc → 16.8s bass → 27.6s guitar → 30.4s 테이프스톱, keys+synth만 → 33.1/35.8/38.5/41.1s 한 마디씩 복구, 34.9s 비 효과 ON → 52.9s 정차 브레이크다운 → 55.0s 비 효과 OFF → 56.0s 주차. 렌더 부하 3~7%(순간 최대 14.9%), underrun 0, 오류 0.
- debug·release(서명 전) 빌드 성공. release 13.1MB.

### 남은 문제
1. 곡이 프롬프트의 4마디 진행(Am7–Fmaj7–Cmaj7–G)이 아니라 2마디 주기(G–Am–Am–Fmaj7 계열)다. 모든 스템이 같은 곡이라 서로 부딪히지는 않으므로 앱 동작에는 문제가 없음. 지원서 문구("Am7–Fmaj7–Cmaj7–G")는 실제 곡에 맞게 고칠 것.
2. 테이프스톱 원샷에 피치 하강이 없음 → 청취 후 SFX(잘라 둔 구간)와 DSP 중에 고를 것.
3. perc는 증폭해도 −32 LUFS 수준이라 작게 들릴 수 있음(레벨 0.50). 청취 후 `SONG_LEVELS`로 조정.
4. synth는 선택 구간 안에서도 마디마다 들쭉날쭉함(−31 ~ −67dB). 정차 시 keys+synth 인트로에서 synth가 거의 안 들리는 마디가 있을 수 있음.
5. 8마디 스테레오 PCM 캐시가 cacheDir에 약 57MB(기기 저장공간). 필요하면 Float → 16bit로 줄일 수 있음.

## 2026-11-10 — 반응형 연주 레이어(PerformanceLayer)

### 구조
- `music/PerformanceLayer.kt`(순수 Kotlin): `Chord`, `ChordTimeline`, `PerformanceLayer`. 믹서가 프레임마다 `process(gridFrame)`를 불러 **스템과 같은 격자 시계**로 스케줄링. 배경 스템 레이어는 그대로.
- 코드 타임라인(SONG, 선택 구간 44~51마디):

| 루프 마디 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|
| 코드(4박 내내) | Am7 | Fmaj7 | Am7 | Fmaj7 | Am7 | Fmaj7 | Am7 | Fmaj7 |
| 구성음 | A C E G | F A C E | A C E G | F A C E | A C E G | F A C E | A C E G | F A C E |

  LOOPS 세트는 스템마다 진행이 달라 Am7 고정(구성음이 모두 펜타토닉), SYNTH는 Am–F–C–G. 허용 음 = 현재 코드 구성음 ∪ A 마이너 펜타토닉(A C D E G). 아르페지오는 구성음만 씀.
- 음색: 2-오퍼레이터 FM 로즈(모듈레이터 1:1, 인덱스 1.6→0.25 감소 + 14배 '틴' 성분, 감쇠 0.75초, 1.6초), 셰이커 = 2차 차분 노이즈 버스트(90ms, 박 머리 강세). 쓸 음은 시작할 때 미리 합성(오디오 스레드에서 합성하지 않음). 연주 음량 `StemConfig.PERF_LEVEL = 0.45`(배경 레이어 0.5~0.8보다 작게).
- **양자화**: 모든 연주음은 다음 8분음표 격자에서 시작(지금이 격자면 지금). 최대 지연 = 8분음표 1개(½박 = 333ms). 즉시 재생 없음. (요구의 "최대 지연 1/8박"은 8분음표 1개로 해석.)
- 운전 중 화면에는 아무것도 추가하지 않음(디버그 화면의 문구만 74초로 바뀜).

### 매핑(`MusicDirector` → `MusicCommand`)
| # | 운전 | 명령 | 소리 |
|---|---|---|---|
| ① | 속도(1km/h 이상 바뀔 때) | `SpeedTone(kmh)` | 전체 로우패스 700Hz(0km/h) → 18kHz(60km/h 이상), 리버브 젖은 비율 0.16(정차) → 0, 0.5초 스무딩. 비가 오면 그 위에 비 효과가 더해짐 |
| ② | 부드러운 가속(SmoothAcceleration ~ 가속도 0.3 미만, 브레이크, 급 이벤트) | `PerfArp(on)` | 첫 음은 다음 8분음표, 이후 박마다 코드톤 상행(Am7: A4 C5 E5 G5 / Fmaj7: F4 A4 C5 E5), 4음 후 근음으로 리셋. 각 박의 코드를 따름 |
| ③ | 주행 중(≥2 m/s) 브레이크를 밟기 시작 → 0.4초 동안 감속이 2.0 m/s²보다 약하면 | `PerfRootFifth` | 다음 8분음표에 근음+5도(Am7: A3+E4, Fmaj7: F3+C4). 급제동이면 취소 |
| ④ | 방향지시등 ON / OFF | `PerfShaker(on)` | 8분음표마다 셰이커(깜빡이 주기와 무관), OFF면 그 마디 끝까지 치고 멈춤 |
| ⑤ | 정차 2초 이상(엔진 Stopped 기준을 1초 → **2초**로 변경) / 출발(D + 속도 증가 = Departed) | 레이어 | keys+synth만 남김 + 속도 0의 로우패스 = 브레이크다운 / **drums+bass를 다음 다운비트에 20ms 페이드로 동시 '드롭'**(`SetLayer(fast=true)`) |
| ⑥ | 급제동·급가속 | `PlayCue(TAPE_STOP)` | 기존 테이프스톱 + **연주 레이어도 30ms 안에 멈추고 음악이 돌아올 때까지 새 음 없음** |

- SONG 편곡 변경: 정차 = keys+synth → **출발 = drums+bass 드롭** → 안정 주행 = +perc → 오래 부드러운 주행 = +guitar. 신호 대기 = keys+synth만. (이전: 부드러운 가속 = drums+perc, 정속 = bass)
- 방향지시등의 hat 합류는 없애고 셰이커로 대체(SYNTH 세트만 필인 레이어 유지).

### 테스트(전체 **43/43 통과**, `PerformanceTest` 10개 추가)
- 양자화: 임의 시점의 명령 → 첫 음이 8분음표 격자, 지연 0~½박. 모든 음·셰이커 타격이 격자 위.
- 코드톤: SONG 타임라인의 교대, 모든 세트·모든 마디의 모든 연주음 ∈ 구성음 ∪ 펜타토닉, 아르페지오 오름차순·구성음만, 근음+5도 값, 박마다 한 음·4음 후 리셋·마디 코드 변화 반영.
- 셰이커: 8분음표 간격, OFF 후 마디 끝까지. 테이프스톱 시 새 음 없음 + 30ms 안에 무음.
- 믹서: 속도 0 < 30 < 80km/h 순으로 밝아짐. 드롭은 경계에서 20ms 안에 다 들어옴(일반 레이어는 0.5마디 페이드).
- 매핑: 속도톤·드롭(같은 배치, fast)·아르페지오 on/off·셰이커·근음+5도(정차 중·세게 밟을 때는 없음)·브레이크다운·재출발 드롭·테이프스톱.
- 데모 시나리오: 74초, 운전 이벤트 순서, 6개 장면 시각, 급제동 때 근음+5도가 없는지.

### 데모 드라이브(74초) 장면표 — 명령 시각(실제 소리는 드롭 = 다음 다운비트(≤2.67초), 연주음 = 다음 8분음표(≤0.33초))
| 시각 | 운전 | 반응 |
|---|---|---|
| 0.0s | P단, 정차, 혼자 탑승 | keys+synth, 먹먹한 톤(① 속도 0) |
| 5.8s | 출발(D + 가속) | ⑤ drums+bass 드롭 |
| 6.3~14.6s | 0→50km/h 부드러운 가속 | ② 상행 아르페지오, ① 톤이 점점 밝아짐 |
| 16.6s / 19.7s | 정속 | +perc |
| 18.0~22.5s | 왼쪽 방향지시등 + 완만한 조향 → 차선 변경 | ④ 셰이커 8분음표, OFF 후 마디 끝까지 |
| 24.4s | 브레이크 시작(부드러운 감속 2.3 m/s²) | ③ 근음+5도 |
| 27.3s | (오래 부드러운 주행) | +guitar |
| 30~37s | 신호 대기 7초 | 32.0s ⑤ 브레이크다운: keys+synth만 + 먹먹 |
| 38.1s | 재출발 | ⑤ drums+bass 드롭(+perc·guitar는 같은 마디에 페이드인), 38.7s ② 아르페지오 |
| 46.1~52.1s | 비(와이퍼) | 로우패스+리버브 |
| 53.5s | 급제동 55→30km/h | ⑥ 테이프스톱, 연주 레이어 정지, keys+synth만 → 한 마디씩 복구 |
| 56.9~60.9s | 부드러운 재가속 | ② 아르페지오 |
| 62.4s | 브레이크 시작(1.8 m/s²) | ③ 근음+5도 |
| 71.0s / 72.0s | 정차 / P단 | 브레이크다운 / 주차 |

- 에뮬레이터: 위 시각 그대로 재현됨. 렌더 부하 4~11%(5초 간격), underrun 0, 오류 0. 첫 실행 때 한 번 48% 튐이 보였으나 재실행에서는 다시 나오지 않음(첫 렌더 시점의 GC·JIT로 추정).

### 남은 문제
1. 근음+5도는 급제동과 구분하려고 0.4초를 지켜본 뒤 내므로, 브레이크를 밟은 뒤 실제 소리까지 0.4~0.73초 걸림. 감속 2.0 m/s² 이상으로 세게(급제동은 아니게) 밟으면 소리가 나지 않음.
2. 코드 타임라인은 마디 단위(Am7/Fmaj7)이지만, 반 마디 분석으로는 키보드가 2박마다 G/Am/F 계열로 움직이는 곳이 있음. 아르페지오는 구성음만 쓰므로 크게 부딪히진 않겠지만 청취 확인 필요.
3. 연주 음량(0.45)과 FM 음색은 청취 후 조정 필요.
4. 리버브가 정차 중에도 항상 켜져 있어(속도 0 = 젖은 비율 0.16) 렌더 부하가 이전(3~7%)보다 약간 늘었음(4~11%).

## 2026-11-11 — 출발 드롭(박 단위) · DSP 테이프스톱 · 화면(무드/주차 후 1분/마음 앨범)

### 음악 수정
- **출발 드롭**: `SetLayer(fast=true)`로 켜는 레이어는 다음 **박** 경계(최대 1박 = 0.67초)에서 **1/16박(약 42ms)** 페이드로 열림. 끄기·브레이크다운은 지금처럼 마디 단위.
- **테이프스톱 기본값**: 확인해 보니 **SFX**였음(이전 요청 "효과음은 기존 것 유지"에 따라 SFX를 기본, DSP는 선택지로 둠). → **DSP로 변경**: 0.7초 동안 재생 속도·음높이가 함께 0까지(속도 = (1−p)²) + 로우패스가 18kHz → 300Hz로 닫힘 → 0.15초 이상 쉰 뒤 다음 마디 머리에서 복귀(20ms 페이드인). 연주 레이어도 같이 멈춤. SFX 테이프스톱 원샷은 싣지 않음(스크래치만 효과음).

### 화면(요청 3번) — 처음 상태: a·b·c 모두 **없었음**(디버그 화면뿐), d는 해당 문구 없음
- **a. 무드 화면**(`ui/Views.kt` `MoodView`): 단색 배경 + 겹친 원 3개(레이어가 많을수록 크게, 부드러울수록 청록 ↔ 분주할수록 주황) + 물결 2줄(부드러울수록 잔잔) + 비가 오면 정적인 사선 + 재생/일시정지 아이콘(`PlayPauseButton`). 글자·숫자·알림 없음.
  - **CLAUDE.md 6번("주행 중 애니메이션 금지")과 요청("천천히 변하는 그래픽")의 충돌 처리**: 주행 중(UX 제한)에는 연속으로 움직이지 않고, 음악 상태가 바뀌어도 **최소 10초 간격**(`MoodThrottle`)으로 **3초짜리 느린 색·크기 전환**만 함. 정차 중에는 12초 주기로 아주 느리게 숨 쉬듯 움직임. 주행 중에는 앨범·디버그 아이콘이 숨겨지고 다른 화면은 닫힘.
  - **CarUxRestrictions**(`ui/UxRestrictionsWatcher`): Virtual Console D단 + 30km/h → `requiresDO=true, activeRestrictions=0x50`, P단 → `false` 확인. 앱 화면 판단은 `UX 제한 || 엔진상 주행 중`(데모·시뮬레이션도 같은 규칙).
  - 함정: 메인 스레드에서 `Car.createCar(context)`(동기)는 null, `WAIT_FOREVER` 리스너 방식은 메인 루퍼를 막아 ANR 위험 → **`CAR_WAIT_TIMEOUT_DO_NOT_WAIT` + 연결 콜백**으로 해결.
  - 에뮬레이터 2시간 41분 가동 뒤 `car_service`가 사라져(`service check car_service: not found`) 앱이 차량 서비스에 연결하지 못함 → `adb reboot`(약 2.5분)으로 복구. 장시간 시연 전에는 에뮬레이터를 새로 켤 것.
- **b. 주차 후 1분**(`album/Album.kt` `BreathCoach`): 출발 후 P단 + 운전석만 착석일 때만 제안 카드("도착했어요. 1분만 숨 고르고 갈까요?"). 동승자가 있으면 제안하지 않음. 수락 → 1분 호흡 화면: 들숨 4초 커지고 날숨 6초 작아지는 원 × 6회, "들이쉬고/내쉬고", 오늘 앨범 커버·제목을 아주 작게, 음악은 keys+synth를 마스터 0.25로 아주 작게. 끝나면 마음 앨범으로("편안한 하루 보내세요"). "다음에요" → 바로 앨범.
- **c. 마음 앨범**(`DriveRecorder` → `AlbumStore`): 출발 → P단 한 번 = 앨범 한 장. `filesDir/albums.json`(기기 안에만, 위치 저장 안 함). 제목 = "{요일} {시간대}, {분위기} {길}"(예: "화요일 밤, 빗속의 귀갓길", 비 → 빗속의 / 부드러움 70 이상 고요한 / 45 이상 잔잔한 / 그 아래 분주했던, 아침 출근길 / 저녁·밤 귀갓길 / 새벽 새벽길 / 한낮 드라이브). 숫자·점수 없음(테스트로 확인). 커버 = 시간대 배경색 + 부드러움에 따른 원 색·개수·물결 + 비 사선. 회고 한 줄(80자)은 정차 중에만 입력·저장.
- **d. 웰니스 표현**: 화면 문구를 `strings.xml`에 모으고, 의료·치료·점수 표현 금지어 검사를 단위 테스트로 넣음(치료, 치유, 진단, 처방, 의학, 의료, 질환, 스트레스 측정, 혈압, 심박, 점수, 스코어, 콤보, 랭킹).
- **4. 데모 연결**: 데모 끝(72초 P단, 혼자 탑승) → 앨범 생성 + 제안 카드 → "좋아요" → 1분 호흡 → 마음 앨범(새 앨범 강조). 에뮬레이터에서 탭으로 끝까지 확인.
- **5. 디버그 인텐트**: `--es debug screen:mood | screen:drive | screen:park | screen:breath | screen:album | screen:debug | seed:albums`(+ 기존 demo/real/stop/layers/cue/fx). 화면 왼쪽 위 점 3개 아이콘 = 디버그 패널(디버그 빌드, 정차 중만).
- 스크린샷(`docs/screens/`, 앱 창 영역만 잘라 저장 — 상태바·사용자 표시 없음, 앨범은 데모 주행 + `seed:albums` 예시 데이터): 01 무드(정차·인트로), 02 무드(주행 중, 데모 25초), 03 주차 후 1분 제안(데모 끝), 04 호흡, 05 마음 앨범, 06 호흡 후 앨범.

### 테스트(전체 **50/50 통과**, `AlbumTest` 7개 추가)
- 제목 문장(화요일 밤, 빗속의 귀갓길 등)·모든 시간대에서 숫자·"점" 없음, 커버가 부드러움·비·시간대로 달라짐, 1분 제안 조건(동승자·P단 아님·주행 안 함이면 제안 없음), 호흡 리듬(4초/6초 × 6), 데모 주행 → 앨범 1장(비 포함) + 제안 조건 충족, 주행 중 무드 갱신 10초 간격, 웰니스 금지어.
- 드롭 테스트를 "다음 박에서 1/16박 안에 열림"으로 바꿈. 테이프스톱 테스트는 0.7초 기준.

### 남은 문제
1. (해결: 아래 2026-11-11 1번 — 주행 중 완전 정지)
2. 주행 중 화면 판단이 엔진상 '주행 중'도 포함 → 실제 차량에선 CarUxRestrictions와 같지만, Virtual Console에서 속도만 바꾸면(기어 P) 둘이 어긋날 수 있음.
3. 앨범 제목의 시간대는 기기 시각 기준. (→ 아래 2026-11-11 5번에서 에뮬레이터를 Asia/Seoul로 맞춤)
4. 회고 입력은 기본 EditText(차량 키보드) — 정차 중에만 활성.

## 2026-11-11 — 주행 중 그래픽 완전 정지(SA-1) · 마음 앨범 재설계 · 차량 화면 기준 · 시간대

### 1. 주행 중 그래픽 완전 정지 (Android 차량 앱 품질 기준 SA-1)
- 이전 방식(주행 중 10초 이상 간격으로 3초 전환)은 SA-1(미디어 앱은 주행 중 움직이는 그래픽 금지, 주차 중만 예외)에 맞지 않아 **폐기**. CLAUDE.md 6번에 근거를 적어 둠.
- 상태 기계를 순수 Kotlin `ui/MoodScene`으로 분리, `MoodView`는 그리기만 함.
  - `requiresDO=true`(또는 시뮬레이션 주행 중)가 되는 순간의 모습으로 **고정**. 진행 중이던 전환도 그 자리에서 멈춤. 다시 그리기 요청도 하지 않음(CPU 거의 0).
  - 주행 중 음악 상태 변화 → `pending`에 마지막 것만 보관 → DO가 풀리는 순간(정차) 3초 동안 부드럽게 갱신. 숨쉬기 위상은 멈춘 곳에서 이어감(튐 없음).
  - 정차·주차 중 12초 주기 숨쉬기, 1분 호흡 화면 애니메이션은 그대로.
- `MoodThrottle`은 "눈에 띄게 달라졌을 때만 넘기기"로 단순화(10초 간격 규칙 삭제).
- 검증
  - 단위 테스트 `MoodSceneTest`: DO 중 10분 동안 1초마다 프레임 비교 + 음악 상태 수시 변경 → **프레임 변화 0**. 정차 시 보관 상태로 3초 전환, 숨쉬기 위상 연속.
  - 에뮬레이터: 데모 드라이브 중 화면 캡처 픽셀 비교 — 10·20·30초 **완전히 같음**, 40·49(비)·58(급제동 직후)·68초 **완전히 같음**. 30~37초 신호 대기(정차)에서만 바뀜(정차 중 갱신 = 의도한 동작).
  - 참고: 실제 차량에서 D단 신호 대기가 DO 해제인지는 차량 UX 제한 설정에 달림. 앱은 `requiresDO` 값을 그대로 따른다.

### 2. 마음 앨범 재설계
- 목록 = **정사각 커버 격자**, 커버 아래 제목만. 열 수는 "24sp 제목이 한 줄에 들어가는 가장 많은 열(최대 4, 최소 2)". 에뮬레이터 앱 창(658dp)에서는 2열.
  - 2열(칸 285dp)에서도 "수요일 아침, 빗속의 두 번째 길"은 24sp 한 줄에 안 들어감 → 이런 제목만 어절 단위로 둘째 줄까지("…빗속의 / 두 번째 길"). 글씨를 24sp 밑으로 줄이지 않기 위한 선택.
- 커버를 누르면 **상세**: 큰 커버(정사각, 화면 높이·폭 45% 중 작은 쪽) + 제목(32sp) + 날짜("11월 10일 화요일 / 오후 9:10") + 회고 한 줄(쓴 것은 글, 없으면 입력칸 + "남기기"). 입력·저장은 정차 중에만. 뒤로 버튼은 왼쪽 위.
- 새 앨범: 첫 칸에 은은한 청록 테두리(3dp, 60% 불투명) + "새 앨범이 생겼어요".
- 제목 겹침: `AlbumNaming.uniqueTitle` — 같은 날짜·같은 기본 제목(같은 시간대·분위기·길)이 앞에 있으면 "…빗속의 두 번째 길", "세 번째 길" … "열 번째 길", 그 뒤는 "또 다른 길"(숫자 없음). 주행 끝 앨범 저장과 `seed:albums`에서 사용.
- `seed:albums`: 기존 앨범을 지우고 3장 — 어제 밤·비(회고 있음) / 그제 아침·맑음·고요 / 나흘 전 한낮·맑음·분주. 날씨 데이터는 지금 "비/비 아님"뿐이라 날씨는 두 가지로만 갈림.
- 디버그 인텐트 추가: `screen:detail[:n]`, `icon:debug:off|on`(스크린샷용).

### 3. 차량 화면 기준
- 터치 영역 76dp 이상(닫기·뒤로·디버그 아이콘 76dp, 버튼 최소 높이 76dp, 재생 112dp), 본문 24sp, 제목 32sp(제안 카드 제목, 앨범 제목, 상세 제목, "들이쉬고/내쉬고"). 디버그 패널(디버그 빌드 전용)은 예외.
- 주차 제안 화면의 아이콘 정리: "•••"는 **디버그 패널**(디버그 빌드에서만, 릴리스 빌드엔 없음), 겹친 사각형은 **마음 앨범** 버튼이었음.
  - 제안 카드가 떠 있을 때는 두 아이콘 모두 숨김(카드의 "다음에요"가 앨범으로 감).
  - 마음 앨범 버튼은 **재킷에서 반쯤 나온 음반 아이콘 + "마음 앨범" 글자**의 알약 버튼(높이 76dp)으로 바꿈.
- 제안 카드는 화면 위쪽으로 옮김(이전엔 가운데여서 커진 카드가 재생 버튼과 겹쳤음).
- **에뮬레이터 실제 화면**: 디스플레이 1024×768px, 160dpi(=1024×768dp, 4:3 가로). 그런데 **앱 창은 [341,76]–[999,628] = 658×552dp(약 1.19:1, 거의 정사각)** — 왼쪽 341dp는 홈 위젯 패널, 위 76dp 상태바, 아래 140dp 공조 바. 가로로 긴 화면이 아님.
  - 이 비율 기준 점검: 무드(가운데 원 + 아래 재생, 문제 없음), 제안 카드(폭 610dp, 2줄 본문), 호흡(원 + 문구 + 작은 커버·제목), 앨범 격자(2열), 상세(가로 배치: 커버 296dp + 정보 칸, 한국어 어절 단위 줄바꿈 `LINE_BREAK_WORD_STYLE_PHRASE`, 날짜 두 줄).
  - 넓은 화면에서는 격자 열이 3~4로 늘고 상세 커버가 커지도록 비율로 계산.

### 4. 호흡 원
- 몸통을 단색 → **가장자리로 갈수록 투명해지는 방사형 그라데이션**(무드 원과 같은 청록 0x6CC3B5, 80% → 72%(62% 지점) → 0%). 바깥 번짐은 그대로.

### 5. 에뮬레이터 시간대 Asia/Seoul
- 확인: 기본값 `persist.sys.timezone = America/Los_Angeles`(이전 노트의 "UTC"는 잘못 — 미국 서부 시간이었음).
- 적용(지금 적용함, 재부팅 후에도 유지):
  ```
  adb shell settings put global auto_time_zone 0
  adb shell cmd alarm set-timezone Asia/Seoul
  adb shell getprop persist.sys.timezone   # → Asia/Seoul
  adb shell date                           # → ... KST 2026
  ```
- 다른 방법: 에뮬레이터 실행 옵션 `-timezone Asia/Seoul`(예: `emulator -avd Pleos_Connect_v2.1.0_API34 -no-snapshot-load -gpu swiftshader_indirect -timezone Asia/Seoul`). AVD를 새로 만들었거나 데이터를 지웠으면 위 adb 명령을 다시 실행.
- 결과: 데모 앨범 제목이 실제 한국 시간대로 나옴(오전 8시 → "수요일 아침, 빗속의 출근길").

### 스크린샷(`docs/screens/`)
- 앱 창 영역(658×552) + 같은 이름 `*_full.png`(차량 화면 전체 1024×768, 비율 그대로) 1장씩. 상태바의 "운"은 에뮬레이터 기본 사용자 "운전자".
- 01 무드(정차) · 02 무드(주행 중, 데모 20초) · 03 주차 후 1분 제안(데모 끝) · 04 호흡 · 05 앨범 격자(새 앨범 강조) · 06 상세(회고 있음) · 07 상세(회고 입력칸) · 08 같은 아침 두 번째 데모 주행("…빗속의 두 번째 길").
- 촬영 중 시스템 앱 DrivingView(소프트웨어 GPU에서 SIGSEGV)가 한 번 앱 창을 덮음 → 앱을 다시 띄워 해결. 우리 앱과 무관.

### 테스트: 전체 **56/56 통과**(새로: `MoodSceneTest` 4개, 제목 겹침, 날짜 한 줄. 이전 "10초 간격" 테스트는 "눈에 띄는 변화만 넘김"으로 교체)
