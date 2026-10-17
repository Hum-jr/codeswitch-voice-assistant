"""
İP3 pilot: compare Whisper tiny/base/small on mixed Turkish–English commands.

Input : a recordings folder pulled from the phone (contains manifest.csv + WAVs)
        and data/pilot/prompts.csv (reference texts).
Output: results/<run>/per_clip.csv and results/<run>/summary.csv

Usage:
    python benchmark_whisper.py --recordings ../../data/recordings --models tiny base small
"""
import argparse
import re
import time
import unicodedata
import wave
from pathlib import Path

import jiwer
import numpy as np
import pandas as pd
import torch
import whisper

REPO = Path(__file__).resolve().parents[2]
DEFAULT_PROMPTS = REPO / "data" / "pilot" / "prompts.csv"

# Bump when decoding settings change so cached results are recomputed.
RUN_VERSION = "v2"

# v1 lessons (pilot S01, 2026-10-17):
#  - free language ID picked Swahili/Polish/Korean/... for Turkish speech  -> restrict LID to tr/en
#  - a sentence-style prompt was copied into the output                   -> vocabulary-only prompt
#  - temperature=0 without fallback produced repetition loops            -> Whisper's default fallback
VOCAB_PROMPT = "meeting, task, deadline, reminder, snooze, toplantı, görev, hatırlatma"
TEMPERATURES = (0.0, 0.2, 0.4, 0.6, 0.8, 1.0)  # Whisper default; retried when output looks like a loop

# Decoding configurations to compare.
#   lid = detect the language first, but only choose between Turkish and English
CONFIGS = {
    "auto": dict(),
    "tr": dict(language="tr"),
    "tr_prompt": dict(language="tr", initial_prompt=VOCAB_PROMPT),
    "lid": dict(lid=True),
    "lid_prompt": dict(lid=True, initial_prompt=VOCAB_PROMPT),
}


# ---------------------------------------------------------------- text utils
def tr_lower(s: str) -> str:
    """Lowercase per word: Turkish rules (İ→i, I→ı) for words with Turkish letters,
    plain lowercasing for ASCII-only words so English 'DEADLINE' stays 'deadline'."""
    def one(w: str) -> str:
        if w.isascii():
            return w.lower()
        return w.replace("İ", "i").replace("I", "ı").lower()
    # stem and suffix are cased separately: DEADLINE'ı → deadline'ı
    return " ".join("'".join(one(p) for p in w.split("'")) for w in s.split())


def normalize(s: str) -> str:
    s = unicodedata.normalize("NFC", s)
    s = s.replace("’", "'").replace("`", "'").replace("‘", "'")
    s = tr_lower(s)
    s = s.replace("'", "")            # meeting'i == meetingi
    s = re.sub(r"[^\w\s]", " ", s)    # drop punctuation, keep Turkish letters
    return re.sub(r"\s+", " ", s).strip()


def term_retention(terms: list[str], hyp_norm: str) -> float | None:
    """Share of English terms (meeting, task…) that survive transcription."""
    if not terms:
        return None
    words = hyp_norm.split()
    hits = sum(any(w.startswith(normalize(t)) for w in words) for t in terms)
    return hits / len(terms)


# ---------------------------------------------------------------- io
def load_wav(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, \
            f"{path} must be 16 kHz mono 16-bit"
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return pcm.astype(np.float32) / 32768.0


def load_clips(recordings: Path, prompts_csv: Path) -> pd.DataFrame:
    prompts = pd.read_csv(prompts_csv, dtype=str, keep_default_na=False)
    man = pd.read_csv(recordings / "manifest.csv", dtype={"speaker": str})
    # re-recordings append rows: keep the latest per speaker/condition/prompt
    man = man.sort_values("timestamp").drop_duplicates(
        ["speaker", "condition", "prompt_id"], keep="last"
    )
    df = man.merge(prompts, left_on="prompt_id", right_on="id", how="left")
    missing = df["text"].isna().sum()
    if missing:
        print(f"warning: {missing} clips have no matching prompt, skipped")
    df = df.dropna(subset=["text"])
    df["path"] = df["file"].map(lambda f: recordings / f)
    return df[df["path"].map(Path.exists)].reset_index(drop=True)


# ---------------------------------------------------------------- model loading
def load_model_with_retry(name: str, device: str, attempts: int = 3):
    """Large downloads (small = 461 MB) can break on slow connections and fail the
    checksum. Whisper re-downloads a file whose checksum is wrong, so just retry."""
    for i in range(1, attempts + 1):
        try:
            return whisper.load_model(name, device=device)
        except RuntimeError as e:
            if "SHA256" not in str(e) or i == attempts:
                raise
            print(f"download of '{name}' was corrupted, retrying ({i}/{attempts - 1})...")


# ---------------------------------------------------------------- main
def run(args):
    device = "cuda" if torch.cuda.is_available() else "cpu"
    clips = load_clips(Path(args.recordings), Path(args.prompts))
    clip_keys = sorted(f"{c.speaker}|{c.condition}|{c.prompt_id}|{c.timestamp}" for c in clips.itertuples())
    print(f"{len(clips)} clips, device={device}")

    # per-model results are cached so a crash or a re-run never repeats finished models
    cache_dir = Path(args.out) / "_cache"
    cache_dir.mkdir(parents=True, exist_ok=True)

    frames = []
    for model_name in args.models:
        cache_file = cache_dir / f"{model_name}_{RUN_VERSION}.csv"
        if cache_file.exists() and not args.force:
            cached = pd.read_csv(cache_file, dtype={"speaker": str}, encoding="utf-8-sig", keep_default_na=False,
                                 na_values=[""])
            if (sorted(cached["clip_key"].unique()) == clip_keys
                    and set(cached["config"]) == set(CONFIGS)):
                print(f"skip: {model_name} (cached results for the same clips)")
                frames.append(cached)
                continue
        rows = []
        model = load_model_with_retry(model_name, device)
        for cfg_name, cfg in CONFIGS.items():
            for c in clips.itertuples():
                audio = load_wav(c.path)
                opts = dict(cfg)
                use_lid = opts.pop("lid", False)
                t0 = time.perf_counter()
                p_tr = p_en = np.nan
                if use_lid:
                    mel = whisper.log_mel_spectrogram(
                        whisper.pad_or_trim(audio), n_mels=model.dims.n_mels
                    ).to(model.device)
                    _, probs = model.detect_language(mel)
                    p_tr, p_en = probs.get("tr", 0.0), probs.get("en", 0.0)
                    opts["language"] = "tr" if p_tr >= p_en else "en"
                res = model.transcribe(
                    audio,
                    temperature=TEMPERATURES,
                    compression_ratio_threshold=2.4,
                    logprob_threshold=-1.0,
                    no_speech_threshold=0.6,
                    fp16=(device == "cuda"),
                    condition_on_previous_text=False,
                    **opts,
                )
                latency = time.perf_counter() - t0
                segs = res.get("segments") or []
                hyp = res["text"].strip()
                hyp_n = normalize(hyp)
                terms = [t for t in c.en_terms.split("|") if t]
                rows.append(dict(
                    clip_key=f"{c.speaker}|{c.condition}|{c.prompt_id}|{c.timestamp}",
                    model=model_name, config=cfg_name, speaker=c.speaker,
                    condition=c.condition, prompt_id=c.prompt_id, lang_type=c.lang_type,
                    intent=c.intent, reference=c.text, hypothesis=hyp,
                    ref_norm=normalize(c.text), hyp_norm=hyp_n,
                    detected_lang=res.get("language"),
                    lid_p_tr=p_tr, lid_p_en=p_en,
                    avg_logprob=np.mean([s["avg_logprob"] for s in segs]) if segs else np.nan,
                    no_speech_prob=np.mean([s["no_speech_prob"] for s in segs]) if segs else np.nan,
                    en_term_retention=term_retention(terms, hyp_n),
                    latency_s=latency, audio_s=len(audio) / 16000,
                ))
            print(f"done: {model_name}/{cfg_name}")
        del model
        if device == "cuda":
            torch.cuda.empty_cache()
        model_df = pd.DataFrame(rows)
        model_df.to_csv(cache_file, index=False, encoding="utf-8-sig")
        frames.append(model_df)

    per_clip = pd.concat(frames, ignore_index=True)
    per_clip["hyp_norm"] = per_clip["hyp_norm"].fillna("").astype(str).replace("", "<empty>")

    def agg(g):
        return pd.Series(dict(
            n=len(g),
            WER=jiwer.wer(list(g.ref_norm), list(g.hyp_norm)),
            CER=jiwer.cer(list(g.ref_norm), list(g.hyp_norm)),
            en_term_retention=g.en_term_retention.dropna().mean(),
            mean_latency_s=g.latency_s.mean(),
            RTF=g.latency_s.sum() / g.audio_s.sum(),
        ))

    keys = ["model", "config", "condition", "lang_type"]
    summary = per_clip.groupby(keys).apply(agg, include_groups=False).reset_index()

    out = Path(args.out) / time.strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=True)
    per_clip.to_csv(out / "per_clip.csv", index=False, encoding="utf-8-sig")
    summary.to_csv(out / "summary.csv", index=False, encoding="utf-8-sig")
    pd.set_option("display.width", 160)
    print(summary.round(3).to_string(index=False))
    print(f"\nsaved to {out}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--recordings", required=True, help="folder with manifest.csv from the phone")
    p.add_argument("--prompts", default=str(DEFAULT_PROMPTS))
    p.add_argument("--models", nargs="+", default=["tiny", "base", "small"])
    p.add_argument("--out", default=str(Path(__file__).parent / "results"))
    p.add_argument("--force", action="store_true", help="ignore cached results and re-run all models")
    run(p.parse_args())
