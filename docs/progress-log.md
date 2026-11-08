# Progress log

Each entry: date – what was done – which work package (İP).

| Date | Work | İP |
|---|---|---|
| 2026-10-05 | Android Studio project created (Kotlin, Compose, min SDK 26) | İP1 |
| 2026-10-05 | Repo structure set up (android / ml / data / docs) | İP1 |
| 2026-10-05 | 30 pilot commands prepared (18 mixed, 6 TR, 6 EN) | İP1 / İP2 |
| 2026-10-05 | Pilot recorder app (16 kHz WAV + manifest) and Whisper benchmark script written | İP2 / İP3 |
| 2026-10-12 | Intent set (8) + 90-command list finalized (47 mixed, 26 TR, 17 EN, 10 deliberately ambiguous); `data/commands.csv` + `docs/IP1_Komut_Listesi_v1.docx` | İP1 |
| 2026-10-17 | Pilot recordings S01 (30 clips, quiet, emulator mic); clean audio (~30 dB SNR) | İP2 / İP3 |
| 2026-10-17 | Whisper pilot benchmark v1 (tiny/base/small × auto/tr/tr_prompt): free LID fails on S01 (sw/pl/ro…), prompt leakage, repetition loops | İP3 |
| 2026-10-17 | Benchmark v2: TR/EN-restricted LID, vocab prompt, temperature fallback. EN WER 0.12 (small) / 0.18 (base); TR & mixed still poor for S01 → need native-speaker pilots; small too slow for phone | İP3 |
| 2026-10-17 | KVKK aydınlatma metni + açık rıza + katılımcı bilgi formu (`docs/KVKK_Aydinlatma_ve_Acik_Riza_Formu.docx`) | İP2 |
| 2026-10-21 | Requirements specification v1.0 (ISO/IEC/IEEE 29148 style: 18 FR, 10 NFR, 6 use cases, H1–H5 traceability) – `docs/IP1_Gereksinim_Belirtimi_v1.docx` | İP1 |
| 2026-10-21 | Benchmark: Turkish-default TR/EN language rule (`lid_bias`, FR-04); incremental config caching | İP3 |
| 2026-10-21 | Benchmark v3 results: `lid_bias_prompt` best for all models (small mixed WER 0.93→0.72, EN 0.12); base = on-device candidate, small = accuracy reference | İP3 |
| 2026-10-22 | Literature review v1 (24 sources, 10 sections, gap analysis table) – `docs/IP1_Literatur_Taramasi_v1.docx` → İP1 complete pending advisor approval | İP1 |
| 2026-10-21 | Pilot recordings S02–S04 (native TR speakers; S04 noisy), KVKK consent signed; 120 clips total | İP2 |
| 2026-10-21 | Benchmark per speaker: `lid_bias_prompt` beats `auto` for all 4 speakers. small: native mixed WER 0.40–0.57 vs S01 0.72; TR 0.19–0.52 vs 0.74 → accent is a major factor, but code-switched commands stay harder than TR/EN even for natives | İP3 |
| 2026-10-21 | On-device ASR in the app: whisper.cpp via JNI, lid_bias_prompt logic, batch test. Speed fixes: 10 s encoder window, bounded retries, ARM dotprod/fp16 (short word 10.9 s → ~2.5 s) | İP3 |
| 2026-10-21 | On-device batch (phone, base-q5_1, 10 s window, 120 clips): latency median 2.75 s, p90 2.87 s, max 2.96 s (H4 ✓); WER EN 0.37 / mixed 0.67 / TR 0.81, CER 0.20 / 0.26 / 0.23 – on par with PC base | İP3 |
| 2026-10-21 | On-device small-q5_1 batch: ~10.2 s before the phone heated up, 17.9 s full-run median (H4 ✗), WER EN 0.29 / mixed 0.54 / TR 0.51 → **model decision: base-q5_1, 10 s window, lid_bias_prompt**; small kept as accuracy reference | İP3 |
| 2026-10-26 | İP3 results report v1 (`docs/IP3_ASR_Raporu_v1.docx`): ablation, model comparison, speaker effect, on-device latency, success criteria | İP3 |
| 2026-10-26 | Recorder updated to the full 90-command list (`commands.csv`): pilot recordings recognised, progress counter, jump to next missing command | İP2 |
| 2026-10-30 | Full command list recorded by S01–S03 (quiet): dataset now 301 clips (S01 90, S02 90, S03 91, S04 30 noisy); S04's remaining 60 noisy clips to be re-recorded | İP2 |
| 2026-10-30 | Dataset QC script (`ml/qc_recordings.py` → `qc.csv`): all clips 16 kHz mono 16-bit, 1.8–7.7 s, no clipping; S03 "quiet" room has a ~18 dB higher noise floor (low-frequency hum, ≈ −29 dBFS) than S01/S02; 3 clips < 10 dB SNR flagged for listening | İP2 |
| 2026-10-30 | Found phone batch runs slowed by heat / screen-off (latency drift up to 1.8×). App now keeps the screen on during batch tests and logs power-save + thermal state per clip; `score_device.py` flags throttled runs and uses the latest valid one. İP3 report Table 3 corrected (small ≈ 10.2 s unthrottled) | İP3 |
| 2026-11-02 – 11-07 | Speakers S05 and S06 recorded the full 90-command list (quiet) on a second phone; merged into `data/recordings` → **481 clips, 6 speakers** (S01 90, S02 90, S03 91, S04 30 noisy, S05 90, S06 90) | İP2 |
| 2026-11-07 | QC of S05/S06: 180/180 pass, 0 flagged; noise floor ≈ −62 dBFS, SNR ≈ 40–46 dB (cleanest in the set). Phone 2 starts every clip with 0.1–0.3 s digital silence and likely applies built-in noise suppression → results reported per recording device (phone 1: S01–S04, phone 2: S05–S06) | İP2 |
| 2026-11-07 | S05 and S06 are native Turkish speakers → 5 native + 1 non-native (S01). 6 speakers make a speaker-independent split possible for İP4 (e.g. 4 train / 2 test, no speaker in both) | İP2 |
| 2026-11-08 | PC benchmark on the full dataset (481 clips, 6 speakers; tiny/base/small × auto/lid_bias_prompt). `lid_bias_prompt` best for every model: small WER 0.65→0.39 (EN 0.15 / mixed 0.51 / TR 0.37), base 0.73→0.57 (EN 0.19 / mixed 0.73 / TR 0.63), tiny 0.74 | İP3 |
| 2026-11-08 | Per speaker (small, lid_bias_prompt): S05 0.22, S02 0.33, S04 0.40, S06 0.41, S03 0.46, S01 0.50. Device/noise is not the main factor (S05 and S06 on the same clean phone differ 0.22 vs 0.41); code-switched commands stay hardest for every speaker | İP3 |
| 2026-11-08 | Prompt check for unsupervised sessions S05/S06: every transcript is a phonetic rendering of its target sentence (e.g. "boss wife, iftar let" ← "Boşver, iptal et"); no wrongly read commands → no re-recording needed | İP2 |
