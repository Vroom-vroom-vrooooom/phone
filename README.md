# 차곡 · 폰 앱

차곡의 폰 앱(P01~P16)입니다. 여행 설정 → 추천 → 일정 → 차로 보내기, 도착 후 사진 → 기록 → 요약 흐름을 보여 줍니다. 로그인 전에는 샘플 데이터로, 로그인하면 차곡 백엔드와 연결됩니다(아래 백엔드 연결).

화면 이동은 하단 가운데 `+` 하나로 합니다. 누르면 새 여행 · 추천 장소 · 지금 기록 · 기록 보기 · 모아보기가 부채꼴로 펼쳐집니다. MY는 홈 오른쪽 위, 홈이 아닌 모든 화면은 왼쪽 위에 `‹ 이전`이 있습니다. 디자인은 pleos 차량 앱의 차곡 화면(`CarActivity` 시작 화면, `bg_chagok`·`bg_trip_card`·`bg_btn_deep/ghost`·`bg_tag`·`bg_place_chip`·`dot_route*`)에 맞췄습니다. 홈은 차량 출발 화면과 같은 구성(구체 → 인사 → 일정 카드 → 바꾸기/보내기 버튼)이고, 구체는 `CompanionOrbView.kt`를 pleos에서 복사해 씁니다. 작은 회색 글씨만 대비 4.5:1을 위해 차량의 `soft_ink`보다 진하게 둡니다. MY에는 "지난 여행 돌아보기"(P16, 여행마다 날짜·지역·지도·장소·사진을 옆으로 넘겨 보기)와 로그인/로그아웃(데모, 계정 서버 연동 전)이 있습니다. 위치 권한은 기록 탭에서 "지금 이 장소 기록" / "위치 찾기"를 누를 때만 요청합니다.

- 차량(Pleos) 앱: https://github.com/Vroom-vroom-vrooooom/pleos
- 두 앱을 잇는 메시지: [CONTRACT.md](CONTRACT.md) · `domain/TripContract.kt`
- 백엔드가 해야 할 것: [BACKEND.md](BACKEND.md)
- 원본 18개 화면 대응표: [docs/wireframe-map.md](docs/wireframe-map.md)
- 폰 화면 디자인 기준: [차곡 와이어프레임 · 단계별 플로우](https://claude.ai/artifact/N4BmsuVCA7ZDY79QVjjMvP) 캔버스의 폰 1~13
- 웹 목업(폰·차량 함께 보기): [mockup/README.md](mockup/README.md) · `node mockup/wireframe-server.cjs`

## 제출 정보

- **앱 이름:** 차곡 (폰 앱) · 팀 부릉부릉부르릉
- **한 줄 소개:** 폰에서 짠 여행 일정을 차(Pleos)로 보내면, 달리는 동안 차곡이 지나는 장소 이야기를 들려주고 다녀온 길을 기록으로 남겨 주는 여행 동반자 앱의 폰 쪽입니다.
- **APK 위치:** [`apk/부릉부릉부르릉_차곡_phone_release.apk`](apk/) (패키지 `io.chagok.app.phone`, 0.1.0, 디버그 키 서명)
- **함께 쓰는 앱:** 차량 앱 https://github.com/Vroom-vroom-vrooooom/pleos · 백엔드 https://github.com/Vroom-vroom-vrooooom/backend

### 실행 방법

1. 안드로이드 폰 에뮬레이터를 켭니다(아래 테스트 환경).
2. `adb install -r "apk/부릉부릉부르릉_차곡_phone_release.apk"`
3. 앱 목록에서 **차곡**을 엽니다. 로그인하지 않아도 샘플 데이터로 모든 화면(P01~P16)을 볼 수 있습니다.
4. 차와 연결해 보려면: MY → 로그인 → 차량 연결에서 8자리 번호를 받아 차량 앱 프로필 → 폰과 연결하기에 넣습니다(아래 [백엔드 연결](#백엔드-연결)).

소스에서 빌드: `gradlew.bat assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

### 테스트 환경·설정

| 항목 | 값 |
|---|---|
| 개발·빌드 | Windows 11, JDK 17, Android SDK 34 (compileSdk/targetSdk 34, minSdk 28) |
| 폰 에뮬레이터 | Android 14 (API 34) `google_apis` x86_64, Pixel 7 프로필 · 720 × 1600, 280dpi (AVD `chagok_phone`) |
| 차량 에뮬레이터 (연동 시험) | Pleos Connect 2.1.0 (API 34, x86_64) · 2560 × 1440 가로, 160dpi |
| 백엔드 | `https://chagok-api-om4h3skohq-du.a.run.app` (APK에 들어 있음, 따로 설정할 것 없음) |
| 위치 | 현재 위치 추천 시험: `adb emu geo fix 128.9470 37.7725` (안목 근처) |

## 백엔드 연결

서버: `https://chagok-api-om4h3skohq-du.a.run.app` (backend 레포, `app/build.gradle`의 `API_BASE_URL`). 연결 코드는 `data/ChagokApi.kt`입니다.

- MY → 로그인: 팀 관리자가 만든 이메일 계정으로 `POST /v1/auth/login`. 토큰은 이 앱의 private 설정에만 둡니다(출시 전 Keystore로 옮길 것). 초대 키·Claude 키는 앱에 넣지 않습니다.
- 차로 보내기(홈·P09): 로그인 상태면 여행 저장(`POST`/`PUT /v1/trips`) → 계정의 Pleos 기기 찾기(`GET /v1/devices`) → `POST /v1/trips/{id}/stage`. 큐 등록은 차량 화면 반영을 뜻하지 않습니다. 로그인 전에는 예전처럼 logcat 데모(`relay.sh`용)입니다.
- 기록(P12): 로그인 + 보낸 여행이 있으면 `GET /v1/trips/{id}/events`를 10초마다 가져옵니다. adb `--es chagok.event`도 그대로 받습니다.
- 공개 범위: 폰은 3단계, 서버는 `private`/`link` 2단계라 임시로 "나만"→`private`, 나머지→`link`로 보냅니다(팀 결정 필요).
- 차량 연결(MY): 로그인 상태에서 누르면 8자리 번호(`POST /v1/pairings`, 5분·1회용)가 나옵니다. 차곡 차량 앱 프로필 → 폰과 연결하기에 넣습니다. 로그인은 MY 위쪽 프로필 카드를 눌러서 합니다.
- 아직 연결 안 함: AI 추천·장소 추출·요약, 회원가입(관리자 전용).

## 실행

Android Studio로 이 폴더를 열거나 `gradlew.bat assembleDebug`(JDK 17, Android SDK 34)를 실행합니다. 아무 폰 에뮬레이터에서 실행하면 됩니다.

명령줄로만 준비할 때(Windows):

| 설치할 것 | 위치 |
|---|---|
| JDK 17 (`winget install EclipseAdoptium.Temurin.17.JDK`) | `C:\Program Files\Eclipse Adoptium\jdk-17…` · 빌드 전에 `JAVA_HOME`을 여기로 |
| Android SDK (command-line tools → `sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"`) | `%LOCALAPPDATA%\Android\Sdk` (Android Studio 기본 위치와 같음) |
| 에뮬레이터 (`sdkmanager "emulator" "system-images;android-34;google_apis;x86_64"`, `avdmanager create avd -n chagok_phone -d pixel_7 …`) | 같은 SDK 폴더 · AVD는 `%USERPROFILE%\.android\avd` |

에뮬레이터에서 현재 위치 추천을 시험하려면 `adb emu geo fix 128.9470 37.7725`(안목 근처, 경도 위도 순)로 위치를 정합니다.

`local.properties`(커밋하지 않음)에 `sdk.dir=C\:/Users/<사용자>/AppData/Local/Android/Sdk`를 적습니다.

## 구조

```text
app/src/main/java/io/chagok/app/
  ui/CompanionOrbView.kt     파스텔 구체 (pleos 레포에서 복사, 같은 모양 유지)
  ui/PhoneActivity.kt        폰 16개 화면(P14 MY, P15 한눈에 모아보기, P16 지난 여행 돌아보기 포함)과 화면 부품(카드·칩·세그먼트·탭) · DriveEvent 수신 · 이 레포에서 주로 고치는 파일
  ui/ScreenActivity.kt       공통 화면 부품 (pleos 레포와 같은 파일)
  domain/TripContract.kt     폰↔Pleos 메시지 (pleos 레포와 반드시 같은 파일)
  domain/Models.kt           화면 ID, 여행 설정, 장소, 연동 Port
  data/DemoTripRepository.kt 샘플 데이터 · 백엔드가 붙으면 여기를 교체
res/layout/activity_phone.xml(본문·하단 버튼·탭·+ 시트), panel_map.xml · values/colors.xml의 phone_* 색
```

`ScreenActivity.kt`, `TripContract.kt`, `Models.kt`, `DemoTripRepository.kt`, `CONTRACT.md`는 두 레포에 똑같이 있습니다. 바꿀 때는 두 레포에 함께 반영합니다.
