# 차곡 · 폰 앱

차곡의 폰 앱(P01~P13)입니다. 여행 설정 → 추천 → 일정 → 차로 보내기, 도착 후 사진 → 기록 → 요약 흐름을 샘플 데이터로 보여 줍니다. 백엔드·실제 API는 아직 연결하지 않았습니다.

- 차량(Pleos) 앱: https://github.com/Vroom-vroom-vrooooom/pleos
- 두 앱을 잇는 메시지: [CONTRACT.md](CONTRACT.md) · `domain/TripContract.kt`
- 백엔드가 해야 할 것: [BACKEND.md](BACKEND.md)
- 원본 18개 화면 대응표: [docs/wireframe-map.md](docs/wireframe-map.md)
- 웹 목업(폰·차량 함께 보기): [mockup/README.md](mockup/README.md) · `node mockup/wireframe-server.cjs`

## 실행

Android Studio로 이 폴더를 열거나 `gradlew.bat assembleDebug`(JDK 17, Android SDK 34)를 실행합니다. 아무 폰 에뮬레이터에서 실행하면 됩니다.

## 구조

```text
app/src/main/java/io/chagok/app/
  ui/PhoneActivity.kt        폰 13개 화면 · 이 레포에서 주로 고치는 파일
  ui/ScreenActivity.kt       공통 화면 부품 (pleos 레포와 같은 파일)
  domain/TripContract.kt     폰↔Pleos 메시지 (pleos 레포와 반드시 같은 파일)
  domain/Models.kt           화면 ID, 여행 설정, 장소, 연동 Port
  data/DemoTripRepository.kt 샘플 데이터 · 백엔드가 붙으면 여기를 교체
res/layout/activity_phone.xml, panel_map.xml, preview_home_content.xml
```

`ScreenActivity.kt`, `TripContract.kt`, `Models.kt`, `DemoTripRepository.kt`, `CONTRACT.md`는 두 레포에 똑같이 있습니다. 바꿀 때는 두 레포에 함께 반영합니다.
