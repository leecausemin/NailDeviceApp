# NailDeviceApp

Nail 디바이스(nRF52840 + LSM6DSL IMU + V2S200D PDM 마이크)와 BLE로 연결해
**마이크와 IMU 데이터를 실시간으로 보여주고 CSV로 저장하는 Android 앱**입니다.

- Kotlin + Jetpack Compose
- BLE: Nordic UART Service(NUS)
- 같은 앱에서 Dual ERM 보드(광고 이름 `ERM`)도 스캔됩니다.

> 이 앱은 Nail 펌웨어(광고 이름 `NailDevice`, SoftDevice S140 7.0.1)와 짝입니다.
> 보드에 다른 펌웨어(예: 예전 `HJ_V1`)가 올라가 있으면 패킷 포맷이 달라서 데이터가 표시되지 않습니다.

---

## 1. 준비물

| 항목 | 버전 / 비고 |
|---|---|
| Android Studio | 최신 안정판 권장. 번들 JDK(JBR)를 그대로 사용 |
| JDK | 17 이상 (Android Studio 번들 JBR이면 충분) |
| Android SDK | compileSdk / targetSdk **36**, minSdk **26** (Android 8.0) |
| Gradle | **8.13**. Wrapper가 자동으로 받으므로 따로 설치할 필요 없음 |
| AGP / Kotlin | 8.13.2 / 2.0.21 (`gradle/libs.versions.toml`) |
| 폰 | Android 8.0 이상, BLE 지원. 실측 기준 기기는 Galaxy A15 |

`local.properties`(SDK 경로)는 저장소에 없습니다. Android Studio로 처음 열면 자동으로 만들어지고,
명령줄로만 빌드한다면 아래처럼 직접 만들면 됩니다.

```properties
# Windows 예시 (역슬래시는 두 번)
sdk.dir=C\:\\Users\\<사용자>\\AppData\\Local\\Android\\Sdk
```

---

## 2. 빌드

### 방법 A. Android Studio (가장 쉬움)

1. `File > Open`에서 이 폴더(`NailDeviceApp`)를 엽니다.
2. Gradle Sync가 끝날 때까지 기다립니다. 처음에는 의존성을 받느라 몇 분 걸립니다.
3. 폰을 USB로 연결하고 USB 디버깅을 켭니다.
4. 상단 실행 대상에서 폰을 고르고 ▶ Run을 누릅니다.

### 방법 B. 명령줄

```bash
# macOS / Linux / Git Bash
./gradlew assembleDebug

# Windows PowerShell / cmd
gradlew.bat assembleDebug
```

결과물은 `app/build/outputs/apk/debug/app-debug.apk`에 생깁니다.

`JAVA_HOME`이 없다는 오류가 나면 Android Studio 번들 JDK를 지정하세요.

```bash
# Git Bash (Windows 기본 설치 경로)
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

### 단위 테스트

```bash
./gradlew testDebugUnitTest
```

패킷 디코딩(`NailProtocolTest`), 지연선(`DelayLineTest`), 리샘플러(`TickResamplerTest`)를 검사합니다.

---

## 3. 폰에 설치

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb`는 Android SDK의 `platform-tools` 폴더에 있습니다.
APK 파일을 폰에 직접 복사해서 설치해도 됩니다. 이때는 "출처를 알 수 없는 앱 설치"를 허용해야 합니다.

처음 실행할 때 **근처 기기(블루투스) 권한**을 허용해야 스캔이 됩니다.
Android 11 이하에서는 위치 권한도 필요합니다.

---

## 4. 사용법

1. Nail 보드 전원을 켭니다. 광고 이름은 `NailDevice`입니다.
2. 앱의 스캔 화면에서 `NailDevice`를 눌러 연결합니다.
3. 모니터 화면에서 마이크와 IMU 파형을 실시간으로 봅니다.
4. 녹화 버튼을 누르면 CSV로 저장됩니다. 저장 위치는 설정에서 바꿀 수 있습니다.
   - 기본 위치: 녹화 하나당 폴더 하나 → `Download/NailDeviceApp/nail_<날짜>/nail_<날짜>_mic.csv`, `..._imu.csv` 등
     (Downloads 폴더로 내보내기는 Android 10 이상에서만 됩니다)
   - PC로 가져오기: `adb pull /sdcard/Download/NailDeviceApp/<녹화 폴더>`

하드웨어 없이 화면만 확인하려면 `AppContainer.kt`의 `USE_MOCK_DEVICE`를 `true`로 바꾸고
빌드하세요. 가짜 신호로 동작합니다.

---

## 5. BLE 프로토콜 요약

자세한 구현은 `app/src/main/java/com/example/naildeviceapp/data/ble/NailProtocol.kt`를 보세요.

### GATT (Nordic UART Service)

| 역할 | UUID |
|---|---|
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| RX (앱 → 보드, Write) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| TX (보드 → 앱, Notify) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |

### 명령 (앱 → 보드)

| 바이트 | 의미 |
|---|---|
| `s` | 스트리밍 시작 |
| `e` | 스트리밍 정지 |
| `w` | 센서 WHO_AM_I 스캔 (텍스트로 응답) |
| `0xA0 <kHz>` | 마이크 샘플레이트: 16 / 8 / 4 / 2 / 1 kHz |
| `0xA1 <Hz uint16 LE>` | IMU ODR: 416 / 833 / 1666 / 3333 / 6664 Hz 등 (기본 416 Hz) |
| `0xA2 <profile>` | 전송 프로파일 0~5 (아래 표) |
| `0xA3 <ms uint16 LE>` | 지연 예산 |

### 패킷 (보드 → 앱)

기본 포맷 (프로파일 0)

| 오프셋 | 내용 |
|---|---|
| `[0]` | 매직: `0xA5` 마이크 PCM16, `0x5A` IMU raw, `0x01` 텍스트/상태 |
| `[1..4]` | 첫 샘플의 절대 번호 (uint32 LE). 번호가 건너뛰면 그만큼 유실된 것 |
| `[5]` | 샘플 개수 (uint8) |
| `[6..]` | 마이크: int16 LE / IMU: 샘플당 int16 LE × 6 (ax, ay, az, gx, gy, gz) |

압축 포맷 (프로파일 1 이상)

| 오프셋 | 내용 |
|---|---|
| `[0]` | 매직: `0xA6` 마이크 IMA ADPCM 4비트, `0xA7` 마이크 μ-law 8비트, `0x5B` IMU 12비트 팩 |
| `[1..4]` | 첫 샘플의 절대 번호 (uint32 LE) |
| `[5..6]` | 샘플 개수 (uint16 LE) |
| `[7]` | IMU: 0 = 6축, 1 = 가속도만 / ADPCM: step index |
| `[8..]` | 데이터 (ADPCM은 `[8..9]` predictor int16 LE, 이후 4비트 코드, 하위 니블 먼저) |

| 프로파일 | 이름 | 비고 |
|---|---|---|
| 0 | RAW | 무손실 |
| 1 | MIC_ADPCM | 마이크만 4비트 |
| 2 | IMU_PACK | IMU만 12비트 |
| 3 | BOTH | 마이크 4비트 + IMU 12비트 |
| 4 | BOTH_ACCEL | 3번에서 자이로 제외 |
| 5 | BOTH_MIC8 | 마이크 8비트 μ-law + IMU 12비트 |

### 단위 변환

| 값 | 변환 |
|---|---|
| 가속도 (±16 g) | `g = raw × 0.000488` |
| 자이로 (±2000 dps) | `dps = raw × 0.07` |
| 마이크 | `raw / 32768` (−1 ~ 1) |

12비트 팩(`0x5B`)도 복원하면 원시 LSB와 같은 축척이라 위 계수를 그대로 씁니다.

---

## 6. 알아둘 점

- **처리량은 폰 기준입니다.** 마이크 16 kHz와 IMU를 함께 받으려면 BLE 2M PHY에서 약 300 kbps가 필요합니다.
  Galaxy A15에서는 유실 0%였지만 Windows PC BLE는 105~155 kbps라 크게 모자랍니다.
- IMU를 1.6 kHz보다 높이면 센서가 아니라 BLE 링크가 한계입니다. 이때는 압축 프로파일(3 또는 5)을 쓰세요.
- 펌웨어 쪽 소스와 빌드 방법은 별도 펌웨어 저장소의 `nail_firmware/FLASH_MANUAL.txt`에 있습니다.

## 7. 코드 구조

```
app/src/main/java/com/example/naildeviceapp/
├── AppContainer.kt          # 의존성 조립, USE_MOCK_DEVICE 스위치
├── data/
│   ├── ble/
│   │   ├── NailProtocol.kt  # UUID, 명령, 패킷 디코딩, 단위 변환
│   │   ├── NailBleClient.kt # 스캔, 연결, MTU/PHY 협상, 명령 전송
│   │   └── StreamRecorder.kt# CSV 녹화
│   ├── MockNailDeviceClient.kt
│   └── SpectrumAnalyzer.kt …
├── model/                   # 샘플레이트, 프로파일, 상태
└── ui/                      # scan / monitor / settings / chart (Compose)
```
