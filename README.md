# NailDeviceApp

Nail 디바이스와 BLE로 연결해 마이크와 IMU 데이터를 실시간으로 보고 CSV로 저장하는 Android 앱임.

## 빌드

### 요구사항

- Android Studio (최신 안정판)
- JDK 17 이상. Android Studio에 들어 있는 JDK를 쓰면 따로 설치할 필요 없음
- Android SDK 36. Android Studio의 SDK Manager에서 설치하면 됨
- Android 8.0 이상이고 BLE를 지원하는 폰
- Gradle은 wrapper가 자동으로 받으므로 설치할 필요 없음

### 방법 1. Android Studio

1. `File > Open`에서 이 폴더(`NailDeviceApp`)를 엶.
2. 오른쪽 아래 Gradle Sync가 끝날 때까지 기다림. 처음에는 의존성을 받느라 몇 분 걸림.
3. 폰에서 개발자 옵션을 켜고 USB 디버깅을 켬.
   - 개발자 옵션은 `설정 > 휴대전화 정보 > 소프트웨어 정보`에서 빌드번호를 7번 누르면 켜짐.
4. 폰을 USB로 연결하고 폰 화면에 뜨는 "USB 디버깅 허용"을 누름.
5. 상단 실행 대상에서 폰을 고르고 ▶ Run을 누름. 빌드와 설치가 한 번에 됨.

### 방법 2. 명령줄

1. 이 폴더에서 아래 명령을 실행함.

   ```bash
   # Git Bash / macOS / Linux
   ./gradlew assembleDebug

   # Windows cmd / PowerShell
   gradlew.bat assembleDebug
   ```

2. 결과물은 `app/build/outputs/apk/debug/app-debug.apk`에 생김.
3. 폰에 설치함.

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   `adb`는 Android SDK의 `platform-tools` 폴더에 있음. APK 파일을 폰에 복사해서 직접 설치해도 됨. 이때는 "출처를 알 수 없는 앱 설치"를 허용해야 함.

### 자주 나는 오류

- **`SDK location not found`**
  - `local.properties` 파일이 없어서 나는 오류임. Android Studio로 한 번 열면 자동으로 생김.
  - 직접 만들 때는 프로젝트 폴더에 아래처럼 적음.

    ```properties
    sdk.dir=C\:\\Users\\<사용자>\\AppData\\Local\\Android\\Sdk
    ```

- **`JAVA_HOME is not set`** 또는 Java 버전 오류
  - Android Studio에 들어 있는 JDK를 지정하면 됨.

    ```bash
    export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
    ```

- **폰이 실행 대상에 안 보임**
  - USB 케이블이 충전 전용인지 확인함.
  - 폰 화면의 "USB 디버깅 허용"을 눌렀는지 확인함.

## 사용법

1. Nail 보드 전원을 켬. 광고 이름은 `NailDevice`임.
2. 앱에서 스캔한 뒤 `NailDevice`를 눌러 연결함. 처음 실행할 때는 블루투스 권한을 허용해야 함.
3. 녹화하면 `Download/NailDeviceApp/`에 CSV로 저장됨.

## PC에서 녹화 제어 (로드셀·스테이지와 동시 녹화)

앱이 켜져 있으면 폰 안에서 `127.0.0.1:8765`로 PC 명령을 받음 (`START <label>`, `STOP`, `PING`, `STATUS`).
PC는 USB + `adb forward`로 접속함. PC 쪽 실행 코드와 사용법은 [`Nano17_CustomTrials_PID_NormalOnly_X45539324/`](Nano17_CustomTrials_PID_NormalOnly_X45539324/README.md)에 있음.

- PC가 시작한 녹화는 로드셀 파일과 같은 이름이 붙음: `nail_<stamp>_<로드셀 파일 이름>`

## 프로토콜

Nordic UART Service를 씀. 패킷 포맷과 명령은 `data/ble/NailProtocol.kt`에 있음.

- 가속도: `g = raw × 0.000488` (±16 g)
- 자이로: `dps = raw × 0.07` (±2000 dps)
