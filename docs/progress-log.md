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
