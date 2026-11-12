# 차곡 · 폰 앱

차곡의 폰 앱(P01~P16)입니다. 여행 설정 → 추천 → 일정 → 차로 보내기, 도착 후 사진 → 기록 → 요약 흐름을 샘플 데이터로 보여 줍니다. 백엔드·실제 API는 아직 연결하지 않았습니다.

화면 이동은 하단 가운데 `+` 하나로 합니다. 누르면 새 여행 · 추천 장소 · 지금 기록 · 기록 보기 · 모아보기가 부채꼴로 펼쳐집니다. MY는 홈 오른쪽 위, 홈이 아닌 모든 화면은 왼쪽 위에 `‹ 이전`이 있습니다. 디자인은 pleos 차곡 패널(`mockup/pleos-screen.html`의 `#chagok`)에 맞췄습니다: 연보라→크림 배경, 테두리 없는 둥근 카드, 짙은 초록 알약 버튼, 연두 태그, 시간·점·장소 세로선 일정 카드, 파스텔 구체(`CompanionOrbView.kt`, pleos에서 복사). MY에는 "지난 여행 돌아보기"(P16, 여행마다 날짜·지역·지도·장소·사진을 옆으로 넘겨 보기)와 로그인/로그아웃(데모, 계정 서버 연동 전)이 있습니다. 위치 권한은 기록 탭에서 "지금 이 장소 기록" / "위치 찾기"를 누를 때만 요청합니다.

- 차량(Pleos) 앱: https://github.com/Vroom-vroom-vrooooom/pleos
- 두 앱을 잇는 메시지: [CONTRACT.md](CONTRACT.md) · `domain/TripContract.kt`
- 백엔드가 해야 할 것: [BACKEND.md](BACKEND.md)
- 원본 18개 화면 대응표: [docs/wireframe-map.md](docs/wireframe-map.md)
- 폰 화면 디자인 기준: [차곡 와이어프레임 · 단계별 플로우](https://claude.ai/artifact/N4BmsuVCA7ZDY79QVjjMvP) 캔버스의 폰 1~13
- 웹 목업(폰·차량 함께 보기): [mockup/README.md](mockup/README.md) · `node mockup/wireframe-server.cjs`

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
