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

from benchmark_whisper import DEFAULT_PROMPTS, normalize


def main(args):
    rec = Path(args.recordings)
    files = sorted(rec.glob("asr_*.csv"))
    if not files:
        raise SystemExit(f"no asr_*.csv files in {rec}")
    prompts = pd.read_csv(args.prompts, dtype=str, keep_default_na=False)
    d = pd.concat([pd.read_csv(f, dtype={"speaker": str}, keep_default_na=False).assign(run=f.name)
                   for f in files], ignore_index=True)
    # if a model was batch-tested several times, keep its latest run
    latest = d.groupby("model")["run"].max()
    d = d[d.run == d.model.map(latest)]
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
    print("\n=== WER by language type ===")
    print(d.groupby(["model", "lang_type"]).apply(agg, include_groups=False).round(3).to_string())
    print("\n=== WER by speaker ===")
    by_sp = d.groupby(["model", "speaker", "condition", "lang_type"]).apply(agg, include_groups=False).reset_index()
    print(by_sp.pivot_table(index=["model", "speaker", "condition"], columns="lang_type", values="WER").round(2).to_string())

    out = rec / "asr_device_summary.csv"
    by_sp.to_csv(out, index=False, encoding="utf-8-sig")
    print(f"\nsaved {out}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--recordings", required=True)
    p.add_argument("--prompts", default=str(DEFAULT_PROMPTS))
    main(p.parse_args())
