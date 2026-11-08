"""
Score on-device ASR results produced by the app's "Toplu test" button.

The app writes recordings/asr_<model>_<time>.csv on the phone; pull the recordings
folder to data/recordings and run:
    python score_device.py --recordings ../../data/recordings
Prints WER/CER per model × speaker × language type and the on-device latency
(median / 90th percentile) that H4 (≤ 3 s) is judged on.
"""
import argparse
from pathlib import Path

import jiwer
import pandas as pd

from benchmark_whisper import DEFAULT_PROMPTS, load_prompt_table, normalize


def main(args):
    rec = Path(args.recordings)
    # batch result files only (the summary written below has a different name)
    files = sorted(f for f in rec.glob("asr_*.csv") if not f.name.startswith("asr_device_summary"))
    if not files:
        raise SystemExit(f"no asr_*.csv files in {rec}")
    prompts = load_prompt_table(args.prompts)
    d = pd.concat([pd.read_csv(f, dtype={"speaker": str}, keep_default_na=False).assign(run=f.name)
                   for f in files], ignore_index=True)
    # Several runs per model: keep the latest one that was NOT throttled (latency drift <= 1.3x
    # between the first and last 20% of the run); fall back to the latest run.
    def drift(g):
        q = g.latency_ms.reset_index(drop=True)
        k = max(1, len(q) // 5)
        return q[-k:].median() / q[:k].median()
    runs = d.groupby("run").agg(model=("model", "first")).assign(
        drift=d.groupby("run").apply(drift, include_groups=False))
    runs["ok"] = runs.drift <= 1.3
    chosen = (runs.reset_index().sort_values(["model", "ok", "run"])
              .groupby("model").tail(1).set_index("model")["run"])
    skipped = sorted(set(runs.index) - set(chosen))
    if skipped:
        print("ignored runs (older or throttled):", ", ".join(skipped))
    d = d[d.run.isin(chosen)]
    d = d.merge(prompts[["id", "lang_type", "text"]], left_on="prompt_id", right_on="id", how="left")
    d["ref_norm"] = d.text.map(normalize)
    d["hyp_norm"] = d.hypothesis.astype(str).map(normalize).replace("", "<empty>")

    def agg(g):
        return pd.Series(dict(
            n=len(g),
            WER=jiwer.wer(list(g.ref_norm), list(g.hyp_norm)),
            CER=jiwer.cer(list(g.ref_norm), list(g.hyp_norm)),
            latency_median_s=g.latency_ms.median() / 1000,
        ))

    pd.set_option("display.width", 200)
    print("runs:", ", ".join(sorted(d.run.unique())))
    print("\n=== On-device latency (H4 target: median ≤ 3 s) ===")
    lat = d.groupby("model").agg(
        n=("latency_ms", "size"),
        median_s=("latency_ms", lambda x: x.median() / 1000),
        p90_s=("latency_ms", lambda x: x.quantile(0.9) / 1000),
        max_s=("latency_ms", lambda x: x.max() / 1000),
        RTF=("latency_ms", lambda x: x.sum() / 1000 / d.loc[x.index, "audio_s"].sum()),
    )
    print(lat.round(2).to_string())

    # Validity check: throttled phones (screen off, power saving, heat) give invalid latency.
    print("\n=== Run validity (latency drift and device state) ===")
    for run, g in d.groupby("run", sort=False):
        q = g.latency_ms.reset_index(drop=True)
        k = max(1, len(q) // 5)
        drift = q[-k:].median() / q[:k].median()
        notes = [f"first/last 20% median {q[:k].median() / 1000:.2f} s -> {q[-k:].median() / 1000:.2f} s (x{drift:.2f})"]
        if "power_save" in g:
            notes.append(f"power-save clips: {(g.power_save == 1).sum()}")
        if "thermal" in g:
            notes.append(f"thermal>=2 clips: {(g.thermal >= 2).sum()}")
        flag = "  <-- CHECK: likely throttled" if drift > 1.3 else ""
        print(f"{run}: " + "; ".join(notes) + flag)
    print("\n=== WER by language type ===")
    print(d.groupby(["model", "lang_type"]).apply(agg, include_groups=False).round(3).to_string())
    print("\n=== WER by speaker ===")
    by_sp = d.groupby(["model", "speaker", "condition", "lang_type"]).apply(agg, include_groups=False).reset_index()
    print(by_sp.pivot_table(index=["model", "speaker", "condition"], columns="lang_type", values="WER").round(2).to_string())

    out = rec / "device_asr_summary.csv"
    by_sp.to_csv(out, index=False, encoding="utf-8-sig")
    print(f"\nsaved {out}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--recordings", required=True)
    p.add_argument("--prompts", default=str(DEFAULT_PROMPTS))
    main(p.parse_args())
