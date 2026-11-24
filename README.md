# NailDeviceApp

Nail 디바이스와 BLE로 연결해 마이크와 IMU 데이터를 실시간으로 보고 CSV로 저장하는 Android 앱입니다.

## 빌드

- Android Studio로 이 폴더를 열고 ▶ Run을 누릅니다.
- 명령줄로 빌드하려면 `./gradlew assembleDebug`를 실행합니다. 결과물은 `app/build/outputs/apk/debug/app-debug.apk`에 생깁니다.

요구사항: JDK 17 이상, Android SDK 36, 폰은 Android 8.0 이상

## 사용법

1. Nail 보드 전원을 켭니다. 광고 이름은 `NailDevice`입니다.
2. 앱에서 스캔한 뒤 `NailDevice`를 눌러 연결합니다.
3. 녹화하면 `Download/NailDeviceApp/`에 CSV로 저장됩니다.

## 프로토콜

Nordic UART Service를 씁니다. 패킷 포맷과 명령은 `data/ble/NailProtocol.kt`에 있습니다.

- 가속도: `g = raw × 0.000488` (±16 g)
- 자이로: `dps = raw × 0.07` (±2000 dps)
