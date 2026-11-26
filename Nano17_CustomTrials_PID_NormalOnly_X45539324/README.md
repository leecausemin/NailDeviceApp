# PC 실험 코드 (로드셀 + 리니어 스테이지 + 앱 동시 녹화)

기존 `loadcell_linear_stage_제어_code` 폴더와 구조가 같음. **실행 파일만 `Nano17_Session_PhoneSync.py`로 바뀜.**
PC 한 곳에서 스테이지, 로드셀, 폰 앱 녹화를 같이 돌리고, 개인별 캘리브레이션(α) 다음에 바로 하중·속도 테스트로 넘어감.

## 파일

| 파일 | 하는 일 |
|---|---|
| `Nano17_Session_PhoneSync.py` | **새 실행 파일.** 세션 전체 (캘리브레이션 → α → 하중 × 속도 테스트) |
| `Nano17_CustomTrials_PID_NormalOnly_X45539324.py` | 기존 제어 코드. `TRIAL_HOOKS`, `LAST_TRIAL`만 추가됨. 단독으로 실행하면 예전과 똑같이 동작함 |
| `phone_remote.py` | 폰 앱 원격 제어 (adb forward, 시계 맞추기, 녹화 시작/정지, 파일 가져오기) |
| `nailsense_session.py` | nailsense의 `.venv`에서 α 계산 / N·v 추정을 돌림 (제어 코드 환경과 안 섞이게) |

## 한 trial 흐름

```
바이어스 재측정 → Z PID로 목표 힘 → 앱 녹화 시작 (이름 = trial id) → 1 s 대기
→ 5 kHz 로드셀 + X 이동 → 0.5 s 대기 → 앱 녹화 정지 → 폰 폴더를 PC로 복사 → manifest.csv 한 줄
```

로드셀 파일과 폰 폴더가 같은 trial id를 가짐 (예: `LJW_resin_cal_3N_20mmps_r01`). mtime으로 짝 맞출 필요 없음.

## 세션 흐름

1. **캘리브레이션**: 3 N × 20 mm/s를 2번 → nailsense로 그 사람·그날의 α 계산 (`LJW_1126` 같은 이름으로 모델에 저장).
   실패하면 다시 / α 없이 계속 / 종료 중에 고름.
2. **테스트**: 힘 목록 × 속도 목록 × 반복. trial이 끝날 때마다 nailsense 추정 N, v를 실제 값과 같이 출력함.

## 실행 전 준비

1. 폰에 새 앱 설치 (저장소 루트 README의 빌드 방법). 원격 제어가 들어간 버전이어야 함.
2. 폰을 USB로 PC에 연결, USB 디버깅 허용.
3. 앱을 열고 Nail 장치 연결. **앱 화면은 켜 둔 채로.**
4. 앱 저장 위치는 **기본값 (Download/NailDeviceApp)**. 다른 폴더면 PC가 파일을 못 가져옴.
5. `Nano17_Session_PhoneSync.py` 위쪽 `SESSION`에서 피험자, `PERSONAL_DEPART`, 힘/속도 목록 수정.
6. `NAILSENSE_CODE`가 이 PC의 nailsense 폴더(`nailsense/`, `models/`, `.venv/`가 있는 곳)를 가리키는지 확인.
   nailsense 없이 녹화만 하려면 `RUN_NAILSENSE: False`.
7. 기존과 똑같이: 스테이지 이동 범위, 로드셀 캘리브레이션, 비상정지 확인.

```bash
python Nano17_Session_PhoneSync.py
```

폰 연결만 먼저 확인하려면 (3초 녹화 후 `phone_test/`로 가져옴):

```bash
python phone_remote.py
```

## 결과 폴더

```
sessions/<피험자>_<시각>/
├── loadcell/       5 kHz Fx Fy Fz + X 스테이지 속도 CSV (기존과 같은 형식)
├── phone/          nail_<stamp>_<trial id>/ (imu, mic, session.txt)
├── pred/           테스트 trial별 nailsense 추정
├── manifest.csv    trial별 조건, 실제 하중, 파일 경로, 시각
├── session.json    세션 설정과 테스트 순서
└── calibration_log.txt
```

`manifest.csv`의 주요 열:
- `preload_measured_N`: X가 움직이기 전 로드셀 |Fz| 평균 = 실제로 누른 힘
- `clock_offset_ms`: 폰 시계 − PC 시계 (PING 왕복으로 측정, USB에서 오차 ~1 ms)
- `daq_minus_phone_first_mic_s`: 로드셀 t=0이 폰 파일의 몇 초 지점인지 (대략적인 동기화, BLE 지연 수십 ms 포함).
  정밀 동기화는 기존처럼 로드셀–센서 xcorr로 함.

## 앱 원격 제어 프로토콜

앱이 폰 안에서 `127.0.0.1:8765`로 대기함. PC는 `adb forward tcp:8765 tcp:8765`로 USB를 통해 접속 (같은 Wi-Fi의 다른 기기는 접근 불가).
한 줄 명령, 한 줄 응답.

| 명령 | 응답 |
|---|---|
| `PING` | `PONG <폰 epoch ms>` |
| `STATUS` | `STATUS conn=<연결 상태> rec=<녹화 상태> file=<마지막 저장 이름>` |
| `START <label>` | `OK <폰 epoch ms>` (녹화 이름 `nail_<stamp>_<label>`) |
| `STOP` | `SAVED <이름> <downloads\|custom> <폰 epoch ms>` (CSV 저장이 끝난 뒤 응답) |
| 잘못된 경우 | `ERR <이유>` |

앱 코드: `app/src/main/java/com/example/naildeviceapp/data/remote/RemoteControlServer.kt`

## 주의

- 중간에 오류로 멈추면 Z를 15 mm 올림. 그래도 손가락이 눌려 있으면 직접 올릴 것.
- α는 nailsense의 `models/force_speed_pipeline.pkl`에 덧붙여 저장됨 (alpha.ipynb와 같은 방식).
- 실제 폰 + 장비로는 아직 안 돌려봄 (앱 빌드·유닛테스트, 가짜 서버 통신, nailsense α 계산/추정은 확인함).
