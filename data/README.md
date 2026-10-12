# data

- `pilot/prompts.csv` – the 30 pilot commands (18 mixed, 6 Turkish, 6 English).
  Columns: `id,intent,lang_type,en_terms,text` (`en_terms` = English words inside a mixed command, `|`-separated).
  **A copy lives in `android/app/src/main/assets/prompts.csv` – keep the two in sync.**
- `recordings/` – audio pulled from the phone. **Git-ignored (KVKK): never commit voice data.**

## Pulling recordings from the phone
1. Connect the phone by USB and allow file transfer.
2. Copy `Android/data/com.ben.karmaasistan/files/recordings/` into `data/recordings/`
   (or run `adb pull /sdcard/Android/data/com.ben.karmaasistan/files/recordings data/`).
3. The folder contains `manifest.csv` and `<speaker>/<condition>/<prompt_id>.wav` (16 kHz, mono, 16-bit).
