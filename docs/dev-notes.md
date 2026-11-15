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

## 2026-11-11 (오후) — 에뮬레이터 해상도 · 정차 때 커버에 한 겹("운전 중엔 귀로, 멈추면 눈으로")

### 1. 에뮬레이터 해상도
- 증상: 1024×768에서 Pleos 하단 독 아이콘과 "꺼짐" 글자가 겹침.
- 근거
  - 공식 가이드(`setup-connect-sdk-emulator`): AVD 생성 시 Form Factor `Automotive` → 하드웨어 프로필 **`Pleos Connect v2.1.0`** 선택, RAM 4GB, Cold boot. (해상도 숫자는 문서에 없음)
  - 그 프로필 정의는 시스템 이미지의 `devices.xml`: id `Pleos_Connect_v2_0`, **2560×1440, 160dpi(mdpi), 12.8"**, 가로.
  - 우리 AVD는 Android Studio 일반 프로필 `automotive_1024p_landscape`(1024×768)로 만들어져 있었음 → 원인.
- 변경(사용자 확인 후): `~/.android/avd/Pleos_Connect_v2.1.0_API34.avd/config.ini`를 `config.ini.bak-1024x768`(+`hardware-qemu.ini.bak-1024x768`)로 백업하고
  `hw.device.name=Pleos_Connect_v2_0`, `hw.device.manufacturer=42dot`, `hw.lcd.width=2560`, `hw.lcd.height=1440`, `hw.lcd.density=160`, `hw.initialOrientation=landscape`, `hw.device.hash2` 삭제. RAM 4096·데이터·시간대 유지. 되돌리기 = 백업 복원.
- 실행: `emulator -avd Pleos_Connect_v2.1.0_API34 -no-snapshot-load -gpu swiftshader_indirect -timezone Asia/Seoul` → 30초 만에 부팅, `wm size 2560x1440`, density 160.
- 결과: 독 아이콘 간격·"꺼짐" 글자·왼쪽 미디어 위젯 모두 정상. **앱 창 = [853,90]–[2535,1300] = 1682×1210dp(1.39:1)**(이전 658×552).
- 레이아웃 재점검: 무드·제안 카드·호흡·앨범 격자(4열)·상세(커버 + 정보) 모두 이 비율에서 자연스러움. 24sp 글자는 큰 화면에서 작아 보이지만 기준 최소값이므로 유지.
- 함정: 시스템 앱 **DrivingView(Unreal)가 소프트웨어 GPU에서 계속 죽고 다시 떠서 앱 창을 덮음**(검은 화면). 에뮬레이터에서만 `adb shell pm disable-user --user 10 ai.umos.drivingview`로 끔(되돌리기: `adb shell pm enable --user 10 ai.umos.drivingview`). 끈 직후엔 남은 SurfaceView가 검게 덮여 있어 `adb reboot` 필요. 재부팅 직후 앱을 너무 일찍 띄우면 시스템 바 없이 전체 화면으로 뜸 → 홈 키 후 다시 실행.

### 2. 정차 때 커버에 한 겹
- `album/Cover.kt`
  - `CoverSketch`(순수): 첫 출발 = 새 커버, **정차(Stopped)·주차(Parked)마다 지난 구간(2초 이상)을 한 겹**으로 닫음. 겹 = 부드러움(하모니 평균) → 원 크기·색(청록↔주황), 비 온 비율 → 빗줄기 수, 급가속·급제동(테이프스톱) 수 → 물결이 흐트러진 자국(최대 3). 위치는 황금각 나선으로 퍼뜨림.
  - 주차 후에도 겹을 들고 있다가 `Album.layers`로 저장 → **완성된 커버가 그대로 새 앨범 커버**. `albums.json`에 `layers` 배열 추가(이전 앨범은 요약으로 만든 커버 그대로).
- `ui/MoodScene`: **장면 시계**로 바꿈 — 주행 중(requiresDO)에는 시계 자체가 멈춰 음악 전환·숨쉬기·커버 장면 모두 그 자리에서 정지. 주행 중 새 겹은 `pendingCover`에 보관 → 정차 순간 4초 장면: 0~0.4초 커버가 또렷해짐(불투명도 0.22 → 0.6), 0.4~2.8초 새 겹이 자라남(원이 커지고 빛 고리, 빗줄기가 내려오고, 자국이 번짐), 3~4초 다시 은은하게. 그동안 무드 원은 옅게(×0.35).
- 정지 화면 구성: 오늘 커버(무드 원과 같은 중심, 화면 높이만 한 정사각형)를 배경에 은은하게 + 가장자리는 배경색으로 스며들게(네모 테두리 없음) + 그 위에 무드 원·물결.
- 순서 버그 수정: 출발 순간 새 커버 콜백이 "주행 중" 상태보다 먼저 와서 빈 커버가 주행 중 화면에 새던 문제 → 커버 콜백을 상태 콜백 뒤에 보냄.
- 마지막 정차 장면이 끝난 뒤(4초) 주차 후 1분 제안 카드를 띄움.
- 앱 시작 화면 = 가장 최근 앨범 커버가 은은하게.

### 3. 데모 확인(2560×1440)
- 첫 출발 5.8초 → 신호 대기 **32.0초에 Stopped → 1겹**(비 0, 자국 0) → 32~36초 장면이 잘 보임(09·10·11 스크린샷). 재출발 38초 → 마지막 정차 71초 → **2겹(비 8줄, 흐트러진 자국 1)** → 75초 제안 카드 → 앨범 "수요일 한낮, 빗속의 드라이브"의 커버 = 주행 중 그린 커버(05·07).
- 주행 중 화면 픽셀 비교: 12초 = 25초, 45초 = 60초(비·급제동 포함) **완전히 같음**.

### 테스트: 전체 **60/60 통과**(새로: `CoverSketchTest` 2개 — 데모에서 정차마다 한 겹·비·자국·앨범 커버 일치, `MoodSceneTest` 2개 — 주행 중 새 겹 숨김·정차 장면 4초·장면 도중 출발 시 정지 후 이어감)

## 2026-11-11 (저녁) — 화면 v2: 빛을 재료로 쓰는 화면(AGSL + Compose)

### 구조
- `ui/MoodScene`(순수): 상태·시간 규칙. 추가: 시간대 색온도(정차 중 6초 전환, 주행 중 보관), 웰컴(0→1, 3초)·굿바이(→0.3, 2초)·깨어남(→1, 1.5초), 박 숨쉬기(`pulseShape`: 박 머리 후 0.3박(약 0.2초)에 걸쳐 +3%까지 차오르고 남은 박 동안 가라앉음, 박 경계 연속), 느린 움직임 시각(600초 순환 — 셰이더의 모든 주기 10·12·600초가 600을 나눔 → 순환해도 튀지 않음).
  - **DO가 시작되는 순간 진행 중인 전환을 완성된 모습으로 맞춘 뒤 멈춤**(정지 프레임 = 완성된 한 장). 그 뒤 음악·커버·시간대·박 입력은 모두 보관/무시.
- `ui/light/LightParams`(순수): MoodFrame/CoverSpec → uniform 값. 시계·난수 없음 → 프레임이 같으면 uniform도 같다(`LightParamsTest`).
- `ui/light/LightShader`(AGSL): 한 셰이더로 무드 화면과 앨범 커버(coverMode)를 그림.
  1. 시간대 색온도 그라데이션(아침 옅은 청회색 / 한낮 맑은 청록 / 노을 위 자주·아래 앰버 / 밤 깊은 남색-검정 / 새벽) + 반사 띠 근처의 옅은 빛
  2. 메인 빛: 가우시안 세 겹(심·몸통·후광) + 가장자리 값 노이즈로 흩어짐, 화면 중앙보다 약간 위(y 0.40), 10·12초 주기로 아주 느리게 흔들림
  3. 보조 빛: 깊은 곳의 빛 2개 + 오늘 커버의 겹(최근 6개, 오래될수록 깊이 ↑ = 크고 흐리고 어둡게). 정차 장면의 새 겹은 빛이 자라며 가는 빛 고리
  4. 물결: 빛 아래일수록 밝은 가는 하이라이트 띠 두 줄 + 바닥 번짐. 테이프스톱 자국 = 그 x 위치에서 띠가 한 번 흐트러지고 끊김
  5. 비: 앞유리 물방울 두 크기 격자(원형 렌즈 굴절로 뒤의 빛이 뒤집혀 일그러짐 + 테두리 그늘 + 작은 반짝임) + 아주 옅은 흐름 자국
  6. 필름 그레인 ±1.1/255(픽셀마다 고정 — 깜빡이지 않음, 밴딩 제거용)
  + 유리 패널: 재생·앨범 버튼 자리에서 같은 장면을 더 흐리게 + 7% 밝게. 1px 하이라이트 테두리(위가 밝음)는 버튼 뷰가 그림
- 커버: 부드러움 = 빛의 크기·선명도(깊이), 비 = 물방울 밀도, 테이프스톱 = 반사 띠 자국, 시간대 = 색온도. 앨범 격자·상세·호흡 화면의 작은 커버 모두 같은 셰이더.
- `ui/MoodView`: 상태(scene)를 들고, API 33+ 는 `ComposeView` + `drawBehind { drawRect(ShaderBrush(RuntimeShader)) }`, 그 아래는 `CanvasMoodLayer`. 정차 중에만 프레임 루프(최대 30fps), 주행 중에는 루프 자체가 없음.
- 의존성: Compose BOM 2025.05.01(ui, foundation), Kotlin Compose 컴파일러 플러그인 2.2.10. APK(디버그) 28MB.

### 웰컴·굿바이
- 웰컴: 앱 시작 → 창 바탕 검정(테마, **흰 화면 번쩍임 제거** — 처음엔 첫 프레임 전에 흰 창이 한 번 보였음) → **첫 화면이 실제로 그려진 순간부터** 3초 동안 빛이 피어남 → 끝나는 순간 `playWithChime()`: 차임 큐 + 재생 시작(격자 0 = 첫 다운비트).
  - 차임 = `music/synth/Chime`(코드 합성, 라이선스 문제 없음): A5·C6·E6를 60ms씩 엇갈려, 정수배 배음만, 0.9초 감쇠. A·C·E는 Am7·Fmaj7 둘 다의 구성음 → 어느 마디에서도 어울림. 덕킹 없이 음악과 함께.
  - "P단에서 시동"은 시동 신호가 SDK에 없어 앱 시작으로 대신함(첫 신호가 P단일 때 다시 웰컴을 하려면 시동 이벤트 확인 필요).
- 굿바이: 주차 확정 → 마지막 정차 장면(4초) → 2초 동안 빛이 0.3까지 가라앉음 → 제안 카드. 카드를 닫고 무드로 돌아오면 1.5초 동안 다시 깨어남.
  - 버그 수정: 제안 카드를 띄울 때 `dismissOverlay()`가 빛을 깨워 굿바이가 바로 취소되던 문제 → 카드가 실제로 닫힐 때만 깨움.

### UI 크롬·글꼴
- 재생 버튼·마음 앨범 버튼: 유리(셰이더가 뒤를 흐리게 + 1px 하이라이트 테두리), 아이콘은 얇은 선(재생 삼각형 윤곽, 일시정지 두 줄, 음반·재킷 선). API 33 미만은 옅은 반투명 면 + 1px 테두리.
- 글꼴: 시스템 산세리프 하나(본문 `sans-serif`, 제목·버튼 `sans-serif-medium`), 제목 자간 0.04em. Pretendard는 파일을 넣어야 해서 이번엔 시스템 글꼴로 통일.

### 성능(에뮬레이터, 2560×1440, 소프트웨어 GPU swiftshader, 4코어)
| 조건 | 프레임 시간(중앙/90%) | 앱 CPU(한 코어 기준) | 그래픽 합성(composer) |
|---|---|---|---|
| 셰이더 원래 해상도(1682×1210) | 450 / 650ms | 39~57% | 61% |
| 셰이더 절반 해상도 | 200 / 250ms | 42~65% | 77% |
| 셰이더 1/4 해상도 | 150 / 200ms | 54% | 74% |
| (비교) 이전 Canvas 무드 화면 | 150 / 300ms | 44~58% | 69% |
| 주행 중(정지 프레임) | **프레임 0개** | 4~9%(음악·엔진) | 0% |
- 해석: 1/4 해상도에서도, 이전 Canvas 화면에서도 150ms → **이 에뮬레이터는 2560×1440 전체 화면 합성을 소프트웨어로 해서 무엇을 그려도 약 6~7fps가 한계**. 셰이더 자체 비용은 원래 해상도에서만 두드러짐(450ms).
- "정차 중 렌더 부하 15% 이하" 목표: 앱 CPU = 4코어 전체의 약 14~16%(절반 해상도). 기준을 넘는 쪽으로 보고 **기본값을 절반 해상도(0.5배로 그린 뒤 하드웨어 레이어로 확대)로 정함**(`MainActivity.DEFAULT_RENDER_SCALE`, 디버그 인텐트 `render:1.0|0.5|0.25|canvas`로 바꿔 측정). 정차 중 프레임은 최대 30fps로 제한.
- 실제 차량 GPU에서는 셰이더(픽셀당 빛 8개 × exp 3번 + 노이즈)가 가벼운 편이라 다시 측정 필요. 주행 중에는 그리지 않으므로 CLAUDE.md의 "주행 중 CPU 5% 미만"과 같은 기준(앱 전체 1~2.3%).
- 콜드 스타트: 화면 표시 3.2~4.9초(Compose 초기화 포함, 기준 10초 이내).

### 확인(데모 74초 × 아침·노을·밤)
- 주행 중 화면 픽셀 비교: 각 시간대에서 12 = 22 = 31초, 45 = 60초(비·급제동 포함) **완전히 같음**.
- 신호 대기(32초) 정차 장면, 마지막 정차(71초) 장면, 굿바이, 제안 카드 순서 확인. 스크린샷 `docs/screens/v2/`(JPEG q92 — PNG는 그레인 때문에 장당 0.9MB라 JPEG 0.15MB로 저장).
- 에뮬레이터: DrivingView 비활성 상태 유지(위 오후 노트 참고).

### 테스트: 전체 **68/68 통과**
- `MoodSceneTest` 추가: 장면 도중 출발 → 완성된 모습으로 정지(박 입력이 바뀌어도 불변), 웰컴 3초·굿바이 2초·깨어남, 박 숨쉬기(진폭 3% 안, 경계 연속, 프레임 간 변화 < 0.3%, 주행 중 1), 시간대 보관·6초 전환, 600초 순환.
- `LightParamsTest`: 주행 중 셰이더 uniform 불변(음악·비·커버·박이 바뀌어도 300회), 시간대 색온도, 커버 매핑(부드러움 = 크기·선명도, 비 = 물방울, 테이프스톱 = 자국).
- `ChimeTest`: 차임 음이 두 코드의 공통 구성음, 10ms 안에 울림, 클리핑 없음.

### 남은 문제
1. ~~에뮬레이터에선 정차 중 움직임이 5fps 안팎~~ → 2026-11-11 `-gpu host`로 해결(30fps, 아래 v3 노트).
2. 박 숨쉬기는 믹서의 격자 시계 기준이라 오디오 출력 지연(수십 ms)만큼 빛이 앞섬. 0.2초에 걸쳐 차오르는 모양이라 체감은 작음.
3. 주행 중 비가 와도 정지 프레임엔 물방울이 없음(SA-1로 변화 금지 → 다음 정차 때 반영). 출발 전부터 비가 오던 경우는 정지 프레임에 물방울이 보임.

## 2026-11-11 — v3: 에뮬레이터 GPU, 비주얼 다듬기

### 에뮬레이터 GPU 옵션(영상 촬영 대비)
Mac M2, 에뮬레이터 37.1.11, AVD Pleos Connect v2.1.0(2560×1440, 160dpi), 앱 정차 무드 화면. 프레임 = `dumpsys gfxinfo` 10초(앱은 정차 중 30fps로 제한).

| `-gpu` | 부팅 | 차량 서비스(`car_service`) | 앱 실행 | 정차 중 fps | 앱 CPU | 렌더러 |
|---|---|---|---|---|---|---|
| `swiftshader_indirect`(이전) | 30초 | 정상 | 정상 | **약 5** | 14~16%(4코어 기준) | 소프트웨어 |
| `host` | 18초 | 정상 | 정상 | **30.1**(301프레임/10초, 90% 17ms) | 8.0% | Apple M2 OpenGL ES 3.0 (Metal) |
| `host` + 전체 해상도(`render:1.0`) | — | — | — | 30.2 | 7.6% | 〃 |
| `host` + 전체 해상도 + 비 | — | — | — | 30.1 | — | 〃 |
| `angle_indirect` | 18초 | 정상 | 정상 | 30.1 | 8.0% | 이 에뮬레이터는 지원 안 함 → **`auto`로 바뀜**(로그 `Selected GPU option 'angle_indirect' is not valid`) |
| `auto` | 16초 | 정상 | 정상 | 30.2 | 11.5% | `host`와 같은 렌더러 |

- 결론: **`-gpu host` 사용.** 11/10 노트의 "`-gpu host`에서 exit 139" 문제는 에뮬레이터 37.1에서는 재현되지 않음(약 30분 촬영·측정 동안 멈춤·종료 없음).
- 녹화: `adb emu screenrecord start --time-limit 6 --fps 30 out.webm` → 2560×1440, 6초에 159프레임(30fps). 거의 같은 프레임을 느슨하게 걸러도(`mpdecimate`) 125프레임 남음 → 정차 중 느린 움직임도 매끈하게 담김.
  앱 안 오프스크린 녹화 모드(MediaCodec)는 필요 없어 만들지 않음.
- 렌더 배율 기본값은 0.5 유지(실차 GPU 미측정). 영상은 `render:1.0`으로 찍으면 물방울이 더 또렷함(GPU에서는 부하 차이 없음).
- 왼쪽 DrivingView 자리는 앱을 비활성화해 비어 있음(흰 패널) — 영상은 앱 영역만 잘라 쓰는 걸 권장.

### 비주얼
- 빗방울: 격자 하나(0.045)에 확률 0.40×비 → 이전의 약 1/3. 반지름 = 0.06 + 0.32·h³(작은 방울이 대부분). 테두리 그늘 없음.
  굴절은 렌즈 중심 대칭(뒤집힌 상, 1.6~2.4배). 반짝임은 큰 방울에만, **장면이 바탕보다 밝은 만큼(빛의 몫)** 에 비례 → 빛이 있는 쪽에만. 흘러내린 자국 3~5개(주행 ID·비 시드로 고정, 끝에 맺힌 방울).
- 테이프스톱 흔적: 실시간 무드 화면에서는 제거(`scarCount = 0`). 커버에서는 톱니 노이즈 대신 사인 한 주기로 한 번 출렁이고 그 자리 띠가 넓고 옅게 흩어짐.
- 팔레트: 바탕 그라데이션을 **OKLCh**로 보간(Kotlin에서 위·아래를 LCh로 바꾸고 색상각을 짧은 쪽으로 펼쳐 uniform으로, 셰이더는 선형 보간 후 sRGB로).
  노을 #1c1640 → #c8783a: 중간 채도가 sRGB 섞기보다 1.3배 이상(테스트) → 탁한 갈색 대신 장밋빛. 아침: 오른쪽 위 금빛 햇빛(`sun` uniform). 밤: 그대로.
- 유리: 제안 카드도 셰이더 유리(최대 3장). 밝은 바탕(노을 아래)에서는 유리 안을 살짝 어둡게 해 흰 아이콘이 읽히게.
- 앨범 커버 규칙(`CoverArt`): 시간대 = 바탕, 정차 횟수 = 빛 개수(1~5, 6번째 정차부터는 기존 빛에 합침), 부드러움 = 크기·선명도, 주행 시간 = 퍼짐(`spread`: 1분 0.11 → 30분 0.33, 이미 그린 빛은 움직이지 않음), 비 = 물방울, 테이프스톱 = 물결 흩어짐. 시작 각도도 주행 ID로 정함 → 커버마다 배치가 다름.
- `seed:albums`: 데모로 쌓인 앨범을 모두 지우고 6장(밤·비·정차3 / 아침·맑음·정차5 / 노을·정차2 / 한낮·분주·정차4 / 아침·비·정차1 / 새벽·정차2).

### 테스트: 전체 **74/74 통과**
- 추가: 노을 OKLCh 중간 채도, 아침에만 햇빛, 무드 화면엔 흔적 없음·커버에만, 정차 횟수 = 빛 개수(1~5), 같은 ID = 같은 커버·다른 ID = 다른 배치, 짧은 주행 모임·긴 주행 퍼짐(1.8배 이상), 6번째 정차부터 합침.
- 주행 중 uniform 불변(300회) 그대로 통과.


## 2026-11-12 — 제출 준비(기능 추가 중단, 버그 수정만)

### 1. 앱 아이콘(adaptive icon)
- 원본 `docs/icon/playdrive_icon.svg`(108×108 캔버스, 안전 영역 지름 66). 배경 = 밤 팔레트 남색 그라데이션(#22305A → #121A33 → #070A16), 전경 = 청록 메인 빛(방사형, #EAFFF9 → #6CC3B5 투명) + 바닥 빛 띠 두 줄. 글자 없음.
- 리소스: `drawable/ic_launcher_background.xml`·`ic_launcher_foreground.xml`(VectorDrawable, aapt 인라인 그라데이션), `ic_launcher_monochrome.xml`(테마 아이콘), `mipmap-anydpi-v26/ic_launcher(_round).xml`. 매니페스트 `android:icon`/`roundIcon`.
- 확인: 앱 목록 `docs/icon/launcher_apps_full.jpg`, 독(실행 중) `docs/icon/launcher_dock_full.jpg`. 런처 이름 "PlayDrive"(aapt `application-label`).
- 참고: 재설치 직후 독의 실행 중 칸에 기본 로봇 아이콘이 잠깐 보임(독 캐시). 다른 앱으로 갔다 오면 새 아이콘으로 바뀜.

### 2. release APK
- 키: `~/.playdrive/playdrive-release.jks`(PKCS12, RSA 2048, 10000일, DN = `CN=PlayDrive`만), 비밀번호는 `~/.playdrive/keystore.properties`(권한 600). 저장소 밖. gradle은 이 파일(또는 local.properties의 `playdrive.keystore=<경로>`)이 있을 때만 서명.
- minify 끔. versionName 1.0. `apk/PlayDrive_v1.0_release.apk` 23.9MB(< 100MB → 저장소에 포함). apksigner 확인 `CN=PlayDrive`.
- release에서 데모 드라이브 진입: 무드 화면 왼쪽 위 **데모 드라이브** 유리 버튼(정차 중에만, 음악 준비 후 활성). 디버그 버튼은 왼쪽 아래로 옮김(디버그 빌드만).
  adb 인텐트는 release에서 `demo`, `seed:albums`, `icon:demo:off|on`만 받음. 여러 명령은 `;`로 이어서(`'seed:albums;demo'`).
- 에뮬레이터 확인(release): 데모 74초 → 주행 중 정지 프레임·버튼 숨김 → 80초쯤 제안 카드. 오류 로그 0.
- 첫 실행 시 CAR_SPEED 권한 창이 "위치 정보" 권한으로 뜸(AAOS 권한 그룹). `pm grant`는 `--user 10`(운전자 사용자)에 줘야 함.

### 3. README
- 심사위원용으로 재구성(소개 → 영상 자리 → v3 핵심 장면 6장 → 동작 표 → 안전 설계 → SDK·권한 → 실행 → 음원 → 라이선스). v1~v3 화면 기록은 아래 절로 옮김.

### 4. 시연 영상 녹화(`tools/record_demo.sh`)
- 소리 측정: `adb emu screenrecord`의 오디오 트랙은 앱 재생 중에도 평균 −62.7dB(호스트 마이크로 추정), scrcpy 5.0.1 `--audio-source=playback`/`output`은 −91dB(디지털 무음) — AAOS 차량 오디오 버스로 가는 앱 소리는 기기 안 캡처로 잡히지 않음.
- 해결: 디버그 빌드 전용 `WavTap` — AudioTrack에 쓰는 float PCM을 그대로 16bit 스테레오 WAV로(`rec:audio:on|off`, 일시정지 중엔 무음을 채워 길이 유지). 첫 샘플의 벽시계 시각을 기록해 영상 시작 시각과 맞춤(기기−맥 시계 차이 보정, 측정 약 234ms).
- 앱 창 자르기 1680×1206(+855+92, 창 테두리 2px 제외), 30fps, x264 CRF 16 + AAC 192k.
- 10초 테스트: 300프레임/10초, 소리 평균 −18.7dB·최대 −4.7dB(클리핑 없음).


## 2026-11-12 — 주행 중 화면 정책 변경: "완전 정지" → "이벤트 때만 한 번 반응(최소 변화)"

근거: Pleos 운전자 주의 분산 가이드(장식적·반복적 움직임 지양, 정보 전달에 필요한 최소한만). 사용자 결정.
주의: 이전 근거였던 Android 차량 앱 품질 기준 SA-1(미디어 앱은 주행 중 움직이는 그래픽 없음)을 엄격히 적용하는 심사라면 지적될 수 있다.

### 구조
- `ui/DriveMotion`(순수): 주행 시작 때 만들어져 레이어 수·새 테이프스톱·비를 받는다. 목표 모습(Look: 단계 −2~+2, 어둡게, 비)이 지금 모습과 다르고 지난 전환 시작에서 10초가 지났으면 전환을 시작한다.
  - 레이어 순증 → +1단계(크기 +6%, 밝기 +8%), 순감 → −1단계(각 1.5초). 10초 사이 증감은 합쳐서 부호만 반영.
  - 테이프스톱(HarshBraking/HarshAcceleration) → 밝기 ×0.75(0.7초). 레이어가 테이프스톱 전 수 이상으로 돌아오면 다음 전환에서 원래대로(1.5초).
  - 비 ON/OFF → 물방울 세기 0↔1(2초). 셰이더에 `rainAlpha` 추가(물방울 배치는 고정, 굴절·반짝임에 곱함).
  - 전환 길이 = 바뀌는 항목 중 가장 긴 것(≤2초).
- `MoodScene`: 주행 중 프레임 = 정지 프레임 + DriveMotion 값(`mainScale`, `mainGain`, `rainAmount`). 정차하면 보관한 음악 상태와 함께 3초에 걸쳐 1로 돌아간다. 시간대 색은 여전히 정차 때만.
- `MoodView`: 주행 중에는 프레임 루프를 끄고, 전환이 시작될 때만 켠다. 끝 시각에 마지막 프레임을 한 번 그린 뒤 끄고, 다음 전환 가능 시각에 다시 확인한다(Handler 예약 1개).

### 테스트(전체 **79/79 통과**)
- 기존 "DO 중 프레임 변화 없음"을 교체: 주행 600초 시뮬레이션(2.7초마다 레이어 증감, 30초마다 테이프스톱, 47초마다 비, 7초마다 음악 상태 변화)에서 (1) 전환 밖 프레임 불변, (2) 전환 시작 간격 ≥10초, (3) 길이 ≤2초.
- (4) 이벤트 없음(같은 상태 600번) → 전환 0, 다음 예약 없음, 600초 내내 같은 프레임.
- 개별: 레이어 추가 1.06/1.08·1.5초·유지, 10초 안 이벤트 미룸, 테이프스톱 0.7초에 −25%·회복, 비 2초 페이드, 정차 후 3초에 걸쳐 1로.

### 에뮬레이터(데모 드라이브, -gpu host, render 0.5)
- 전환 로그(데모 시작 기준): 5.8초 출발 드롭(1.5초) → 16.6초 레이어 추가(1.5초) → [32~38초 신호 대기 정차] → 38.1초 재출발 드롭(1.5초) → 48.1초 비 페이드 인(2초) → 58.1초 테이프스톱 −25% + 비 OFF(2초) → 68.1초 회복(1.5초) → 정차·주차. 간격 모두 ≥10초(비·테이프스톱은 앞 전환에서 10초가 지난 뒤 반영됨).
- 화면 녹화의 프레임 차이: 주행 중 바뀐 구간은 위 전환 시각뿐. 테이프스톱 때 메인 빛 중앙 밝기 144 → 129(8비트 회색), 회복 뒤 144.
- 앱 CPU(top, 코어 1개 기준, 1초 샘플): 주행 중 정지 평균 11.7%(최대 17%, 대부분 오디오 엔진: 오디오 렌더 부하 4~6%), 전환 중 14.1%(최대 18%), 정차 중 애니메이션 14.5%(최대 19%). 주행 약 66초 중 전환은 약 10초.


## 2026-11-12 (밤) — 화면 효과 강도 설정(LIVE 기본 / CALM) · 시연 원본 녹화 · 화면-소리 싱크

사용자 결정: 주행 중 "이벤트 때만 반응"은 시연에서 밋밋해 관심을 못 끈다 → 설정으로 고르게 하고 기본은 LIVE. SA-1·Pleos 가이드와 부딪힐 수 있음을 알고 내린 결정(CLAUDE.md 6절).

### LIVE(`ui/LiveMotion`)
- 킥 맥박(+10%·+4%, 80/400ms), 드롭 버스트(+35%, 상승 150ms·감쇠 600ms) + 바닥 물결(1.2초, 셰이더 `ripple`), 악기 빛(레이어 수 = 보조 빛, 최대 6, 셰이더 빛 8 → 14개),
  속도 색(채도·밝기 +15%, 시간 상수 0.67초), 테이프스톱 가라앉음(0.7초, 화면 −35%·빛이 아래로, 믹서가 레이어를 실제로 줄일 때까지 유지 → 들리는 레이어가 돌아오는 만큼 회복),
  깜빡이 가장자리 번짐(셰이더 `sideGlow`), 비 물방울 페이드 + 킥 반짝임(`sparkle`).
- 광과민성: 밝기(속도 색 포함) ≤ 1.35배, 맥박 최소 간격 520ms(어느 1초에도 2회까지 — "초당 3회 이상 금지"를 엄격히), 드롭은 그 자체로 맥박 하나(바로 뒤 킥과 겹쳐 두 번 번쩍이지 않게),
  드롭 상승을 80 → 150ms로(한 프레임 밝기 변화 ≤0.12).
- 설정: 오른쪽 아래 설정 버튼(정차 중에만) → "화면 효과: 라이브 / 차분", SharedPreferences, 다음 출발부터. 유리 패널 3 → 4개.
- 테스트(전체 **93/93**): `DriveLiveTest` 13개(600초 밝기·점멸, 240BPM, 킥, 드롭, 출발 직후 드롭, 악기 빛, 속도 색, 테이프스톱 2, 깜빡이, 비, 바탕 보관, 레벨 전환, 정차 복귀),
  `DriveCalmTest`(기존 주행 중 테스트를 CALM으로 분리), `MoodSceneTest`(공통).
- 에뮬레이터(-gpu host, render 0.5, 데모 74초): 앱 CPU(코어 1개 기준) LIVE 주행 중 평균 27.6%(최대 77%, 순간), 정차 20.6% — CALM 주행 중 11.7% 대비 +16%p.
  프레임 2212개/78초(약 28fps), GPU 프레임 시간 중앙값 15ms·90% 17ms.

### 화면-소리 싱크(버그 수정)
- 문제: 화면 효과가 믹서 위치(소리를 *만드는* 순간)를 보고 반응 → 출력 지연(AudioTrack 80ms + 에뮬레이터 HAL, 측정 150~190ms)만큼 화면이 먼저 튐. 실제 차에서도 같은 문제.
- 해결: AudioTrack 타임스탬프를 PLL처럼 걸러(측정이 가끔 실패·±40ms 흔들림) 매끄러운 재생 시계를 만들고, 화면은 그리는 순간의 "지금 들리는 격자 위치"를 본다.
  믹서는 레이어가 바뀐 격자 위치를 기록(`activeLayersAt`) → 박·드럼·레이어 수 모두 들리는 위치 기준.

### 시연 원본 녹화(`tools/record_demo.sh` → `docs/demo/`, git 제외)
- 소리: 에뮬레이터 화면 녹화·scrcpy는 앱 소리를 못 담음(무음) → 디버그 빌드 `record:start|stop`이 최종 믹스를 32bit float 48kHz WAV로(같은 재생 시계로 시간축, 빈 시간은 무음).
  1초마다 (WAV 위치, 들리는 시각)을 남겨 직선 맞춤 → 오프셋·오디오 시계 비율(이번 0.99983, 약 170ppm)을 보정해 합친다.
- 결과물: video_raw.webm(원본), app_screen_2560x1440.mp4, app_screen.mp4(앱 창 → 1920×1080 레터박스), app_audio.wav, app_with_audio.mp4, timeline.md, sync_check.md.
- 싱크 확인(`tools/check_sync.py`): 정답 = 오디오 스레드가 남긴 드럼 진입 샘플 위치, 화면 = 영상의 메인 빛 밝기 상승 시작(드롭 곡선이 20%에 닿는 34ms 보정).

화면 반응 시작 = 밝기 상승폭 20% 지점 − 34ms(앱의 드롭 밝기 곡선이 20%에 닿는 시간)

| 드럼 진입(소리, 영상 시각) | 화면 반응 시작 | 화면 − 소리 |
|---|---|---|
| 14.673s | 14.705s | +31ms |
| 46.729s | 46.695s | -34ms |
| 66.755s | 66.711s | -44ms |

최대 차이 44ms → 50ms 안 ✅ (화면은 30fps라 ±17ms 오차)

- 남은 한계: 에뮬레이터는 실제 스피커 출력을 캡처할 수 없어, 위 값은 "앱의 재생 시계 기준"으로 화면과 소리가 맞는지다(실차 HAL 지연은 AudioTrack 타임스탬프가 맞다는 전제).
- Virtual Console 동시 녹화는 하지 않음: 데모 드라이브는 신호를 앱 안에서 주입하므로 Virtual Console 화면에 속도·깜빡이 변화가 나타나지 않는다.


## 2026-11-12 — README에서 옮긴 화면 기록(v1~v3)

README를 심사위원용으로 다시 쓰면서 버전별 화면 설명과 스크린샷 표를 여기로 옮겼다(스크린샷 파일은 `docs/screens/`에 그대로).

### 화면 v3 — 다듬은 빛

빗방울은 대부분 투명한 작은 렌즈(뒤 장면이 뒤집혀 비침, 빛 쪽에만 반짝임, 3~5개는 흘러내린 자국), 노을은 남보라→앰버를 OKLCh로 보간, 아침은 오른쪽 위 금빛 햇빛, 제안 카드는 버튼과 같은 유리. 테이프스톱 흔적은 앨범 커버에만(물결이 한 번 부드럽게 흩어진 모양).
앨범 커버 규칙: 시간대 = 바탕색, 정차 횟수 = 빛의 개수(1~5), 부드러움 = 빛의 크기·선명도, 주행 시간 = 빛의 퍼짐, 비 = 물방울, 테이프스톱 = 물결 흩어짐, 주행 ID로 고정.

| 아침 · 정차 · 비 | 노을 · 정차 | 밤 · 주행 중(정지 프레임) | 제안 카드(유리) |
|---|---|---|---|
| ![](screens/v3/morning_03_parked_rain.jpg) | ![](screens/v3/sunset_02_parked.jpg) | ![](screens/v3/night_04_driving_frozen.jpg) | ![](screens/v3/sunset_08_park_card.jpg) |
| **정차: 지난 구간이 한 겹(빛)으로** | **노을 · 비** | **마음 앨범(6장 예시)** | **앨범 상세** |
| ![](screens/v3/night_05_stop_reveal.jpg) | ![](screens/v3/sunset_03_parked_rain.jpg) | ![](screens/v3/20_album_grid.jpg) | ![](screens/v3/21_album_detail.jpg) |

전체 목록은 `docs/screens/v3/`(v2와 같은 구성, `*_full.jpg` = 차량 화면 전체 2560×1440).

### 화면 v2 — 빛을 재료로 쓰는 화면

무드 화면과 마음 앨범 커버를 **AGSL 셰이더(API 33+, Compose)** 한 벌로 그립니다. 시간대 색온도 배경 + 부드러운 메인 빛(박에 맞춰 3%만 숨쉬기) + 깊이가 다른 보조 빛(오늘 커버의 겹) + 바닥에 반사된 듯한 가는 빛 띠 + 앞유리 물방울(비) + 미세 필름 그레인. 주행 중에는 완성된 한 장으로 멈춥니다. API 33 미만은 Canvas 버전으로 그립니다.

| 웰컴(검은 화면 → 3초 동안 피어남) | 아침 · 정차 | 노을 · 정차 · 비 | 밤 · 주행 중(정지 프레임) |
|---|---|---|---|
| ![](screens/v2/01_welcome_b_bloom.jpg) | ![](screens/v2/morning_02_parked.jpg) | ![](screens/v2/sunset_03_parked_rain.jpg) | ![](screens/v2/night_04_driving_frozen.jpg) |
| **정차: 지난 구간이 한 겹(빛)으로** | **마지막 정차** | **굿바이(2초 동안 가라앉음)** | **마음 앨범(같은 셰이더 커버)** |
| ![](screens/v2/night_05_stop_reveal.jpg) | ![](screens/v2/sunset_06_last_stop.jpg) | ![](screens/v2/night_07_goodbye.jpg) | ![](screens/v2/20_album_grid.jpg) |

전체 목록은 `docs/screens/v2/`(아침·노을·밤 × 정차/비/주행 중/정차 장면/마지막 정차/굿바이/제안 카드, `*_full.jpg` = 차량 화면 전체 2560×1440).

### 화면 v1(Canvas)

| 무드(정차, 오늘 커버가 은은하게) | 무드(주행 중 — 완전 정지) | 주차 후 1분 제안 | 1분 호흡 |
|---|---|---|---|
| ![](screens/01_mood_parked.png) | ![](screens/02_mood_driving.png) | ![](screens/03_park_offer.png) | ![](screens/04_breath.png) |
| **마음 앨범(새 앨범 강조)** | **앨범 상세(회고 있음)** | **앨범 상세(회고 입력)** | **같은 날 두 번째 주행** |
| ![](screens/05_album_grid.png) | ![](screens/06_album_detail.png) | ![](screens/07_album_detail_input.png) | ![](screens/08_album_second_trip.png) |
| **정차 직전(주행 중 정지 화면)** | **정차 순간: 지난 구간이 커버에 한 겹** | **정차 3초 뒤** | **마지막 정차: 빗줄기·흐트러진 물결 자국** |
| ![](screens/09_stop_before.png) | ![](screens/10_stop_reveal.png) | ![](screens/11_stop_after.png) | ![](screens/12_last_stop_reveal.png) |

앱 창 영역(1682×1210dp)만 자른 이미지입니다. 차량 화면 전체(Pleos Connect v2.1.0 프로필 2560×1440)는 같은 이름의 `*_full.png`를 보세요.

- 주행 중(CarUxRestrictions `requiresDistractionOptimization`)에는 단색 배경과 추상 그래픽, 재생/일시정지 버튼만 보이고 **그래픽이 완전히 멈춥니다**(Android 차량 앱 품질 기준 SA-1). 음악 상태 변화는 정차하는 순간 한 번에 반영됩니다.
- 웰컴: 앱을 켜면 검은 화면에서 3초 동안 빛이 피어나고, 첫 다운비트에 부드러운 차임(코드에서 합성한 A·C·E 종소리)과 함께 음악이 시작됩니다. 주차 후에는 2초 동안 빛이 가라앉은 뒤 "1분 숨 고르기"를 제안합니다.
- "운전 중엔 귀로, 멈추면 눈으로": 주행하며 오늘의 앨범 커버를 계속 그려 두고(화면엔 반영 안 함), 정차하는 순간 지난 구간이 커버에 한 겹 추가되는 장면을 보여줍니다. 부드럽게 달린 구간은 둥근 원이 크게, 비 온 구간은 빗줄기, 급가속·급제동(테이프스톱)은 물결이 흐트러진 자국. 주차하면 그 커버가 그대로 마음 앨범의 새 앨범이 됩니다.
- 주차(P단) 후 운전석에만 사람이 있을 때 "1분 숨 고르기"를 제안합니다. 동승자가 있으면 제안하지 않습니다.
- 마음 앨범: 주행 한 번이 앨범 한 장이 됩니다. 정사각 커버 격자에 제목 한 줄, 커버를 누르면 큰 커버·날짜·회고 한 줄을 봅니다. 커버는 그날 운전의 부드러움·비·시간대로 그린 추상 그래픽이고, 회고 한 줄은 정차 중에만 남길 수 있습니다. 같은 날 같은 시간대 제목이 겹치면 "…빗속의 두 번째 길"처럼 구분합니다. 기록은 기기 안에만 저장합니다.
- 차량 화면 기준: 터치 영역 76dp 이상, 본문 24sp·제목 32sp 이상.

## 2026-11-13 — 음성 안내 · 연주 기록(이어진 마디·판정·등급·배지) · demo:safe/rough

### 1. 에뮬레이터 TTS 확인 → 쓸 수 없음
- `TTS_SERVICE`로 등록된 서비스는 Pleos 시스템 앱 `ai.pleos.playground.caas`(4.2.10)의 SpeechService 하나뿐. Google TTS·Pico 등 일반 엔진 없음. `tts_default_synth`/`tts_default_locale` = null.
- 앱에서 `TextToSpeech(context)` → 엔진 목록 `[]`, 34ms 만에 onInit ERROR(-1). `<queries>`에 TTS_SERVICE를 넣고, 엔진 패키지를 직접 지정해도 ERROR → ko-KR 여부 확인 불가.
  SpeechService는 표준 TextToSpeechService가 아니라 Pleos TTS SDK(Coming Soon) 전용으로 보임. 확인 코드: `voice/TtsProbe.kt`(`--es debug tts:probe`).
- 결정(사용자): 미리 녹음한 OGG. 임시 음성은 eSpeak NG(`brew install espeak-ng`, ko 음성) → `tools/make_voice.py` → `voice_*.ogg` 110개(1.7MB, 48kHz 모노, −18 LUFS, oggenc q4).
  {등급}{점수}는 "오늘의 연주는 S 등급이에요."(4개) + "N 점."(0~100, 101개)을 이어 붙인다. 같은 이름으로 덮어쓰면 목소리가 바뀜.

### 2. 사용자 결정(CLAUDE.md 6절 갱신)
- 게임 요소는 기능은 넣고 용어만 순화(이어진 마디·부드러운 출발/차선 변경/정지·흔들림·완주하지 못했어요). 등급·점은 주차 후 화면에만.
- '같은 구간 비교'는 위치 없이 '비슷한 주행'(움직인 시간 ±35%, 정차 횟수 ±2, 같은 시간대 우선).
- 등급 기준은 하모니 지수의 감점 값을 그대로 쓰되 시간 보상(초당 +0.8)은 뺌 → 점수 = 100 × 완주 악기/6 − (급가속 10·급제동 15·급조향 8) + 판정 2점(최대 5회). S 90 / A 80 / B 65 / C 50, 미만 = 완주 못함.
  '곡 완성도' = 마지막으로 움직이던 순간 연주 중이던 악기 수 / 6.

### 3. 구조
- `play/PlayTracker`(순수): 출발~주차 동안 상태·이벤트·지금 악기 수를 받아 `PlayEvent`(Combo/Perfect/Miss)와 주차 시 `DriveResult`.
  - 이어진 마디 = 마지막 흔들림(또는 출발) 이후 지난 마디 수(시간 기준, 신호 대기 중에도 셈). 16마디마다 필인, 32 = 합류, 64 = 클라이맥스.
  - 부드러운 출발: Departed 후 5초 동안 |a| ≤ 0.15g(1.471). 깔끔한 차선 변경: LaneChange 시 깜빡이 켠 지 ≥ 3초 + 그사이 급조향 없음(엔진은 TurnSignalOff 다음에 LaneChange를 냄 — 순서 주의).
    부드러운 정지: 감속이 이어지는 동안 최대 감속 ≤ 0.2g(1.961)로 0.3 m/s 아래까지(엔진 Stopped의 2초를 기다리지 않음 → 마무리 화음이 멈추는 순간 나옴).
  - 안전 거리(해금용): 흔들림이 있으면 직전 판정 이후 구간의 거리를 버린다.
- `play/PlayProfile`(순수) + `PlayStore`(filesDir/play.json): 배지 6종, 이번 주(월요일 시작) 최고, 7일 연속(흔들림 0 + 완주한 날), 비교 문장, 해금 진행도(시티팝 300km·재즈 1,000km, 팩은 "준비 중").
- 음악(`PerformanceLayer`): `PerfFill`(마지막 박 16분음표 롤 + 스웰 → 다운비트 심벌), `PerfBed(1)`(박마다 로즈 아르페지오 합류), `PerfBed(2)`(8분음표 2옥타브 아르페지오 + 다운비트 화음 + 종),
  `PerfReward`(DROP = 다음 박 근음 붐 + 근음·5도·옥타브 + 심벌 / SWELL = 박 경계로 끝나는 스웰 + 심벌 / CADENCE = 다음 8분음표 근음 + 코드 4음). 음높이 있는 소리는 울리는 순간의 코드에서 고름(테스트).
  테이프스톱(silence)이면 예약·합류가 모두 꺼짐. 보이스 상한 12 → 24.
- 음성(`voice/`, `LoopMixer`): `Speak(clips)` → 음성 큐(문장 사이 220ms). 음성 동안 음악 ×0.2512(−12dB, 80ms), 끝나면 1초 선형 복귀. 음성은 마스터·필터·테이프스톱 영향 없음.
  같은 AudioTrack(미디어 포커스 그대로)으로 나가므로 내비 안내 덕킹·포커스 정책은 기존과 같고, 시연 녹음(WavTap)에도 그대로 담김. 일시정지 중에는 말하지 않음.
  `VoicePolicy`: 주행 중(출발~P단)은 Stop/Resume만, 같은 문장 60초 간격. 결과 음성은 주차 시 미리 디코딩(처음엔 결과 화면보다 3.4초 늦었음 → 해결).
- 화면: 주차 → 한 겹(4초) → 굿바이(2초) → **오늘의 연주**(결과) → 확인 → 숨 고르기 제안/앨범. 무드 화면 위 가운데 **연주 기록** 버튼(탭 4개). 설정에 음성 안내 켜기/끄기.
  주행 중 금지 화면은 `ui/ScreenPolicy`(테스트).
- 데모: `DriveScenario.SAFE`(208초, 판정 6·흔들림 0·64마디 클라이맥스 → 100점 S), `ROUGH`(60초, 흔들림 → 완주 못함). 인텐트 `demo:safe`, `demo:rough`, `seed:play`, `reset:play`, `voice:on|off`, `screen:result`, `screen:records[:n]`, `panel:on|off`(세로 1:1.6 미리보기), `bars:off`(효과 없음 — 아래).

### 4. 확인
- 단위 테스트 **121/121**(추가 25: 이어진 마디 증가·초기화·이정표, 판정 경계 3종(1.471/1.481, 3000/2999ms, 1.961/1.971), 점수·등급 경계, 속도 무관(40 vs 110km/h 같은 점), 시간 무관(60초 vs 600초 정속 같은 점),
  흔들림 감점 속도 무관, demo:safe/rough 결과, 배지 6종·주간 최고·비교 문장·해금, 화면 정책, 음성 정책·60초, 보상음 박자·코드톤, 음성 덕킹 −12dB·1초 복귀).
- 에뮬레이터(swiftshader) demo:rough: 흔들림 7번(실시간 10Hz에서는 3.7 m/s² 가속이 2초 쿨다운 뒤 한 번 더 잡힘), "연주가 멈췄어요" 1번(나머지 60초 규칙으로 생략), "다시 연주를 시작할게요" 1번, 완주 못함 + 음성.
- 에뮬레이터 demo:safe: 판정 START·CHANGE·STOP 각 2, 이어진 마디 16/32 합류/48/64 클라이맥스, 최대 74마디, 흔들림 0, 6/6, 100점 S. 결과 음성 [S등급·100점] → [이번 주 최고] → [새 배지](첫 S등급). 오류 로그 0.
- Pleos Connect는 일반 앱을 (853,90)-(2535,1300) 멀티 윈도우에 고정 → 몰입 모드(`bars:off`)·`--windowingMode 1` 모두 무시됨. 2560×1440 전체를 앱으로 채울 수 없어 v4 캡처는 앱 창(1682×1210)과 원본 전체(full/)를 함께 저장.

## 2026-11-13 (오후) — 곡 단위 + 라이프 게이지 · 결과 화면 정리 · NaviHelper/Pleos TTS

### 1. 확인(구현 전)
- 의존성: `ai.pleos.playground:NaviHelper:3.0.5`(+ navi-data 3.0.4, navi-aidl 3.0.1), `ai.pleos.playground:TextToSpeech:3.3.1` — Pleos Nexus에서 인증 없이 받아짐.
  권한 `pleos.car.permission.NAVI_ROUTE`·`NAVI_ROUTE_SEARCH`(정의: navi 서비스)·`TTS_SERVICE`(정의: ai.pleos.playground.caas) 모두 **normal**.
- NaviHelper 데이터 클래스(javap):
  - TBTInfo: `type: TurnType`(LEFT/RIGHT/SHARP/KEEP/UTURN/IC·고가·지하차도·로터리/ARRIVE 등 43종), `distance: Double`(m), `description`
  - RouteStateInfo: `termsAgreement`, `naviMode`(NOT_WORKING/SAFE_GUIDING/ROUTE_GUIDING/SIMULATION_GUIDING), `isRouting`, `routeOption`(…/SCHOOL_ZONE 우회), 충전 예상, `trafficStatus`
  - DestinationInfo: 좌표, `name`, `address`, `remainDistance`, `remainTime`, `tollFare`, POI / RouteStartInfo: duration·distance·tollFare·name / DestinationArrivedInfo: duration·distance·name·address
  - **제한속도·보호구역·단속카메라 있음**: 리스너 `onCurrentRoadSpeedLimit(int)`, `CameraAlert`(type: SPEED_CAMERA_FIX/…/SPEED_CAMERA_SCHOOL_ZONE, `speedLimit`, `remainDistance`, `isOverSpeed`, 구간단속 평균), `CautionPoint`(type: SCHOOL_ZONE·SPEED_BUMP 등 23종, `remainDistance`)
  - 리스너 26개(onRouteStarted/Ended, onDestinationArrived, onTBTInfo, onNavigationStatus, onDrivingInfo, onCameraAlert, onCautionPointInfo …). 공개 메서드는 initialize/release/add·removeListener/requestRoute/cancelRoute/경유지/get…Info/requestNavigationScreen/requestTrip — **모의 주행을 켜는 API는 없음**.
- 에뮬레이터 실측(`probe/` 확인용 앱):
  - 초기화 직후: onCurrentLocationInfo(37.4127, 127.0947, 수정구 시흥동), onRouteStateInfo(SAFE_GUIDING), onTBTInfo([]), onCurrentRoadSpeedLimit 0(1초마다)
  - requestRoute(판교역, 3.1km) → 6초 뒤 onRouteStarted(780초, 3100m), **onRouteEnded(시작 직후 한 번 — 도착 신호로 못 씀)**, onNavigationStatus ROUTE_DRIVE, onRouteStateInfo(ROUTE_GUIDING), onTBTInfo 7개(첫 회전 LEFT 80m), onDrivingInfo
  - SIMULATION_GUIDING: 공개 API 없음, 내비 설정에도 항목 없음. `adb emu geo fix`로 위치를 옮겨도 내비 엔진 map-matching 위치가 그대로(엔진이 에뮬레이터 GPS를 쓰지 않음). Virtual Console에도 위치 항목 없음 → 회전 거리 감소·도착 이벤트는 에뮬레이터에서 못 받음.
- Pleos TTS(Mode.ON_DEVICE — 사양의 "GleoTtsEngine" 클래스는 SDK에 없음): initialize 96ms → speak("다시 시작할게요. 조금만 부드럽게요.") → onReady → onStart → onDone 3.6초. 귀로 듣지는 않음(재생 시간으로 판단).
  주의: `addEventListener`를 initialize 성공 전에 부르면 SDK 내부 lateinit 예외.

### 2. 곡 + 라이프 게이지(`play/PlayTracker`, `play/Conductor`)
- 곡 64마디, 시작 악기 4(카운트인 재시작은 3), 최대 6. 흔들림 −1(급제동 −2, 깜빡이 없는 회전·제한속도 5초 초과 −1), 4마디 연속 +1, 판정 +1. 출발 후 8마디 워밍업(1 밑으로 안 내려감).
- 악기 1 = 위험(Muffle 1, 약 900Hz 로우패스) + "조금만 부드럽게요"(곡당 1회). 0 = 정지: DSP 테이프스톱 → keys+synth만, 500Hz·×0.45(Muffle 2) + "연주가 멈췄어요. 부드럽게 달리면 다시 시작할게요."
  재시작: 흔들림 없이 15초 주행 또는 정차 후 출발 → "다시 시작할게요" → `RestartSong(countIn)`: 다음 마디에 4박 스틱 → 그다음 마디 머리에서 **루프를 처음부터**(믹서 readPos=0, 연주 레이어의 코드 기준 `loopOrigin`도 같이) → 악기 3개. 그 순간까지 레이어 변경을 미룬다.
- 곡 완주: 마무리 화음 + 다음 마디 머리에서 처음부터(심벌), 악기 4개. 주차할 때 연주 중이던 곡은 세지 않음. 미완주한 시도는 곡별 결과에 '미완주'로 남는다.
- 연출(`MusicDirector.gauge`): 레이어 = keys → synth → drums → bass → perc → guitar 앞에서 게이지 수(신호 대기 브레이크다운은 그대로). 흔들림으로 빠질 때는 다음 박에 1박 페이드. 급가감속 = 로우패스 딥, 급조향 = 스크래치(테이프스톱은 정지 때만).
- 곡 점수 = 평균 악기 비율×70 + 곡 안 최장 이어진 마디/64×20 + 판정×2(≤10). S90/A80/B70/C 완주.
- 화면: LIVE 빛 원 밝기 ×(0.62+0.38·악기/6), 크기 ×(0.86+0.14·악기/6), 1.2초 스무딩, 정지 = 0(어두움) → 카운트인 뒤 곡이 열리면 다시. 줄이기만 하므로 광과민성 상한과 무관.
- 결과(P): 커버+최고 등급+제목 / 숫자 카드(완주 x/y곡·최장 이어진 마디·흔들림) / 곡별 등급 칩(S·A 청록, B·C 회색, 미완주 붉은 테두리) / 새 배지. 남색·청록 한 색. 패널(폭<높이)은 세로.
- 기록 형식 v2(곡별). v1 기록은 읽지 않음(seed:play로 다시).

### 3. 내비·음성
- `signal/PleosNaviFeed`: onRouteStarted → RouteStarted, onTBTInfo 첫 항목 → NextTurn(LEFT/RIGHT/OFF, m), onDrivingInfo → Remaining, onDestinationArrived → Arrived, onCurrentRoadSpeedLimit·CameraAlert(SCHOOL_ZONE)·CautionPoint(SCHOOL_ZONE) → SpeedLimit. `SignalSample.Navi` → 엔진이 `DrivingEvent.Navi`로 넘김.
- 규칙: 안내 시작 → 카운트인 → 첫 곡 / NextTurn ≤300m에서 준비 → 그 방향 깜빡이 + 급조향 없음 = 깔끔한 회전, 깜빡이 없거나 반대 = 흔들림(다음 회전으로 넘어갈 때 판정; 그 깜빡이는 차선 변경 판정에서 뺌) /
  보호구역 진입~해제 동안 초과 없음 = 깔끔한 구간 통과 / 초과 중 경고(Muffle 1), 5초마다 −1 / 1km 안 = 클라이맥스(PerfBed 2) / 도착 = 16마디 이상이면 그 곡 완주로 마무리, 이후 곡 전환 없음.
- `voice/PleosVoice`: 한 문장씩, onStart~onDone 동안 `VoiceDuck`(−12dB, 1초 복귀). 초기화 실패·onError → 녹음 음성. 녹화 때는 `voice:ogg`(TTS 소리는 앱 믹스 밖).
- 테스트 **130/130**(추가: 게이지 증감·워밍업·위험 1회·정지 후 15초/흔들림 재카운트/정차 후 출발·곡 전환·점수 경계·속도 무관·시간 무관·회전·구간·안내 시작/도착·demo:safe(S,A + 내비)·demo:rough(미완주→C)).

## 2026-11-13 (저녁) — 노을 팔레트 · FAILED 칩 · 안내문 · 웰니스 요소 제거

- 노을(EVENING) 팔레트: 보라→주황 그라데이션 → **남색 중심**(위 #0E1A3A → 아래 #1F2F5C), 주황 #C9805A는 지평선(바닥 빛 띠) 바로 위 얇은 띠로만.
  셰이더에 `band`(rgb·두께) uniform 추가(다른 시간대는 두께 0). 보조 빛 색은 옅은 남청 #8C9BD6. 앨범 커버 노을 바탕(Canvas 대체)도 #1F2F5C.
- 재생/일시정지 버튼: 뒤 빛이 그대로 비치던 유리 → 남색 반투명(#0E1A3A, 알파 150) 위에 유리 테두리. 모든 시간대 공통.
- 결과 화면: 실패한 곡 칩 "미완주" → **"FAILED"**(사용자 결정, 붉은 테두리 유지). 상단 문구는 그대로 부드럽게.
- 시연용 데이터 안내문: "보호구역·회전·도착은 시연용 경로 데이터예요. 실차에서는 Pleos 내비 값을 씁니다."(2줄 이내). 세로 패널은 버튼 바로 위(스크롤 밖), 가로는 오른쪽 아래.
- **주차 후 1분 호흡 완전 제거**(사용자 결정): 제안 카드·BreathView·BreathCoach·문구·테스트·README 언급·디버그 명령(screen:park/breath) 삭제. 주차하면 바로 '오늘의 연주' → 확인 → 드라이브 앨범.
- **마음수련 성격 요소 제거**(사용자 결정 — 기획을 '안전 운전 = 연주'에 집중): "마음 앨범" → "드라이브 앨범", 앨범 상세의 회고 한 줄 입력 삭제(저장 형식의 reflection 필드는 옛 기록 호환용으로만 남김), 예시 앨범의 회고 문장 삭제,
  CLAUDE.md의 '주차 후 1분'·'음성 회고'·'웰니스 표현' 항목 정리. 정차 중 빛이 박에 맞춰 3% 맥박치는 화면 효과는 음악 반응이라 유지(문구에서 '숨 쉬듯' 표현만 뺌).
  문구 검사 테스트에 "마음·호흡·숨 고르·명상·회고·힐링" 추가.
- 테스트 128/128(호흡 테스트 2개 삭제, 노을 팔레트 테스트 갱신).

## 2026-11-14 주행 화면 물결 교체 · 듣기 모드 · 창 정책

### 물결(빛 방울 대체)
- 사용자 결정: 빛 방울·점 입자·원형 링·반짝임 전부 제거 → 차량 앰비언트 라이트와 이어지는 **흐르는 그라데이션 물결**. `ui/LiveMotion`·`ui/DriveMotion`(및 그 테스트)을 지우고 `ui/WaveMotion`(순수) + `ui/light/LightShader`(AGSL 전면 교체)로.
- 물결은 지금 연주되는 음악: `tools/make_wave_score.py`가 앱이 재생하는 스템(8마디, 1,024,000프레임)을 1/32박(1000프레임 ≈ 20.8ms) 칸으로 분석 → `res/raw/wave_score.txt`
  (악기별 RMS, 드럼 저역<150Hz 온셋 = 킥 / 1.5~6kHz 온셋 중 킥과 겹치지 않는 것 = 스네어, 퍼커션 >5kHz 온셋 = 하이햇, 베이스·기타 음 높이 = YIN).
  킥은 8분음표 격자 ±1칸에서 검출(첫 마디: 0, 0.5, 1.5, 2, 2.5박), 스네어 1·2·3박, 기타 음 높이 A3~E4, 베이스 G1~A2.
- 화면 동기: `DriveController.sound()`가 들리는 격자 위치(AudioTrack 타임스탬프 − 출력 지연) → (위치 − 곡 시작 `LoopMixer.originAt`) mod 루프 → 악보 칸.
  보상·필인·카운트인 스틱·다음 곡 심벌은 `PerformanceLayer.cues`(실제로 울린 격자 위치)를 100ms 미리 읽어 정점을 소리에 맞춘다. 테이프 스톱은 `LoopMixer.tapeStartMark/ResumeMark`.
- 움직임(사용자 피드백 "파동이 뚝딱거림" 반영): 모든 값 임계감쇠 스프링(`Spring`, dt 기반). 파동 포락선 = 정점 80ms 전 smoothstep 상승 → (1+t/τ)e^(−t/τ), τ=170ms(10%까지 약 0.66초).
  진행파: 시작점에서 1.3(화면 높이/초)로 좌우로(가운데 → 끝 약 0.55초), 물결마다 45ms씩 늦게(드럼 → 베이스 → 코드 → 하이햇 → 패드), 겹치면 합 후 부드러운 상한 s/(1+|s|/0.012).
  킥 진폭 0.006, 스네어 0.003, 넓은 파동 0.010(화면 높이 비율). Compose 프레임 루프 30 → 60fps.
- 밝기: 면 밝기는 물결 높이와 무관하게 정규화(물결이 오르내려도 평균 밝기 그대로). 악기가 들어오고 빠질 때 면 밝기 예산 초당 0.06, 어두워짐 포함 0.075(luma 모형).
- 측정
  - 단위 테스트 `WaveMotionTest`(60fps 600초 무작위 상황): 한 프레임 최대 이동 화면 높이의 0.84%(FAILED 때 맨 위 물결이 평평한 선으로 약 2초에 걸쳐 내려옴), 프레임 사이 속도 변화 최대 0.28%(넓은 파동의 80ms 상승).
  - 기기 로그(wavelog:on, 확인 영상 2개): 한 프레임 최대 이동 0.0075(≈9px), 속도 변화 최대 0.0013(≈1.5px), 평균 56~58fps, 40ms 넘게 밀린 프레임 6·11개(최대 285ms·앱 시작 때 3.8초는 녹화 구간 밖).
  - 녹화 영상 실제 밝기(`tools/check_luma.py`, 편집 페이드 제외): 1초 변화 최대 6.9%·5.7%, 1% 넘는 밝기 왕복 1초에 최대 1회.
- 처음 악기 4개의 면이 다 차오르는 데 약 10초(밝기 제한). 마루 빛(가장자리)은 0.6초 안에 먼저 보인다.
- 에뮬레이터 실차 신호원은 정차 중 신호를 보내지 않아 출발 전에는 믹서에 켜진 악기가 없다(기존 동작). 화면은 그때 음악이 없을 때와 같은 잔잔한 패드·코드 물결.

### 듣기 모드
- `play/ListenMode`: `AppMode`, `ModeSwitch`(주행 중 거부, SharedPreferences "mode"에 기억), `ListenConductor`(게이지 6 고정, 급가속·급제동·급조향 이벤트를 연출에서 뺌 → 악기가 빠지지 않고 테이프스톱·먹먹함·판정·음성·결과 없음).
- 기기 확인(demo:rough, 듣기): 급가속·급조향·급제동이 이어져도 로그의 레이어가 6개 그대로.
- `music/MusicPack`: 팩(이름·장르·곡 목록), 곡(제목·BPM·반복 마디·악기→스템 파일·물결 악보). 기본 팩 1개(밤길 로파이). 잠긴 장르 = `GenrePack`(시티팝 300km, 재즈 1000km, 흔들림 없던 거리).

### 창 정책(Pleos Window Policy: Connect-S 1:1, Connect-W 2:1·1:2, 가로/세로 전환)
- `resizeableActivity=true` + `configChanges`(screenSize 등) → 창 크기가 바뀌어도 액티비티를 다시 만들지 않음. 루트 크기가 바뀌면 `WindowShape.narrow`(가로:세로 < 1)로 세로 패널 레이아웃을 고르고 정차 중 화면을 다시 그림.
- 에뮬레이터(Pleos Connect API 34): 홈(`ai.umos.homescreen` TaskSlotLayout)이 앱을 (853,90)-(2535,1300) **한 칸**에만 띄운다. 내비(`ai.umos.maps.android.navigation.app`)를 열면 같은 칸이 내비로 바뀌고, 오른쪽 핸들(⋮)은 탭·끌기에 반응하지 않았다 → 내비와 나란히 보는 화면은 재현 불가.
  `am task resize`로 앱 창을 841×1210(1:1 상당), 561×1210(1:2 상당)으로 바꿔 레이아웃 전환·음악 연속(이어진 마디가 계속 증가)만 확인 → `docs/screens/v4/full/12_window_1to1_resize.png`, `13_window_1to2_resize.png`.
  (먼저 configChanges 없이 해 보니 창 크기를 바꾸는 순간 액티비티가 다시 만들어져 음악이 멈췄다.)
- 내비 앱 화면: TBT·경로선은 그려지지만 지도 바탕이 비어 있음 → 에뮬레이터 내비의 지도 타일 미표시(창 정책 문제 아님).

### 재촬영(2026-11-14)
- 시간대 지정: demo_safe = 노을(daypart:EVENING), demo_rough = 밤(NIGHT). 캡처 01·04·05 노을, 02 밤, 03 아침+비, 06~09 밤. 촬영용 `daypart:`는 결과·앨범 커버 색에도 적용(`LightParams.coverDayOverride`, 디버그 명령). 앨범 제목(요일·시간대 문구)은 실제 주행 시각을 따른다.
- 첫 재촬영에서 밤 커버의 물결이 거의 보이지 않아 커버를 손봄: 패드 바탕 물결을 늘 깔고, 구간 물결은 코드·베이스·드럼·하이햇 자리, 포인트 색 멜로디 리본 추가, 커버 면 밝기 ×1.7.
- 04(신호 대기)는 정차 직후면 빠진 드럼·베이스 물결 면이 아직 잦아드는 중(밝기 제한으로 몇 초) → 촬영 시점을 74초 → 82초.
- demo_safe 편집 첫 구간을 0초 → 3초부터(물결이 차오르기 전 빈 화면을 짧게).
- 연주 다시 듣기(선택)는 사양을 받지 못해 넣지 않음.

## 2026-11-14 결과·기록 화면 리디자인(docs/design/result_ui_brief.md)
- 피드백 "PPT·설정 화면 같다" → 리듬게임 결과 화면 패턴(레퍼런스 tina-otoge.github.io/result-screens 확인: 상태 문구 → 정리 화면, 세부는 따로). bugnet 글 주소는 블로그 첫 페이지로 연결돼 원문 확인 못 함.
- 결과: 화면 정가운데 상태 문구(CLEARED / RETRY CLEAR / FAILED, 커버·카드는 숨긴 채) → 0.8초 왼쪽 위 머리 자리로 → 0.9초 커버·숫자 카드 3개 카운트업(96sp, 오른쪽 폭·높이를 꽉) → 1.7초 등급 도장(Overshoot, S = `MoodView.celebrate` 물결 한 번) → 2.0초 결과 음성. 누르면 끝 모습으로. MISS는 3번 이상만 붉게.
  곡별 가로 카드(곡 번호·등급 112sp·곡 점, FAILED 붉은 테두리, RETRY), 새 배지 메달 카드, "자세히"(종류별 PERFECT·MISS의 뜻). 바탕 = 물결이 비치는 남색 막(등급이 높을수록 투명).
- 기록 4탭: 이번 주 최고 기록 히어로 카드 + 최근 연주 커버 카드(FAILED 붉은 테두리), 오늘 VS 지난 비슷한 주행, 친구 단상(예시), 메달 격자(`ResultArt.MedalView`)·장르 카드(`LockView`). `PlayProfile.badgeProgress`·`finishStreakDays`.
- 결과의 시작 시각과 앨범 ID는 다른 시계(몇 초 차이) → 10분 안에서 가장 가까운 앨범을 커버·제목으로.
- 글자 크기는 차량 기준(본문 24sp·제목 32sp 이상)을 지킴(브리프의 보조 13~14sp 대신). "다시 듣기" 버튼은 기능(사양) 없음으로 뺌.

### 최종 재촬영(2026-11-14)
- 캡처: 01 노을, 02 밤, 03 아침+비, 04 노을 신호 대기, 05 결과(S, 노을), 06 결과(RETRY CLEAR, 밤), 07a·07b·07·08 기록, 09 앨범(밤), 11 듣기 모드 곡 목록, panel 01~06.
  - demo:safe의 신호 대기는 72.0초 정차 → 79.2초 출발. 04는 빠진 악기 물결이 잦아든 뒤(패널 78.0초, 앱 창 78.9초)에 찍음(82초는 이미 출발).
  - 07b: 가장 최근 주행(demo:rough)에는 비슷한 주행이 없어서, 비교 탭은 비슷한 주행이 있는 가장 최근 주행을 고르게 함.
- 영상: demo_safe(노을, 85초), demo_rough(밤, 42초). 실제 화면 밝기(`check_luma.py`, 편집 이음매 제외): 주행 구간 1초 변화 최대 6.4%·5.1%, 1% 넘는 왕복 1초에 최대 1회.
  demo_safe에서 주차 후 결과 화면으로 넘어가는 순간(68초 이후)은 화면 전환이라 36% 변화 — 주차 중이라 주행 중 기준 대상 아님.

## 녹음 음성 교체(2026-11-15)

- 시연 영상의 음성이 기계음처럼 들려(eSpeak NG) `voice_*.ogg` 111개를 macOS 기본 한국어 음성 **Yuna**(`say -v Yuna -r 185`)로 다시 만들었다. 파일 이름·개수·형식(48kHz 모노 Vorbis, oggenc q4)은 그대로라 코드 수정 없음.
- 처리: 앞뒤 무음 정리(−50dB) → highpass 80Hz → loudnorm −16 LUFS(TP −1.5) → 끝 0.08초 여백. 점검: voice_danger/stop/resume/grade_s/score_99/weekly_best/badge/nosong 모두 −16.0~−16.3 LUFS, 0.35초 넘는 중간 무음 없음.
- 라이선스: macOS 사용권은 시스템 음성 출력을 개인·비상업 용도로만 허락한다. 사용자가 알고 APK·저장소까지 교체하기로 결정. 정식 출시 전 상업 이용 가능한 음성으로 바꾼다(`ENGINE=espeak`로 이전 음성 재생성 가능).
- 시연 영상 demo_safe(노을)·demo_rough(밤)만 같은 편집 구간으로 다시 녹화(캡처 이미지는 그대로).
