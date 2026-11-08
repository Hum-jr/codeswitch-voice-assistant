# data

- `commands.csv` – **main command list (İP1)**: 90 commands, 8 intents.
  Columns: `id,intent,lang_type,ambiguity,alt_intent,pilot_id,en_terms,text`.
  `ambiguity` = none | intent | slot | reference (deliberately ambiguous commands, labelled with the most likely intent;
  `alt_intent` = the other plausible reading). `pilot_id` links to the 30 pilot prompts.
  Human-readable version for the advisor: `docs/IP1_Komut_Listesi_v1.docx`.
- `pilot/prompts.csv` – the 30 pilot commands (18 mixed, 6 Turkish, 6 English).
  Columns: `id,intent,lang_type,en_terms,text` (`en_terms` = English words inside a mixed command, `|`-separated).
  **A copy lives in `android/app/src/main/assets/prompts.csv` – keep the two in sync.**
- `recordings/` – audio pulled from the phone. **Git-ignored (KVKK): never commit voice data.**

## Pulling recordings from the phone
1. Connect the phone by USB and allow file transfer.
2. Copy `Android/data/com.ben.karmaasistan/files/recordings/` into `data/recordings/`
   (or run `adb pull /sdcard/Android/data/com.ben.karmaasistan/files/recordings data/`).
3. The folder contains `manifest.csv` and `<speaker>/<condition>/<prompt_id>.wav` (16 kHz, mono, 16-bit).

## Dataset status (İP2, 2026-11-07)
| Speaker | Condition | Clips | Device | Note |
|---|---|---|---|---|
| S01 | quiet | 90 | phone 1 | non-native TR speaker |
| S02 | quiet | 90 | phone 1 | native TR |
| S03 | quiet | 91 | phone 1 | native TR; room has low-frequency hum (noise floor ≈ −29 dBFS vs −47 for S01/S02) |
| S04 | noisy | 30 | phone 1 | native TR; remaining 60 commands to be re-recorded |
| S05 | quiet | 90 | phone 2 | native TR; very clean (noise floor ≈ −61 dBFS) |
| S06 | quiet | 90 | phone 2 | native TR; very clean, slightly quieter voice level |

**Total: 481 clips, 6 speakers.** Phone 2 inserts 0.1–0.3 s digital silence at the start of each clip and probably applies noise suppression, so compare results per device.
Recordings from a new phone: pull into `data/recordings_phoneN`, copy the speaker folders into `recordings/` and append its `manifest.csv` rows (never overwrite the main manifest).

Quality check (no voice data leaves the PC):
`ml\.venv\Scripts\python.exe ml\qc_recordings.py --recordings data\recordings` → writes `data/recordings/qc.csv`
(duration, peak, clipping, noise floor, SNR, flags). Listen to every flagged clip; re-record only if unintelligible.
