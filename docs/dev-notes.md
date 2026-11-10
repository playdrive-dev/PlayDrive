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
