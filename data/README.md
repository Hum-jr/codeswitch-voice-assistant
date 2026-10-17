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
