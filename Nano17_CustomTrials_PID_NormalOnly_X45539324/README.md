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
