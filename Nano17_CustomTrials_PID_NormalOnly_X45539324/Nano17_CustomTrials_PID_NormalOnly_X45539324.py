# NORMAL-ONLY fixed-force / fixed-speed trial version.
# Derived from Nano17_CustomTrials_PID_CoarseFineSafe_X45539324(3).py
# Original hardware integration and force safeguards are retained.


import time
import math
import nidaqmx
import atiiaftt
import clr
import threading
import csv
import numpy as np
from nidaqmx.stream_readers import AnalogMultiChannelReader
from pathlib import Path
from System import Decimal #이거 나중에 손봐야함.


# Thorlabs 라이브러리 로드
clr.AddReference("C:\\Program Files\\Thorlabs\\Kinesis\\Thorlabs.MotionControl.DeviceManagerCLI.dll")
clr.AddReference("C:\\Program Files\\Thorlabs\\Kinesis\\Thorlabs.MotionControl.GenericMotorCLI.dll")
clr.AddReference("C:\\Program Files\\Thorlabs\\Kinesis\\ThorLabs.MotionControl.IntegratedStepperMotorsCLI.dll")
from Thorlabs.MotionControl.DeviceManagerCLI import *
from Thorlabs.MotionControl.GenericMotorCLI import *
from Thorlabs.MotionControl.IntegratedStepperMotorsCLI import *


# ============================================================================
# 설정 파라미터
# ============================================================================
# 변수 처리
CONFIG = {
    'personal_depart': 4.14,     # ← 여기만 수정하면 끝!
    'TARGET_FORCE_MAGNITUDE' : -1.0,
    'settle_time': 1.0,
    'force_tol': 1.0*0.2,
    'initial_z_position': 102.0,

    # 초기 Stage 설정 방법
    # 1: Homing이 이미 잘 잡혀 있을 때
    # 2: Homing을 새로 잡아야 할 때
    'SLIDE_VELOCITY_MM_S': 40.0,
    'CASE_NAME': 'case1',
    'REPEATS': 1,
    'SAVE_DIR': str(Path(__file__).resolve().parent / 'results'),
    'SLIDE_RATE_HZ': 5000,
    'SLIDE_BLOCK_SAMPLES': 250,
    'SLIDE_MAX_DURATION_S': 60,
    'MAX_FORCE_N': 6.0,  # Software force cutoff; verify rig-specific safe limit.
    'MAX_Z_APPROACH_MM': 25.0,
    'PID_CONTACT_THRESHOLD_N': 0.10,
    'PID_MAX_COARSE_STEP_MM': 1.00,
    'PID_FINE_MAX_STEP_MM': 0.12,
    'PID_FORCE_MARGIN_N': 2.0,
    'PID_AFTER_MOVE_WAIT_S': 0.15,
    'PID_SENSOR_SAMPLE_COUNT': 5,
    'PID_SENSOR_SAMPLE_INTERVAL_S': 0.025,
    'PID_MAX_FEEDBACK_AGE_S': 0.50,
    'PID_MAX_DURATION_S': 120.0,
    'HOMING_MODE': 1,

    # 폰 앱(NailDeviceApp) 동시 녹화: 5 kHz 로드셀 측정과 같이 시작/정지
    'USE_PHONE': True,          # False면 예전처럼 로드셀만
    'PHONE_PREROLL_S': 1.0,     # 앱 녹화 시작 후 로드셀/X 시작까지 대기
    'PHONE_POSTROLL_S': 0.5,    # X 정지 후 앱 녹화 정지까지 대기
}
    
CALIB_FILE = r"C:\Load Cell\revised_FT21184\FT21184\FT21185.cal"
CALIB_INDEX = 1
STAGE_SERIAL_Zaxis = "45543844"
STAGE_SERIAL_Xaxis = "45539324"
calibration_path_Zaxis = r"C:\LinearStage\2_Calibration file\2_Calibration file\45543844\45543844.dat"
calibration_path_Xaxis = r"C:\LinearStage\2_Calibration file\2_Calibration file\45539324\45539324.dat"

STAGE_MIN_POS = 0.0      # mm
STAGE_MAX_POS = 150.0    # mm

#TARGET_FORCE_MAGNITUDE = -2.0  #추후 -1N에 맞추기 => 준우오빠가 코멘트 주심. -2N으로! #변수, 바꾸기 - force
TARGET_FORCE_MAGNITUDE = CONFIG['TARGET_FORCE_MAGNITUDE']
PID_UPDATE_INTERVAL = 0.2  # 초 (5 Hz)


# ✅ 수정된 PID 게인 (절댓값 기반)
KP = 1.0    # 비례 (감소) 2.0 -> 6.0 -> 4.0
KI = 0.0   # 적분 (감소) 0.1
KD = 0.01   # 미분 (감소) 0.05 -> 0.01

# DAQ 설정
SAMPLING_RATE = 25000# 기존 10000: 10 kHz
SAMPLES_PER_READ = 10   # 10개 샘플 평균
DAQ_READ_INTERVAL = 0.001  # 10ms마다 읽기
DAQ_CHANNELS = ["Dev1/ai0", "Dev1/ai1", "Dev1/ai2", "Dev1/ai3", "Dev1/ai4", "Dev1/ai5"]


#이벤트 플래그
exit_event = threading.Event()
z_axis_ready_event = threading.Event()  # Z축 초기 설정 완료 플래그
# xaxis_moving_event = threading.Event()

shared_force = [0, 0, 0, 0, 0, 0]
shared_lock = threading.Lock()

daq_resource_lock = threading.Lock()
daq_thread = None #DAQ 스레드 핸들
force_last_update = 0.0
daq_failed = threading.Event()

# 폰 앱 원격 녹화 (phone_remote.py). main()에서 연결, None이면 로드셀만.
PHONE = None
phone_recording = False
phone_trial_name = None
daq_start_unix = None   # 5 kHz DAQ task.start() 직후 PC 시각 (time.time())


def phone_start(name):
    """앱 녹화 시작. 앱 파일 이름 = nail_<stamp>_<로드셀 파일과 같은 이름>."""
    global phone_recording, phone_trial_name, daq_start_unix
    daq_start_unix = None
    if PHONE is None:
        return
    PHONE.sync_clock(n=10)   # 폰 시계 - PC 시계 (trial마다 다시 잼)
    PHONE.start(name)
    phone_recording = True
    phone_trial_name = name
    print(f'[PHONE] 녹화 시작: {name}')
    time.sleep(float(CONFIG['PHONE_PREROLL_S']))


def phone_stop():
    """앱 녹화 정지 후 폰 폴더를 SAVE_DIR/nail_<stamp>_<이름>/ 으로 복사하고 대략적인 지연을
    그 폴더에 기록. 이 폴더를 돌려줌 (로드셀 CSV도 여기에 저장됨)."""
    global phone_recording
    if PHONE is None or not phone_recording:
        return None
    time.sleep(float(CONFIG['PHONE_POSTROLL_S']))
    s = PHONE.stop()
    phone_recording = False
    folder = PHONE.pull(s['name'], Path(CONFIG['SAVE_DIR']), s['where'])
    print(f'[SAVED] {folder}')
    log_rough_lag(folder)
    return folder


def log_rough_lag(phone_folder):
    """그 trial 폴더(nail_<stamp>_<이름>/) 안에 phone_sync_log.csv 저장.

    lag_s = 로드셀 t=0이 폰 파일(첫 샘플 = t=0)의 몇 초 지점인지 (대략, ±0.3 s 정도).
    폰 첫 샘플 시각은 첫 패킷이 폰에 '도착한' 시각으로 잡으므로, 보드 버퍼/BLE 지연만큼
    실제보다 늦게 잡힘 -> lag_s가 실제보다 0~0.3 s 작게 나올 수 있음.
    정밀 정렬은 이 값을 중심으로 좁은 범위(예: ±0.5 s)에서 xcorr.
    """
    from phone_remote import read_session_note
    note = read_session_note(phone_folder)
    first_mic_unix = PHONE.phone_ms_to_pc_unix(note['first_mic']) if note.get('first_mic') else None
    lag = (daq_start_unix - first_mic_unix) if (daq_start_unix and first_mic_unix) else None
    log = Path(phone_folder) / 'phone_sync_log.csv'
    with open(log, 'w', newline='', encoding='utf-8-sig') as fp:
        w = csv.writer(fp)
        w.writerow(['loadcell_name', 'phone_folder', 'lag_s',
                    'daq_start_unix', 'phone_first_mic_unix', 'clock_offset_ms', 'clock_rtt_ms'])
        w.writerow([phone_trial_name, Path(phone_folder).name,
                    '' if lag is None else f'{lag:.4f}',
                    '' if daq_start_unix is None else f'{daq_start_unix:.4f}',
                    '' if first_mic_unix is None else f'{first_mic_unix:.4f}',
                    f'{PHONE.offset_ms:.1f}', f'{PHONE.rtt_ms:.1f}'])
    print(f'[SYNC] 로드셀 t=0 ≈ 폰 파일 {lag:.3f} s 지점' if lag is not None else '[SYNC] 지연 계산 실패')

# ============================================================================
# PID 컨트롤러 클래스
# ============================================================================

class PIDController:
    def __init__(self, kp, ki, kd):
        self.kp = kp
        self.ki = ki
        self.kd = kd
        self.prev_error = 0.0
        self.integral = 0.0
        self.integral_max = 10.0  # Anti-windup
        
    def update(self, error, dt):
        # 적분 항
        self.integral += error * dt
        self.integral = max(-self.integral_max, min(self.integral_max, self.integral))
        
        # 미분 항
        derivative = (error - self.prev_error) / dt if dt > 0 else 0
        self.prev_error = error
        
        # PID 출력
        output = self.kp * error + self.ki * self.integral + self.kd * derivative
        
        return output
    
    def reset(self):
        self.prev_error = 0.0
        self.integral = 0.0

# ============================================================================
# 센서 클래스
# ============================================================================

class LoadCellSensor:
    def __init__(self, calib_file, calib_index, channels):
        self.sensor = atiiaftt.FTSensor(calib_file, calib_index)
        self.channels = channels
        self.bias = None
    
    def measure_bias(self, num_samples=100):
        """무부하 상태에서 바이어스 측정"""
        bias_accum = [0.0] * len(self.channels)
        with daq_resource_lock:
            with nidaqmx.Task() as task:
                for ch in self.channels:
                    task.ai_channels.add_ai_voltage_chan(ch)
                for _ in range(num_samples):
                    sample = task.read()
                    bias_accum = [a + b for a, b in zip(bias_accum, sample)]
                    time.sleep(0.005)
        self.bias = [x / num_samples for x in bias_accum]
        return self.bias
    
    def set_bias(self, bias):
        """바이어스 설정"""
        self.bias = bias
        self.sensor.bias(bias)
    
    def convertToFt(self, raw_data):
        return self.sensor.convertToFt(raw_data)


# ============================================================================
# Stage 제어 클래스
# ============================================================================

class LinearStage:
    def __init__(self, serial_no, axis_name="Unknown"):
        self.serial_no = serial_no
        self.axis_name = axis_name
        self.device = None
        self.current_pos = 0.0
        
    def connect(self):
        """Stage 연결 및 초기화"""
        try:
            DeviceManagerCLI.BuildDeviceList()
            self.device = LongTravelStage.CreateLongTravelStage(self.serial_no)
            if self.device is None:
                raise Exception("Device creation failed")
            
            self.device.Connect(self.serial_no)
            
            # 설정 초기화 대기
            if not self.device.IsSettingsInitialized():
                self.device.WaitForSettingsInitialized(10000)
            
            # Polling 시작 및 활성화
            self.device.StartPolling(250)
            time.sleep(0.25)
            self.device.EnableDevice()
            time.sleep(0.25)
            
            print(f"✓  {self.axis_name} Stage connected: {self.device.GetDeviceInfo().Description}")
            return True
            
        except Exception as e:
            print(f"❌ Stage 연결 실패: {e}")
            self.device = None
            return False
    
    def load_calibration(self, calibration_path):
        try:
            self.device.GetCalibrationFile()
            print(f"✓ {self.axis_name} Calibration 파일 적용 완료")
            return True
        
        except Exception as e:
            print(f"❌ Calibration 적용 실패: {e}")
            return False

    def home(self, timeout_ms=120000):
        print(f"Homing stage {self.axis_name}...")
        try:
            self.device.Home(timeout_ms)
            self.current_pos = 0.0
            print(f"✓ Homing complete: {self.axis_name}")
            time.sleep(0.5)
            
            try:
                motor_config = self.device.LoadMotorConfiguration(self.serial_no)
                print(f"✓ Motor configuration reloaded: {self.axis_name}")
            except:
                pass
            return True
        
        except Exception as e:
            print(f"❌ Home 실패({self.axis_name}): {e}")
            return False
    
    def move_to(self, position_mm, timeout_ms=5000):
        position_mm = float(position_mm)
        position_mm = max(STAGE_MIN_POS, min(STAGE_MAX_POS, position_mm))
        
        if not self.device.IsSettingsInitialized():
            self.device.WaitForSettingsInitialized(10000)
            time.sleep(0.1)
        
        try:
            self.device.MoveTo(Decimal(position_mm), timeout_ms)
            self.current_pos = position_mm
            return True
        except Exception as e:
            print(f"⚠️  이동 실패({self.axis_name}): {e}")
            return False
    
    # def move_continuous(self):
    #     if not self.device.IsSettingsInitialized():
    #         self.device.WaitForSettingsInitialized(10000)
    #         time.sleep(0.1)
        
    #     try:
    #         #self.device.MoveContinuous(MotorDirection.Backward)
    #         self.device.MoveContinuousAtVelocity(MotorDirection.Backward, -1)
    #         print(f"디버깅")
    #         return True
    #     except Exception as e:
    #         print(f"⚠️  이동 실패({self.axis_name}): {e}")
    #         return False
    
    def stop(self):
        if not self.device.IsSettingsInitialized():
            self.device.WaitForSettingsInitialized(10000)
            time.sleep(0.1)
        
        try:
            self.device.Stop(0)
            return True
        except Exception as e:
            print(f"⚠️  이동 실패({self.axis_name}): {e}")
            return False
    
    def move_to_async(self, position_mm):
        """비동기 이동 (대기하지 않음)"""
        position_mm = float(position_mm)
        position_mm = max(STAGE_MIN_POS, min(STAGE_MAX_POS, position_mm))
        
        if not self.device.IsSettingsInitialized():
            self.device.WaitForSettingsInitialized(10000)
            time.sleep(0.1)
        
        try:
            self.device.MoveTo(Decimal(position_mm), 60000)
            self.current_pos = position_mm
            return True
        except Exception as e:
            print(f"⚠️  비동기 이동 실패({self.axis_name}): {e}")
            return False

    def Zaxis_MoveTo(self, position_mm=40.0, vel=1.0, acc=5.0, timeout_ms=120000):
    # def Xaxis_MoveTo(self, position_mm=50.0, vel=20.0, acc=50.0, timeout_ms=60000):
        """X축 Stage 속도 파라미터 설정 및 절대 위치 이동! 나중에 원하는 속도 및 위치에 맞게 변경하기"""
        try:
            veloPars = self.device.GetVelocityParams()
            veloPars.MaxVelocity = Decimal(vel)
            veloPars.Acceleration = Decimal(acc)
            self.device.SetVelocityParams(veloPars)
            self.device.MoveTo(Decimal(position_mm), timeout_ms)
            self.current_pos = position_mm
            print(f"✓ X축 이동 완료: {position_mm:.3f} mm")
            return True
        except Exception as e:
            print(f"❌ X축 이동 실패: {e}")
            return False
    
    def Xaxis_MoveTo(self, position_mm=50.0, vel=20.0, acc=5.0, timeout_ms=60000):
    # def Xaxis_MoveTo(self, position_mm=50.0, vel=20.0, acc=50.0, timeout_ms=60000):
        """X축 Stage 속도 파라미터 설정 및 절대 위치 이동! 나중에 원하는 속도 및 위치에 맞게 변경하기"""
        try:
            veloPars = self.device.GetVelocityParams()
            veloPars.MaxVelocity = Decimal(vel)
            veloPars.Acceleration = Decimal(acc)
            self.device.SetVelocityParams(veloPars)
            self.device.MoveTo(Decimal(position_mm), timeout_ms)
            self.current_pos = position_mm
            print(f"✓ X축 이동 완료: {position_mm:.3f} mm")
            return True
        except Exception as e:
            print(f"❌ X축 이동 실패: {e}")
            return False

    def get_position(self):
        """현재 위치 조회"""
        try:
            raw_pos = self.device.Position
            pos_float = float(str(raw_pos))
            return pos_float
        except Exception as e:
            print(f"Position read error({self.axis_name}): {e}")
            return self.current_pos
    
    def disconnect(self):
        if self.device is not None:
            try:
                self.device.StopPolling()
                time.sleep(0.1)
            except:
                pass
            
            try:
                self.device.Disconnect()
                time.sleep(0.1)
            except:
                pass
            self.device = None

# ============================================================================
# DAQ reading 함수 (FINITE 모드)
# ============================================================================
def daq_reader(sensor):
    """
    FINITE 모드로 계속 반복해서 읽는 DAQ 스레드.
    - 매 루프마다 새로운 Task 생성 → 리소스/버퍼 문제 최소화
    - 채널당 SAMPLES_PER_READ개 읽고 평균낸 뒤 shared_force 갱신
    """
    global force_last_update
    error_count = 0
    MAX_ERRORS = 20

    while not exit_event.is_set() and error_count < MAX_ERRORS:
        try:
            with daq_resource_lock:
                with nidaqmx.Task() as task:
                    # 채널 추가
                    for ch in DAQ_CHANNELS:
                        task.ai_channels.add_ai_voltage_chan(ch)

                    # FINITE 모드 타이밍
                    task.timing.cfg_samp_clk_timing(
                        rate=SAMPLING_RATE,
                        sample_mode=nidaqmx.constants.AcquisitionType.FINITE,
                        samps_per_chan=SAMPLES_PER_READ
                    )

                    # 읽기
                    raw = task.read(
                        number_of_samples_per_channel=SAMPLES_PER_READ,
                        timeout=1.0
                    )
                # raw: 각 채널이 길이 SAMPLES_PER_READ인 리스트
            averaged = [
                sum(raw[ch_idx]) / SAMPLES_PER_READ
                for ch_idx in range(len(DAQ_CHANNELS))
            ]

            forces = sensor.convertToFt(averaged)  # [Fx, Fy, Fz, Tx, Ty, Tz]

            with shared_lock:
                shared_force[:] = forces
                force_last_update = time.monotonic()

            error_count = 0
            time.sleep(DAQ_READ_INTERVAL)

        except nidaqmx.errors.DaqError as e:
            error_count += 1
            print(f"❌ DAQ read error #{error_count}: {e}")
            time.sleep(0.01)

        except Exception as e:
            error_count += 1
            print(f"❌ DAQ 일반 오류 #{error_count}: {e}")
            time.sleep(0.01)

    if error_count >= MAX_ERRORS:
        daq_failed.set()
    print("⚠️ DAQ 리더 종료 (에러 과다 또는 exit_event 설정)")

def start_daq(sensor):
    global daq_thread
    if daq_thread is not None and daq_thread.is_alive():
        return
    daq_failed.clear()
    exit_event.clear()
    daq_thread = threading.Thread(target=daq_reader, args=(sensor,), daemon=True)
    daq_thread.start()
    time.sleep(0.1)

def stop_daq():
    global daq_thread
    exit_event.set()
    if daq_thread is not None:
        daq_thread.join(timeout=5.0)
        if daq_thread.is_alive():
            raise RuntimeError("PID DAQ thread did not stop; refusing to start 5k DAQ")
    daq_thread = None

# ============================================================================
# PID 제어 관련 함수
# ============================================================================

def initial_zaxis_pid_control(stage_Zaxis, pid_controller, TARGET_FORCE_MAGNITUDE,
                              settle_time=CONFIG['settle_time'],
                              force_tol=CONFIG['force_tol'],
                              coarse_step_mm=1.0, max_pid_step_mm=None):
    """Safely approach a negative Fz target, then settle in a force deadband.

    - COARSE moves toward contact only; no oversized up/down corrections.
    - FINE limits each Z step and uses blocking MoveTo (motion must finish).
    - Sensors are checked for age, force excess and non-finite readings.
    - No motion while within the force tolerance; require sustained settling.

    IMPORTANT: This is software supervision, NOT an independent safety interlock.
    Use physical E-stop and verify Z polarity with no finger attached first.
    """
    target = float(TARGET_FORCE_MAGNITUDE)
    if not math.isfinite(target) or target >= 0:
        raise ValueError('PID requires finite negative target Fz')
    if not 0 < force_tol < abs(target):
        raise ValueError('PID force_tol must be positive and below target magnitude')

    coarse_max = min(float(coarse_step_mm), float(CONFIG['PID_MAX_COARSE_STEP_MM']))
    fine_max = float(CONFIG['PID_FINE_MAX_STEP_MM'])
    if max_pid_step_mm is not None:
        fine_max = min(fine_max, float(max_pid_step_mm))
    if coarse_max <= 0 or fine_max <= 0:
        raise ValueError('PID movement step must be positive')

    # A target-relative cap reduces exposure to overload during first contact.
    # Allow a previously established contact force to be RELEASED when the
    # next S-mode target is smaller in magnitude (e.g. -5 N -> -1 N).
    # Never raise the absolute global force ceiling.
    with shared_lock:
        entry_fz = float(shared_force[2])
    force_limit = min(float(CONFIG['MAX_FORCE_N']),
                      max(abs(target) + float(CONFIG['PID_FORCE_MARGIN_N']),
                          abs(entry_fz) + 0.5))
    t_start = time.monotonic()
    z_start = float(str(stage_Zaxis.device.Position))
    if not math.isfinite(z_start):
        raise RuntimeError('Invalid initial Z stage position')
    pid_controller.reset()

    def check_watchdogs(fz):
        if exit_event.is_set():
            raise RuntimeError('PID aborted: global stop requested')
        if daq_failed.is_set():
            raise RuntimeError('PID aborted: DAQ failed')
        if not math.isfinite(fz):
            raise RuntimeError('PID aborted: non-finite Fz')
        if abs(fz) >= force_limit:
            raise RuntimeError(f'PID FORCE LIMIT: Fz={fz:.3f} N, limit={force_limit:.3f} N')
        if time.monotonic() - t_start >= float(CONFIG['PID_MAX_DURATION_S']):
            raise RuntimeError('PID aborted: force-control timeout')

    def read_fz_median():
        readings = []
        count = int(CONFIG['PID_SENSOR_SAMPLE_COUNT'])
        for _ in range(count):
            with shared_lock:
                fz = float(shared_force[2])
                updated = float(force_last_update)
            if updated <= 0 or time.monotonic() - updated > float(CONFIG['PID_MAX_FEEDBACK_AGE_S']):
                raise RuntimeError('PID aborted: DAQ sample stale')
            check_watchdogs(fz)  # Check EACH raw snapshot before median.
            readings.append(fz)
            time.sleep(float(CONFIG['PID_SENSOR_SAMPLE_INTERVAL_S']))
        return float(np.median(readings))

    def get_actual_z():
        z = float(str(stage_Zaxis.device.Position))
        if not math.isfinite(z):
            raise RuntimeError('Invalid actual Z position')
        return z

    def move_checked(z_target):
        old_z = get_actual_z()
        z_target = float(z_target)
        if not (STAGE_MIN_POS <= z_target <= STAGE_MAX_POS):
            raise RuntimeError('PID: requested Z outside stage limits')
        if abs(z_target - z_start) > float(CONFIG['MAX_Z_APPROACH_MM']):
            raise RuntimeError('PID: requested Z beyond approach travel limit')
        if not stage_Zaxis.move_to(z_target, timeout_ms=5000):
            raise RuntimeError('PID Z MoveTo failed')
        actual = get_actual_z()
        if abs(actual - z_target) > 0.05:
            raise RuntimeError(f'PID: Z did not reach target (cmd {z_target:.4f}, actual {actual:.4f})')
        if time.monotonic() - t_start >= float(CONFIG['PID_MAX_DURATION_S']):
            raise RuntimeError('PID: movement exceeded time budget')
        time.sleep(float(CONFIG['PID_AFTER_MOVE_WAIT_S']))
        return actual

    print(f'\n[Z coarse] target Fz={target:.2f} N; coarse <= {coarse_max:.3f} mm, '
          f'fine <= {fine_max:.3f} mm, force cutoff {force_limit:.2f} N')

    # Stage 1: ONLY move towards the contact, with conservative bounded steps.
    # As soon as compressive contact is detected, switch to FINE control.
    reached_contact = False
    max_coarse_steps = int(math.ceil(float(CONFIG['MAX_Z_APPROACH_MM']) / coarse_max)) + 2
    for idx in range(max_coarse_steps):
        fz = read_fz_median()
        z_now = get_actual_z()
        error = target - fz
        print(f'  Coarse {idx+1:3d} | Z: {z_now:.4f} mm | Fz: {fz:.4f} N | Error: {error:.4f}')
        if fz <= -float(CONFIG['PID_CONTACT_THRESHOLD_N']) or abs(error) <= force_tol:
            reached_contact = True
            break
        if fz > 0.5:
            raise RuntimeError('PID: unexpected positive Fz during contact approach; check sign/bias')
        move_checked(z_now - coarse_max)
    if not reached_contact:
        raise RuntimeError('PID: contact not found within bounded Z travel')

    # Stage 2: bounded fine position increments; hold completely still inside tolerance.
    print(f'\n[Z fine] target {target:.3f} N, tolerance +/-{force_tol:.3f} N')
    settled_count = 0
    settle_threshold = max(1, int(math.ceil(float(settle_time) / PID_UPDATE_INTERVAL)))
    iteration = 0
    while not exit_event.is_set():
        fz = read_fz_median()
        z_now = get_actual_z()
        error = target - fz
        in_band = abs(error) <= force_tol
        if in_band:
            settled_count += 1
            step = 0.0
        else:
            settled_count = 0
            pid_output = float(pid_controller.update(error, PID_UPDATE_INTERVAL))
            raw_step = pid_output * PID_UPDATE_INTERVAL
            # Never let derivative kick reverse a motion that should reduce force error.
            step_size = min(fine_max, max(0.005, abs(raw_step)))
            step = math.copysign(step_size, error)
            move_checked(z_now + step)

        print(f'  Fine {iteration:3d} | Z: {z_now:.4f} mm | Fz: {fz:.4f} N | '
              f'Error: {error:.4f} N | dZ: {step:+.4f} mm | '
              f'Settled: {settled_count}/{settle_threshold}')

        if settled_count >= settle_threshold:
            final_z = get_actual_z()
            print(f'\n[PID READY] Z={final_z:.4f} mm, Fz={fz:.4f} N '
                  f'(held {settled_count} readings)')
            z_axis_ready_event.set()
            return True
        iteration += 1
        time.sleep(PID_UPDATE_INTERVAL)

    raise RuntimeError('PID aborted: global stop requested')

def setup_z_axis_pid(stage_Zaxis, target_force=None, coarse_step_mm=1.0, max_pid_step_mm=None):
    # Set selected target force using bounded coarse/fine PID.
    if target_force is None:
        target_force = TARGET_FORCE_MAGNITUDE
    pid = PIDController(KP, KI, KD)
    z_axis_ready_event.clear()
    print(f"[PID] Existing FINITE DAQ, target {target_force:.2f} N")
    success = initial_zaxis_pid_control(stage_Zaxis, pid, target_force,
                                        CONFIG['settle_time'], CONFIG['force_tol'],
                                        coarse_step_mm=coarse_step_mm,
                                        max_pid_step_mm=max_pid_step_mm)
    if not success:
        raise RuntimeError('Z axis PID did not reach target; X movement forbidden')
    return float(str(stage_Zaxis.device.Position))

# ============================================================================
# 데이터 기록 및 X축 이동
# ============================================================================
def record_5khz_slide(stage_Xaxis, stage_Zaxis, sensor, force_n, speed_mm_s, case_name, trial_num):
    """Stop FINITE PID task first, acquire 6ch at 5kHz, then convert and save Fx/Fy/Fz."""
    stop_daq()  # must fully release NI-DAQ channels before continuous acquisition
    folder = Path(CONFIG['SAVE_DIR'])
    folder.mkdir(parents=True, exist_ok=True)
    safe_case = ''.join(c if c.isalnum() or c in '-_' else '_' for c in case_name)
    name = f"{force_n:g}N_{speed_mm_s:g}mmps_{safe_case}_r{trial_num:02d}_{time.strftime('%Y%m%d_%H%M%S')}"
    force_file = folder / (name + '_FxFyFz_5000Hz.csv')
    stage_file = folder / (name + '_Xstage_velocity.csv')
    trial_dir = None  # 앱 녹화 폴더 (phone_stop이 채움)
    raw_blocks = []
    positions = []
    abort = threading.Event()
    acquired = threading.Event()
    err = []
    started_at = time.monotonic()
    block = int(CONFIG['SLIDE_BLOCK_SAMPLES'])
    fs = float(CONFIG['SLIDE_RATE_HZ'])
    phone_start(name)  # 앱 녹화 먼저 (실패하면 X가 움직이기 전에 멈춤)

    def capture():
        global daq_start_unix
        try:
            with nidaqmx.Task() as task:
                for ch in DAQ_CHANNELS:
                    task.ai_channels.add_ai_voltage_chan(ch)
                task.timing.cfg_samp_clk_timing(rate=fs,
                    sample_mode=nidaqmx.constants.AcquisitionType.CONTINUOUS,
                    samps_per_chan=int(fs * 10))
                data = np.empty((6, block), dtype=np.float64)
                reader = AnalogMultiChannelReader(task.in_stream)
                task.start()
                daq_start_unix = time.time()
                acquired.set()
                while not abort.is_set():
                    reader.read_many_sample(data, number_of_samples_per_channel=block, timeout=2.0)
                    raw_blocks.append(data.copy())
                    if sum(b.shape[1] for b in raw_blocks) > fs * CONFIG['SLIDE_MAX_DURATION_S']:
                        raise RuntimeError('Sliding capture duration exceeded')
                    # only force conversion for safety, not file I/O during DAQ read
                    for i in (0, block//2, block-1):
                        ft = sensor.convertToFt(data[:,i].tolist())
                        if abs(ft[2]) >= CONFIG['MAX_FORCE_N']:
                            raise RuntimeError('Fz safety limit reached during sliding')
        except Exception as e:
            err.append(e)
            abort.set()
            acquired.set()
            try: stage_Xaxis.stop()
            except Exception: pass

    def stage_logger():
        while not abort.is_set():
            try:
                positions.append((time.monotonic()-started_at, float(stage_Xaxis.get_position())))
            except Exception: pass
            time.sleep(0.05)

    t_cap = threading.Thread(target=capture, daemon=False)
    t_stage = threading.Thread(target=stage_logger, daemon=False)
    t_cap.start()
    acquired.wait(timeout=5)
    if err or not acquired.is_set():
        abort.set(); t_cap.join(timeout=4)
        raise RuntimeError('5 kHz capture failed to start: '+str(err))
    t_stage.start()
    try:
        time.sleep(0.2)
        if err: raise RuntimeError(str(err[0]))
        target = 86.0 + CONFIG['personal_depart']
        if not (STAGE_MIN_POS <= target <= STAGE_MAX_POS):
            raise RuntimeError('X target outside software travel limits')
        if stage_Xaxis.get_position() >= target - 0.5:
            raise RuntimeError('X already near end target; check homing and initial position')
        ok = stage_Xaxis.Xaxis_MoveTo(target, vel=speed_mm_s, acc=30.0)
        if not ok: raise RuntimeError('X stage MoveTo failed')
        actual_x = float(stage_Xaxis.get_position())
        if not math.isfinite(actual_x) or abs(actual_x - target) > 0.5:
            raise RuntimeError(f'X stage endpoint mismatch: commanded {target:.3f} mm, measured {actual_x:.3f} mm')
        if err: raise RuntimeError(str(err[0]))
    finally:
        abort.set()
        try: stage_Xaxis.stop() if err else None
        except Exception: pass
        t_cap.join(timeout=5); t_stage.join(timeout=2)
        try:
            trial_dir = phone_stop()
        except Exception as e:  # 로드셀 데이터는 그대로 저장 (SAVE_DIR에)
            print(f'⚠️  앱 녹화 정지/복사 실패: {e}')
    if t_cap.is_alive():
        raise RuntimeError('5 kHz DAQ thread still active; refusing conversion or next trial')
    if err:
        raise RuntimeError('5 kHz DAQ failed: '+str(err[0]))

    # 앱 녹화가 됐으면 로드셀 CSV도 같은 nail_<stamp>_<이름>/ 폴더에 저장
    if trial_dir is not None:
        force_file = Path(trial_dir) / force_file.name
        stage_file = Path(trial_dir) / stage_file.name

    # motion has finished; make CSV only now, avoiding writer bottleneck during slide
    with open(force_file, 'w', newline='', encoding='utf-8-sig') as fp:
        w = csv.writer(fp)
        w.writerow(['Fx_N','Fy_N','Fz_N'])
        for matrix in raw_blocks:
            for sample_idx in range(matrix.shape[1]):
                f = sensor.convertToFt(matrix[:,sample_idx].tolist())
                w.writerow([f'{f[0]:.6f}', f'{f[1]:.6f}', f'{f[2]:.6f}'])
    with open(stage_file, 'w', newline='', encoding='utf-8-sig') as fp:
        w = csv.writer(fp)
        w.writerow(['elapsed_s', 'x_mm', 'velocity_mm_s'])
        prev = None
        for tm, pos in positions:
            vel = '' if prev is None or tm<=prev[0] else f'{(pos-prev[1])/(tm-prev[0]):.4f}'
            w.writerow([f'{tm:.6f}', f'{pos:.6f}', vel])
            prev = (tm, pos)
    print(f'[SAVED] {force_file} | {(sum(m.shape[1] for m in raw_blocks))} samples/channel at {fs:g} Hz')
    print(f'[SAVED] {stage_file}')
    return str(force_file)

# ============================================================================
# Each trial: load-cell re-bias and fixed-force/fixed-speed sliding
# ============================================================================

def remeasure_bias_for_trial(sensor, test_count, trial_num):
    """매 trial 시작 전 로드셀 바이어스 재측정 및 적용"""
    print(f"\n[실험 번호 {test_count} - 반복 {trial_num}] 바이어스 재측정 중...")
    print("⚠️  센서에 힘이 가해지지 않은 상태를 확인하세요.")
    print("▶️  1초 후 바이어스 측정을 시작합니다.")
    time.sleep(1)

    stop_daq()
    bias = sensor.measure_bias(num_samples=100)
    sensor.set_bias(bias)
    start_daq(sensor)
    print(f"✓ 바이어스 측정 및 적용 완료: {[f'{v:.4f}' for v in bias]}")

def run_single_trial(stage_Xaxis, stage_Zaxis, sensor, fixed_z_position, test_count,
                     trial_num, return_to_initial=False):
    """Normal trial only: return -> re-bias -> Fz PID -> fixed-speed X slide -> retract.

    The normal force is set with PID BEFORE the X slide. Z is fixed while the
    X stage moves. Therefore Fz is monitored/recorded but NOT actively controlled
    during sliding.
    """
    if return_to_initial:
        if fixed_z_position is None:
            raise RuntimeError('Cannot return for repeat without preceding Z reference')
        x_return2inital_forREP(stage_Xaxis, stage_Zaxis, fixed_z_position,
                               test_count, trial_num)

    remeasure_bias_for_trial(sensor, test_count, trial_num)
    force_n = abs(float(CONFIG['TARGET_FORCE_MAGNITUDE']))

    fixed_z_position = setup_z_axis_pid(stage_Zaxis)
    try:
        record_5khz_slide(stage_Xaxis, stage_Zaxis, sensor, force_n,
                          float(CONFIG['SLIDE_VELOCITY_MM_S']),
                          CONFIG['CASE_NAME'], trial_num)
    finally:
        start_daq(sensor)

    time.sleep(2)
    if not stage_Zaxis.Zaxis_MoveTo(fixed_z_position + 15.0,
                                   vel=1.0, acc=5.0):
        raise RuntimeError('Z retract failed; stop')
    return fixed_z_position

def execute_x_movement(stage_Xaxis, stage_Zaxis, fixed_z_position, test_count, trial_num, record_stop_event=None):
    """X축 80mm 이동 및 초기 위치로 복귀"""
    
    personal_depart = CONFIG['personal_depart']
    #personal_depart = float(3.5)#mm단위 #사용자 맞춤형1_personal option1 #변수, 바꾸기 - 초기 위치, depart


    # X축을 80mm로 이동
    print(f"[실험 번호 {test_count} - 반복 {trial_num}] X축을 {86.0+personal_depart}mm로 이동 중...")
    stage_Xaxis.Xaxis_MoveTo(86.0+personal_depart, vel=CONFIG['SLIDE_VELOCITY_MM_S'], acc=30.0)
    print(f"[실험 번호 {test_count} - 반복 {trial_num}] ✓ {86.0+personal_depart}mm 도달 완료")

    # 모든 trial에서 X축 목표 위치 도달 후 데이터 기록 중지
    if record_stop_event is not None:
        print(f"[실험 번호 {test_count} - 반복 {trial_num}] Load Cell 데이터 기록 중지...")
        record_stop_event.set()
        print(f"[실험 번호 {test_count} - 반복 {trial_num}] ✓ Load Cell 데이터 기록 완료")


    time.sleep(2)

    stage_Zaxis.Zaxis_MoveTo(fixed_z_position + 15.0, vel=1.0, acc=5.0)
    time.sleep(0.1)

def x_return2inital_forREP(stage_Xaxis, stage_Zaxis, fixed_z_position, test_count, trial_num):
    personal_depart = CONFIG['personal_depart']
    # 초기 위치로 복귀
    print(f"[실험 번호 {test_count} - 반복 {trial_num}] X축을 초기 위치로 복귀 중...")
    # stage_Xaxis.Xaxis_MoveTo(5.0, vel=20.0, acc=10.0)
    # stage_Xaxis.Xaxis_MoveTo(0.0, vel=5.0, acc=2.0)
    stage_Xaxis.Xaxis_MoveTo(personal_depart + 5.0, vel=20.0, acc=10.0)
    stage_Xaxis.Xaxis_MoveTo(personal_depart, vel=5.0, acc=2.0)
    # 다음 trial의 바이어스 재측정과 PID 재조정을 위해 Z축은 접촉 위치보다 위쪽의 안전 위치로 유지
    stage_Zaxis.Zaxis_MoveTo(fixed_z_position + 15.0, vel=1.0, acc=5.0)
    print(f"[실험 번호 {test_count} - 반복 {trial_num}] ✓ X축 초기 위치 복귀 및 Z축 안전 위치 유지 완료")

    time.sleep(2)

def x_return2inital_for_nextsample(stage_Xaxis, stage_Zaxis, test_count, trial_num):
    personal_depart = CONFIG['personal_depart']
    initial_z_position = CONFIG['initial_z_position']
    # 초기 위치로 복귀
    print(f"[실험 번호 {test_count} - 반복 {trial_num}] X축을 초기 위치로 복귀 중...")
    # stage_Xaxis.Xaxis_MoveTo(5.0, vel=20.0, acc=10.0)
    # stage_Xaxis.Xaxis_MoveTo(0.0, vel=5.0, acc=2.0)
    stage_Xaxis.Xaxis_MoveTo(personal_depart + 5.0, vel=20.0, acc=10.0)
    stage_Xaxis.Xaxis_MoveTo(personal_depart, vel=5.0, acc=2.0)
    stage_Zaxis.Zaxis_MoveTo(initial_z_position, vel=1.0, acc=5.0)
    print(f"[실험 번호 {test_count} - 반복 {trial_num}] ✓ 초기 위치 복귀 완료")

    time.sleep(2)

# ============================================================================
# 스페이스바 리스너
# ============================================================================

class SpaceKeyListener:
    def __init__(self):
        self.space_pressed = False

    def listen(self):
        try:
            import msvcrt
            while not exit_event.is_set():
                if msvcrt.kbhit():
                    key = msvcrt.getch()
                    if key == b' ':
                        self.space_pressed = True
                        return True
                    elif key.lower() == b'n':
                        return False
                time.sleep(0.01)
        except ImportError:
            print("\n(스페이스바는 Windows에서만 실시간 감지 가능합니다)")
            while not exit_event.is_set():
                try:
                    user_input = input().strip().lower()
                    if user_input == ' ' or user_input == 'space':
                        return True
                    elif user_input == 'q':
                        exit_event.set()
                        return False
                except:
                    pass
        return False


# ============================================================================
# Homing 제어 함수
# ============================================================================
def setup_initial_stage_positions(stage_Xaxis, stage_Zaxis):
    """
    실험 시작 전 X/Z stage 초기 설정

    HOMING_MODE = 1
        → Homing이 이미 잘 잡혀 있는 경우
        → X축만 Home 확인
        → Z축은 현재 calibration 기준 사용

    HOMING_MODE = 2
        → Homing을 새로 잡아야 하는 경우
        → X축 Home
        → X축을 140 mm로 이동
        → Z축 Home
        → X/Z축 실험 초기 위치로 이동
    """

    homing_mode = CONFIG['HOMING_MODE']
    personal_depart = CONFIG['personal_depart']
    initial_z_position = CONFIG['initial_z_position']

    print(f"\n[Stage 초기 설정] HOMING_MODE = {homing_mode}")

    # ============================================================
    # MODE 1: Homing이 이미 잘 잡혀 있을 때
    # ============================================================
    if homing_mode == 1:

        print("▶ Mode 1: 기존 Homing 기준을 사용합니다.")

        # Z축은 다시 Homing하지 않고 motor configuration만 불러오기
        motor_config = stage_Zaxis.device.LoadMotorConfiguration(stage_Zaxis.serial_no)

        # X축 Homing
        thread_X = threading.Thread(target=stage_Xaxis.home)
        thread_X.start()
        thread_X.join()

        #사용자 맞춤형1_personal option1
        print(f"X축 초기 위치: {personal_depart} mm")
        stage_Xaxis.Xaxis_MoveTo(personal_depart, vel=10.0, acc=5.0)
        print(f"✓ {personal_depart} mm 도달 완료")

        print(f"Z축을 {initial_z_position} mm로 이동 중...")
        stage_Zaxis.Zaxis_MoveTo(initial_z_position, vel=1.0, acc=5.0)

        print(f"✓ {initial_z_position} mm 도달 완료")
        print("✓ X축 스테이지 Homing 완료 및 Z축 스테이지 준비 완료")


    # ============================================================
    # MODE 2: Homing을 새로 잡아야 할 때
    # ============================================================
    elif homing_mode == 2:

        print("▶ Mode 2: X/Z축 Homing을 새로 수행합니다.")

        thread_X = threading.Thread(target=stage_Xaxis.home)
        thread_X.start()
        thread_X.join()
        stage_Xaxis.Xaxis_MoveTo(140.0, vel=5.0, acc=3.0)
        thread_Z = threading.Thread(target=stage_Zaxis.home)
        thread_Z.start()
        thread_Z.join()

        # Z축 초기 위치
        print(f"Z축을 {initial_z_position} mm로 이동 중...")
        stage_Zaxis.Zaxis_MoveTo(initial_z_position, vel=1.0, acc=5.0)
        print(f"✓ {initial_z_position} mm 도달 완료")

        # X축 개인별 초기 위치
        print(f"X축을 {personal_depart} mm로 이동 중...")
        stage_Xaxis.Xaxis_MoveTo(personal_depart, vel=10.0, acc=5.0)
        print(f"✓ {personal_depart} mm 도달 완료")
        print("✓ X축 스테이지 Homing 완료 및 Z축 스테이지 준비 완료")


    # ============================================================
    # 잘못된 값 입력
    # ============================================================
    else:
        raise ValueError(
            f"HOMING_MODE는 1 또는 2만 가능합니다. "
            f"현재 값: {homing_mode}"
        )

    print("✓ X/Z축 스테이지 준비 완료")

# ============================================================================
# 메인 제어 루프
# ============================================================================
def main():
    global TARGET_FORCE_MAGNITUDE, PHONE
    print('--- Experiment settings (before connection) ---')
    target = float(input('Target normal force (N, positive; e.g. 3): ').strip())
    vel = float(input('Sliding speed (mm/s; e.g. 20): ').strip())
    case = input('Case name (e.g. Resin): ').strip() or 'case1'
    reps = int(input('Number of trials [1]: ').strip() or '1')
    if not (0.1 <= target <= 5.0):
        raise ValueError('Target force range 0.1..5 N; check your experiment plan')
    if not (1 <= vel <= 40):
        raise ValueError('Requested speed range 1..40 mm/s')
    if not (1 <= reps <= 100):
        raise ValueError('Invalid number of trials')
    TARGET_FORCE_MAGNITUDE = -target
    CONFIG['TARGET_FORCE_MAGNITUDE'] = -target
    CONFIG['SLIDE_VELOCITY_MM_S'] = vel
    CONFIG['CASE_NAME'] = case
    CONFIG['REPEATS'] = reps
    print(f'CONFIG: {target:g}N, {vel:g}mm/s, case={case}, trials={reps}')
    print('NORMAL only: PID Fz before sliding; fixed X speed; 5 kHz force log.')
    print('WARNING: Z stays fixed while sliding; check measured Fz in CSV.')
    if CONFIG['USE_PHONE']:
        # 폰: USB 연결 + 앱 실행 + Nail 장치 연결된 상태여야 함
        from phone_remote import PhoneRecorder
        PHONE = PhoneRecorder()
        print(f'✓ 폰 앱 연결: {PHONE.connect()}')
    input('After verifying stage travel, sensor calibration and physical E-stop, press Enter to connect...')
    print("=" * 80)
    print("😆✨🍀실험 시작~~~~~ w/ Linear Stages and Load Cell")
    print("=" * 80)
    
    # ========================================================================
    # [1단계] Linear Stage 초기화
    # ========================================================================
    print("\n[1단계] Linear Stage 초기화 중...")
    stage_Zaxis = LinearStage(STAGE_SERIAL_Zaxis, axis_name="Z축")
    stage_Xaxis = LinearStage(STAGE_SERIAL_Xaxis, axis_name="X축")

    try:
        connected_Z = stage_Zaxis.connect()
        connected_X = stage_Xaxis.connect()
        time.sleep(1)

        if not connected_Z or not connected_X:
            print("Stage 연결 실패")
            return

        stage_Zaxis.load_calibration(calibration_path_Zaxis)
        stage_Xaxis.load_calibration(calibration_path_Xaxis)

        setup_initial_stage_positions(stage_Xaxis, stage_Zaxis)



    except Exception as e:
        print(f"❌ Stage 초기화 실패: {e}")
        import traceback
        traceback.print_exc() 
        return

    # ========================================================================
    # [2단계] 센서 초기화 및 바이어스 측정
    # ========================================================================
    print("\n[2단계] 로드셀 센서 초기화 중...")
    sensor = LoadCellSensor(CALIB_FILE, CALIB_INDEX, DAQ_CHANNELS)

    # print("⚠️  센서에 힘이 가해지지 않은 상태에서 대기 중...")
    # print("▶️  1초 후 바이어스 측정을 시작합니다.")
    # time.sleep(1)
    
    # bias = sensor.measure_bias(num_samples=100)
    # sensor.set_bias(bias)
    # print(f"✓ 바이어스 측정 및 적용 완료: {[f'{v:.4f}' for v in bias]}")

    # DAQ 리더 스레드 시작 (FINITE 모드)
    start_daq(sensor)

    # --------------------------------------------------------------------
    # Chosen fixed force/speed/case, repeated trials
    # --------------------------------------------------------------------
    fixed_z_position = None
    try:
        for trial_num in range(1, CONFIG['REPEATS']+1):
            print(f'\n=== Trial {trial_num}/{CONFIG["REPEATS"]} ===')
            fixed_z_position = run_single_trial(
                stage_Xaxis, stage_Zaxis, sensor, fixed_z_position,
                1, trial_num, return_to_initial=(trial_num > 1))
    except Exception as e:
        print(f'[ABORT] {e}')
        try: stage_Xaxis.stop(); stage_Zaxis.stop()
        except Exception: pass
        try: phone_stop()  # 중간에 멈춰도 앱 녹화는 닫음
        except Exception: pass
    finally:
        exit_event.set()
        if PHONE is not None:
            PHONE.close()

    # ====================================================================
    # [5단계] 시스템 종료
    # ====================================================================

    print("\n[5단계] 시스템 종료 중...")

    try:
        stop_daq()
        print("✓ DAQ 리더 스레드 종료")
    except:
        pass

    try:
        stage_Zaxis.disconnect()
        stage_Xaxis.disconnect()
        print("✓ Stage 연결 해제")
    except:
        pass

    print("✓ 프로그램 종료")

if __name__ == "__main__":
    main()