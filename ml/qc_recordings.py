"""
İP2 dataset quality check: format, duration, level, clipping and noise floor per clip.

Runs locally on the recordings folder (voice data never leaves the PC - KVKK).
Output: <recordings>/qc.csv + a short summary per speaker/condition.

Usage (from the ml folder):
    .venv\\Scripts\\python.exe qc_recordings.py --recordings ..\\data\\recordings
"""
import argparse
import wave
from pathlib import Path

import numpy as np
import pandas as pd

FRAME = 400  # 25 ms at 16 kHz


def clip_stats(path: Path) -> dict:
    with wave.open(str(path), "rb") as w:
        sr, ch, sw, n = w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes()
        x = np.frombuffer(w.readframes(n), dtype=np.int16).astype(np.float32) / 32768
    fr = x[: len(x) // FRAME * FRAME].reshape(-1, FRAME)
    e = 10 * np.log10((fr ** 2).mean(1) + 1e-12)
    floor, speech = np.percentile(e, 10), np.percentile(e, 95)
    return dict(
        format_ok=(sr, ch, sw) == (16000, 1, 2),
        duration_s=round(n / sr, 2),
        peak=round(float(np.abs(x).max()), 3),
        clipped_ratio=round(float((np.abs(x) > 0.99).mean()), 5),
        noise_floor_db=round(float(floor), 1),
        speech_level_db=round(float(speech), 1),
        snr_db=round(float(speech - floor), 1),
    )


def flags(r) -> str:
    f = []
    if not r.format_ok: f.append("format")
    if r.duration_s < 1.0: f.append("too_short")
    if r.duration_s > 9.5: f.append("too_long")  # phone model window is 10 s
    if r.speech_level_db < -40: f.append("too_quiet")
    if r.clipped_ratio > 0.001: f.append("clipping")
    if r.snr_db < 10: f.append("low_snr")
    return "|".join(f)


def main(args):
    rec = Path(args.recordings)
    man = pd.read_csv(rec / "manifest.csv", dtype={"speaker": str})
    man = man.sort_values("timestamp").drop_duplicates(["speaker", "condition", "prompt_id"], keep="last")
    rows = []
    for m in man.itertuples():
        p = rec / m.file
        if not p.exists():
            rows.append(dict(speaker=m.speaker, condition=m.condition, prompt_id=m.prompt_id, file=m.file, flags="missing"))
            continue
        s = clip_stats(p)
        s.update(speaker=m.speaker, condition=m.condition, prompt_id=m.prompt_id, file=m.file)
        rows.append(s)
    df = pd.DataFrame(rows)
    if "flags" not in df:
        df["flags"] = ""
    df["flags"] = [f if f == "missing" else flags(r) for f, r in zip(df["flags"], df.itertuples())]
    cols = ["speaker", "condition", "prompt_id", "file", "duration_s", "peak", "clipped_ratio",
            "noise_floor_db", "speech_level_db", "snr_db", "format_ok", "flags"]
    df = df[cols]
    df.to_csv(rec / "qc.csv", index=False)

    summ = df.groupby(["speaker", "condition"]).agg(
        clips=("file", "count"),
        dur_median=("duration_s", "median"),
        noise_floor_median=("noise_floor_db", "median"),
        snr_median=("snr_db", "median"),
        flagged=("flags", lambda s: (s != "").sum()),
    ).round(1)
    pd.set_option("display.width", 160)
    print(summ.to_string())
    bad = df[df["flags"] != ""]
    if len(bad):
        print(f"\n{len(bad)} flagged clips (listen to these; re-record if unintelligible):")
        print(bad[["file", "duration_s", "snr_db", "flags"]].to_string(index=False))
    print(f"\nsaved {rec / 'qc.csv'}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--recordings", required=True)
    main(ap.parse_args())
