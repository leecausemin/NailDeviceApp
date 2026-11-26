"""Runs nailsense for the session script, inside nailsense's own venv (scikit-learn is pinned there,
and the rig's pythonnet / nidaqmx environment never has to import it).

    <code>\\.venv\\Scripts\\python.exe nailsense_session.py calibrate --code <code dir> --session LJW_1126 \
        --load 3.02 --speed 20 <nail_ dir> [<nail_ dir> ...]
    <code>\\.venv\\Scripts\\python.exe nailsense_session.py predict --code <code dir> --session LJW_1126 \
        --out pred.csv <nail_ dir>

The last stdout line is one JSON object for the caller.
"""
import argparse
import json
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["calibrate", "predict"])
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--code", required=True, help="nailsense repo (folder with nailsense/ and models/)")
    ap.add_argument("--model", default="models/force_speed_pipeline.pkl", help="relative to --code")
    ap.add_argument("--session", required=True, help="alpha key, e.g. LJW_1126")
    ap.add_argument("--load", type=float)
    ap.add_argument("--speed", type=float)
    ap.add_argument("--out")
    a = ap.parse_args()

    code = Path(a.code).resolve()
    sys.path.insert(0, str(code))
    from nailsense import ForceSpeedPipeline
    import numpy as np

    model = code / a.model
    pipe = ForceSpeedPipeline.load(model)

    if a.mode == "calibrate":
        if a.load is None or a.speed is None:
            raise SystemExit("calibrate needs --load and --speed")
        ref = pipe.calibrate_from_paths(a.paths, session=a.session, load=a.load, speed=a.speed)
        pipe.save(model)
        aN, av = np.exp(pipe.calibration.log_alpha[a.session])
        print(json.dumps(dict(ok=True, session=a.session, alpha_N=float(aN), alpha_v=float(av),
                              n_windows=int(len(ref)))))
    else:
        windows, summary = pipe.predict_sessions(a.paths, session=a.session)
        if a.out:
            windows.to_csv(a.out, index=False)
            summary.to_csv(Path(a.out).with_name(Path(a.out).stem + "_summary.csv"), index=False)
        print(json.dumps(dict(ok=True, rows=json.loads(summary.to_json(orient="records")))))


if __name__ == "__main__":
    main()
