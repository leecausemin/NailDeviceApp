# One subject, one sitting: stage + load cell + phone app recorded together, personal
# calibration (alpha) right after the reference trials, then the force x speed test grid.
#
#   python Nano17_Session_PhoneSync.py
#
# Before running:
#   - phone on USB (USB debugging on), NailDeviceApp open and the nail device CONNECTED in the app
#   - app save location = default (Downloads/NailDeviceApp), so the PC can adb pull each recording
#   - same checks as the original script (stage travel, load-cell calibration, physical E-stop)
#
# Per trial: re-bias -> Z PID to target force -> phone START (label = trial id) -> 5 kHz DAQ +
# X slide -> phone STOP -> pull phone folder -> manifest row. Files of one trial share the label.

import csv
import json
import subprocess
import time
import traceback
from pathlib import Path

import numpy as np

import Nano17_CustomTrials_PID_NormalOnly_X45539324 as rig
from phone_remote import PhoneRecorder, PhoneError, read_session_note

HERE = Path(__file__).resolve().parent
# nailsense repo (folder with nailsense/, models/, .venv/). Change this if it lives elsewhere on this PC.
NAILSENSE_CODE = HERE.parent / 'code'

SESSION = {
    'SUBJECT': 'LJW',                 # file / alpha-session prefix
    'PERSONAL_DEPART': 4.14,          # X start position for this finger [mm]
    'HOMING_MODE': 1,                 # 1: homing already good, 2: home X and Z again
    'CASE': 'resin',                  # surface name, goes into file names

    # personal calibration: reference rub(s) -> nailsense alpha for this subject/day
    'CAL_FORCE_N': 3.0,
    'CAL_SPEED_MM_S': 20.0,
    'CAL_REPEATS': 2,
    'RUN_NAILSENSE': True,            # False: record only, calibrate later in alpha.ipynb

    # test grid (every force x every speed x repeats)
    'TEST_FORCES_N': [1.0, 2.0, 3.0, 4.0, 5.0],
    'TEST_SPEEDS_MM_S': [10.0, 20.0, 30.0, 40.0],
    'TEST_REPEATS': 1,
    'SHUFFLE': False,                 # True: random order (seeded, order saved in session.json)
    'PREDICT_TEST_TRIALS': True,      # nailsense estimate of each test trial, in the background

    # phone timing
    'PHONE_PREROLL_S': 1.0,           # recording runs this long before the DAQ / X start
    'PHONE_POSTROLL_S': 0.5,          # ... and this long after X stops

    'DATA_ROOT': HERE / 'sessions',
    'NAILSENSE_PY': NAILSENSE_CODE / '.venv' / 'Scripts' / 'python.exe',
}

MANIFEST_COLS = [
    'phase', 'label', 'force_target_N', 'speed_mm_s', 'rep', 'status', 'error',
    'loadcell_file', 'stage_file', 'phone_dir',
    'preload_measured_N',
    'clock_offset_ms', 'clock_rtt_ms',
    'phone_start_pc_unix', 'phone_first_mic_pc_unix', 'daq_start_unix', 'x_cmd_unix', 'x_done_unix',
    'phone_stop_pc_unix',
    'daq_minus_phone_first_mic_s',   # coarse sync: load-cell t=0 sits this far into the phone file
]


# ----------------------------------------------------------------------------- helpers
def preload_from_csv(force_file, daq_start, x_cmd, fs=5000.0, max_s=0.15):
    """|mean Fz| before the X stage moves = the normal force actually applied."""
    n = int(min(max_s, max(0.02, (x_cmd - daq_start) - 0.02)) * fs)
    vals = []
    with open(force_file, encoding='utf-8-sig') as fp:
        r = csv.DictReader(fp)
        for i, row in enumerate(r):
            if i >= n:
                break
            vals.append(float(row['Fz_N']))
    return abs(float(np.mean(vals))) if vals else float('nan')


def run_nailsense(mode, paths, session, **kw):
    py = Path(SESSION['NAILSENSE_PY'])
    if not py.exists():
        raise FileNotFoundError(f'nailsense venv python not found: {py}')
    cmd = [str(py), str(HERE / 'nailsense_session.py'), mode, *map(str, paths),
           '--code', str(NAILSENSE_CODE), '--session', session]
    for k, v in kw.items():
        cmd += [f'--{k}', str(v)]
    return cmd


def parse_last_json(text):
    for line in reversed(text.strip().splitlines()):
        line = line.strip()
        if line.startswith('{'):
            return json.loads(line)
    raise RuntimeError('nailsense gave no result:\n' + text[-2000:])


class Session:
    def __init__(self):
        stamp = time.strftime('%Y%m%d_%H%M%S')
        self.alpha_session = f"{SESSION['SUBJECT']}_{time.strftime('%m%d')}"
        self.root = Path(SESSION['DATA_ROOT']) / f"{SESSION['SUBJECT']}_{stamp}"
        self.lc_dir = self.root / 'loadcell'
        self.phone_dir = self.root / 'phone'
        self.pred_dir = self.root / 'pred'
        for d in (self.lc_dir, self.phone_dir, self.pred_dir):
            d.mkdir(parents=True, exist_ok=True)
        self.manifest = self.root / 'manifest.csv'
        with open(self.manifest, 'w', newline='', encoding='utf-8-sig') as fp:
            csv.writer(fp).writerow(MANIFEST_COLS)
        self.phone = PhoneRecorder()
        self.label = None
        self.trial = {}
        self.pending = []   # (label, Popen, out_csv)

    # ---- hooks called from inside rig.record_5khz_slide
    def before_motion(self, name):
        self.phone.sync_clock(n=10)
        self.trial.update(clock_offset_ms=self.phone.offset_ms, clock_rtt_ms=self.phone.rtt_ms)
        st = self.phone.start(self.label)
        self.trial['phone_start_pc_unix'] = self.phone.phone_ms_to_pc_unix(st['phone_ack_ms'])
        self.trial['phone_recording'] = True
        time.sleep(SESSION['PHONE_PREROLL_S'])

    def after_motion(self, name):
        time.sleep(SESSION['PHONE_POSTROLL_S'])
        sp = self.phone.stop()
        self.trial['phone_recording'] = False
        self.trial['phone_stop_pc_unix'] = self.phone.phone_ms_to_pc_unix(sp['phone_stop_ms'])
        folder = self.phone.pull(sp['name'], self.phone_dir, sp['where'])
        self.trial['phone_dir'] = str(folder)

    def stop_phone_if_running(self):
        if self.trial.get('phone_recording'):
            try:
                self.after_motion('abort')
            except Exception as e:
                print(f'⚠️  could not stop phone recording: {e}')

    # ---- one trial
    def run_trial(self, stages, sensor, fixed_z, phase, force, speed, rep, count):
        stage_X, stage_Z = stages
        self.label = f"{SESSION['SUBJECT']}_{SESSION['CASE']}_{phase}_{force:g}N_{speed:g}mmps_r{rep:02d}"
        self.trial = {}
        rig.TARGET_FORCE_MAGNITUDE = -abs(force)
        rig.CONFIG['TARGET_FORCE_MAGNITUDE'] = -abs(force)
        rig.CONFIG['SLIDE_VELOCITY_MM_S'] = float(speed)
        rig.CONFIG['CASE_NAME'] = f"{SESSION['SUBJECT']}_{SESSION['CASE']}_{phase}"
        print(f'\n=== [{phase}] {self.label} ===')
        row = dict(phase=phase, label=self.label, force_target_N=force, speed_mm_s=speed, rep=rep,
                   status='ok', error='')
        try:
            fixed_z = rig.run_single_trial(stage_X, stage_Z, sensor, fixed_z, count, rep,
                                           return_to_initial=fixed_z is not None)
        except Exception as e:
            self.stop_phone_if_running()
            row.update(status='failed', error=repr(e))
            self.write_row(row)
            raise
        lt = dict(rig.LAST_TRIAL)
        if lt.get('hook_error'):
            row.update(status='phone_failed', error=lt['hook_error'])
        row.update({k: self.trial.get(k, '') for k in
                    ('clock_offset_ms', 'clock_rtt_ms', 'phone_start_pc_unix', 'phone_stop_pc_unix', 'phone_dir')})
        row.update(loadcell_file=lt.get('force_file', ''), stage_file=lt.get('stage_file', ''),
                   daq_start_unix=lt.get('daq_start_unix', ''), x_cmd_unix=lt.get('x_cmd_unix', ''),
                   x_done_unix=lt.get('x_done_unix', ''))
        if lt.get('force_file') and lt.get('x_cmd_unix'):
            row['preload_measured_N'] = preload_from_csv(lt['force_file'], lt['daq_start_unix'], lt['x_cmd_unix'])
        if row.get('phone_dir') and self.phone.offset_ms is not None:
            note = read_session_note(Path(row['phone_dir']))
            if note.get('first_mic'):
                t_mic = self.phone.phone_ms_to_pc_unix(note['first_mic'])
                row['phone_first_mic_pc_unix'] = t_mic
                if lt.get('daq_start_unix'):
                    row['daq_minus_phone_first_mic_s'] = lt['daq_start_unix'] - t_mic
        self.write_row(row)
        print(f"[trial] preload {row.get('preload_measured_N', float('nan')):.3f} N | "
              f"phone {Path(row['phone_dir']).name if row.get('phone_dir') else '-'} | "
              f"DAQ starts {row.get('daq_minus_phone_first_mic_s', float('nan')):.3f} s into phone file")
        return fixed_z, row

    def write_row(self, row):
        with open(self.manifest, 'a', newline='', encoding='utf-8-sig') as fp:
            csv.writer(fp).writerow([row.get(c, '') for c in MANIFEST_COLS])

    # ---- nailsense
    def calibrate(self, cal_rows):
        ok = [r for r in cal_rows if r['status'] == 'ok' and r.get('phone_dir')]
        if not ok:
            raise RuntimeError('No usable calibration trial')
        load = float(np.nanmean([r['preload_measured_N'] for r in ok]))
        cmd = run_nailsense('calibrate', [r['phone_dir'] for r in ok], self.alpha_session,
                            load=f'{load:.4f}', speed=SESSION['CAL_SPEED_MM_S'])
        print(f'\n[calibrate] {len(ok)} reference trial(s), load {load:.3f} N -> session {self.alpha_session}')
        r = subprocess.run(cmd, capture_output=True, text=True, cwd=NAILSENSE_CODE)
        (self.root / 'calibration_log.txt').write_text(r.stdout + '\n' + r.stderr, encoding='utf-8')
        if r.returncode != 0:
            raise RuntimeError('nailsense calibration failed:\n' + r.stderr[-2000:])
        res = parse_last_json(r.stdout)
        print(f"[calibrate] alpha_N={res['alpha_N']:.3f}, alpha_v={res['alpha_v']:.3f} "
              f"({res['n_windows']} windows)")
        return res

    def predict_async(self, row):
        if not row.get('phone_dir'):
            return
        out = self.pred_dir / f"{row['label']}.csv"
        cmd = run_nailsense('predict', [row['phone_dir']], self.alpha_session, out=out)
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, cwd=NAILSENSE_CODE)
        self.pending.append((row, p))

    def report_predictions(self, wait=False):
        still = []
        for row, p in self.pending:
            if p.poll() is None and not wait:
                still.append((row, p))
                continue
            out, err = p.communicate()
            try:
                res = parse_last_json(out)['rows'][0]
                true_n = float(row.get('preload_measured_N') or 'nan')
                print(f"[pred] {row['label']}: N {res['N_pred']:.2f} N (true {true_n:.2f}), "
                      f"v {res['v_pred']:.1f} mm/s (set {row['speed_mm_s']:g}), {res['n_windows']} windows")
            except Exception:
                print(f"[pred] {row['label']}: failed\n{err[-600:]}")
        self.pending = still


# ----------------------------------------------------------------------------- main
def main():
    if not (0.1 <= SESSION['CAL_FORCE_N'] <= 5.0) or not all(0.1 <= f <= 5.0 for f in SESSION['TEST_FORCES_N']):
        raise ValueError('Force range 0.1..5 N')
    if not all(1 <= v <= 40 for v in [SESSION['CAL_SPEED_MM_S'], *SESSION['TEST_SPEEDS_MM_S']]):
        raise ValueError('Speed range 1..40 mm/s')

    grid = [(f, v, k) for f in SESSION['TEST_FORCES_N'] for v in SESSION['TEST_SPEEDS_MM_S']
            for k in range(1, SESSION['TEST_REPEATS'] + 1)]
    if SESSION['SHUFFLE']:
        rng = np.random.default_rng(int(time.time()))
        grid = [grid[i] for i in rng.permutation(len(grid))]

    s = Session()
    rig.CONFIG['personal_depart'] = float(SESSION['PERSONAL_DEPART'])
    rig.CONFIG['HOMING_MODE'] = int(SESSION['HOMING_MODE'])
    rig.CONFIG['SAVE_DIR'] = str(s.lc_dir)
    (s.root / 'session.json').write_text(json.dumps(
        dict(SESSION, DATA_ROOT=str(SESSION['DATA_ROOT']), NAILSENSE_PY=str(SESSION['NAILSENSE_PY']),
             alpha_session=s.alpha_session, test_order=grid), indent=2, ensure_ascii=False), encoding='utf-8')

    print(f"Session folder: {s.root}")
    print(f"Calibration: {SESSION['CAL_FORCE_N']:g} N x {SESSION['CAL_SPEED_MM_S']:g} mm/s x {SESSION['CAL_REPEATS']}")
    print(f"Test grid: {len(grid)} trials")

    # phone first: no point moving stages if the app cannot record
    print('\n[0] Phone app...')
    st = s.phone.connect()
    print(f"✓ app answers ({st}), clock offset {s.phone.offset_ms:+.1f} ms, rtt {s.phone.rtt_ms:.1f} ms")
    input('After verifying stage travel, sensor calibration and physical E-stop, press Enter to connect stages...')

    stage_Z = rig.LinearStage(rig.STAGE_SERIAL_Zaxis, axis_name='Z축')
    stage_X = rig.LinearStage(rig.STAGE_SERIAL_Xaxis, axis_name='X축')
    if not (stage_Z.connect() and stage_X.connect()):
        print('Stage 연결 실패')
        return
    sensor = None
    try:
        stage_Z.load_calibration(rig.calibration_path_Zaxis)
        stage_X.load_calibration(rig.calibration_path_Xaxis)
        rig.setup_initial_stage_positions(stage_X, stage_Z)
        sensor = rig.LoadCellSensor(rig.CALIB_FILE, rig.CALIB_INDEX, rig.DAQ_CHANNELS)
        rig.start_daq(sensor)
        rig.TRIAL_HOOKS['before_motion'] = s.before_motion
        rig.TRIAL_HOOKS['after_motion'] = s.after_motion

        stages, fixed_z, count = (stage_X, stage_Z), None, 0

        # ---- 1. personal calibration
        while True:
            cal_rows = []
            for k in range(1, SESSION['CAL_REPEATS'] + 1):
                count += 1
                fixed_z, row = s.run_trial(stages, sensor, fixed_z, 'cal',
                                           SESSION['CAL_FORCE_N'], SESSION['CAL_SPEED_MM_S'], k, count)
                cal_rows.append(row)
            if not SESSION['RUN_NAILSENSE']:
                break
            try:
                s.calibrate(cal_rows)
                break
            except Exception as e:
                print(f'❌ {e}')
                ans = input('Calibration failed. [r] repeat reference trials / [c] continue without / [q] quit: ').strip().lower()
                if ans == 'c':
                    SESSION['PREDICT_TEST_TRIALS'] = False
                    break
                if ans != 'r':
                    raise

        # ---- 2. force x speed test
        input(f'\nCalibration done. Press Enter to start the {len(grid)}-trial test grid...')
        for i, (force, speed, rep) in enumerate(grid, 1):
            count += 1
            print(f'\n--- test {i}/{len(grid)} ---')
            fixed_z, row = s.run_trial(stages, sensor, fixed_z, 'test', force, speed, rep, count)
            if SESSION['RUN_NAILSENSE'] and SESSION['PREDICT_TEST_TRIALS']:
                s.predict_async(row)
            s.report_predictions()
        s.report_predictions(wait=True)
        print(f'\n✓ Session complete: {s.root}')

    except Exception as e:
        print(f'[ABORT] {e}')
        traceback.print_exc()
        try:
            stage_X.stop(); stage_Z.stop()
        except Exception:
            pass
        try:  # an abort can leave the plate pressed on the finger: lift Z by 15 mm
            z = stage_Z.get_position()
            stage_Z.Zaxis_MoveTo(min(z + 15.0, rig.STAGE_MAX_POS), vel=1.0, acc=5.0)
        except Exception as e2:
            print(f'⚠️  Z retract after abort failed: {e2} — retract manually')
    finally:
        rig.TRIAL_HOOKS['before_motion'] = rig.TRIAL_HOOKS['after_motion'] = None
        rig.exit_event.set()
        try:
            rig.stop_daq()
        except Exception:
            pass
        stage_Z.disconnect(); stage_X.disconnect()
        s.phone.close()
        print('✓ 프로그램 종료')


if __name__ == '__main__':
    main()
