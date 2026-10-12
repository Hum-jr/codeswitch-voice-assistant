# Karma Asistanı

**Türkçe–İngilizce Karma Sesli Komutlarda Belirsizlik Duyarlı Mobil Görev Asistanı**
Bitirme Ödevi – Mersin Üniversitesi Bilgisayar Mühendisliği
Öğrenci: Ben Hamphrey Nyangiye · Danışman: Doç. Dr. Çiğdem Acı

An offline Android task assistant that understands mixed Turkish–English voice commands
("Yarın meeting için hatırlatma ekle") and asks a short clarifying question instead of guessing
when it is unsure.

## Layout
| Folder | What |
|---|---|
| `android/` | Android Studio project (Kotlin, Jetpack Compose). Currently: pilot recorder app. |
| `ml/` | Python: Whisper experiments, intent classifier, ONNX export. |
| `data/` | Prompts and (git-ignored) recordings. |
| `docs/` | Progress log, requirements, reports. |

## Current step – Whisper pilot (İP3)
1. Open `android/` in Android Studio, run on the phone, record the 30 prompts (speaker `S01`, quiet).
2. Pull recordings into `data/recordings/` (see `data/README.md`).
3. Run the benchmark:
   ```bash
   cd ml
   python -m venv .venv && .venv\Scripts\activate      # Windows
   pip install -r requirements.txt
   python asr_pilot/benchmark_whisper.py --recordings ../data/recordings
   ```
   Results land in `ml/asr_pilot/results/<timestamp>/summary.csv`
   (WER, CER, English-term retention, latency per model × config × language type).
   No GPU? Upload `ml/` + `data/` to Google Colab and run the same command there.
