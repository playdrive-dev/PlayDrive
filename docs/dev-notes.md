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
