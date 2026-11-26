# PC 실험 코드 (로드셀 + 리니어 스테이지 + 앱 동시 녹화)

기존 `loadcell_linear_stage_제어_code`와 같은 코드. **로드셀 측정이 시작될 때 앱 녹화도 같이 시작되고, 끝날 때 같이 멈추도록만 추가함.**
쓰는 방법(입력값, Enter)은 예전과 같음.

| 파일 | 하는 일 |
|---|---|
| `Nano17_CustomTrials_PID_NormalOnly_X45539324.py` | 실행 파일 (기존 코드 + 앱 녹화 시작/정지) |
| `phone_remote.py` | 앱 원격 제어 (USB adb로 녹화 시작/정지, 파일 가져오기) |

## 준비

1. 폰에 이 저장소의 앱 최신 버전 설치 (루트 README의 빌드 방법).
2. 폰 USB 연결 + USB 디버깅 허용.
3. 앱 열고 Nail 장치 연결, 앱 화면 켜 둠.
4. 앱 저장 위치는 기본값 (Download/NailDeviceApp).

```bash
python Nano17_CustomTrials_PID_NormalOnly_X45539324.py
```

앱 없이 예전처럼 로드셀만 쓰려면 `CONFIG['USE_PHONE'] = False`.

## trial마다

```
Z PID로 목표 힘 → 앱 녹화 시작 → 1 s → 5 kHz 로드셀 + X 이동 → 0.5 s → 앱 녹화 정지 → 폰 파일을 PC로 복사
```

- 로드셀: `results/<이름>_FxFyFz_5000Hz.csv` (예전과 같음)
- 앱: `results/phone/nail_<stamp>_<같은 이름>/` → 로드셀 파일과 이름으로 바로 짝 맞음
- 앱이 연결 안 돼 있으면 시작할 때 멈춤. 녹화 시작이 안 되면 X가 움직이기 전에 멈춤.

## 대략적인 지연 기록

trial마다 `results/phone_sync_log.csv`에 한 줄씩 남음.

| 열 | 뜻 |
|---|---|
| `lag_s` | 로드셀 t=0이 폰 파일(첫 샘플 = 0 s)의 몇 초 지점인지. 보통 1 s 근처 |
| `daq_start_unix` | 로드셀 측정 시작 PC 시각 |
| `phone_first_mic_unix` | 폰 첫 mic 패킷 도착 시각 (PC 시계로 바꾼 값) |
| `clock_offset_ms`, `clock_rtt_ms` | 폰 시계 − PC 시계, 측정 왕복 시간 |

- 정확도는 ±0.3 s 정도. 첫 패킷 "도착" 시각을 쓰므로 보드 버퍼·BLE 지연(0~0.3 s)만큼 `lag_s`가 실제보다 작게 나올 수 있음.
- 정밀 정렬: 폰 신호를 `lag_s`만큼 옮긴 뒤, 그 근처 좁은 범위(예: ±0.5 s)에서 로드셀–센서 xcorr로 맞춤.
