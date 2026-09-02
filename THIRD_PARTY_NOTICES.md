# Drittanbieterhinweise

## whisper.cpp

- Projekt: <https://github.com/ggml-org/whisper.cpp>
- Copyright: 2023–2026 The ggml authors
- Lizenz: MIT
- Verwendung: Lokale Whisper-Inferenz und Android-JNI-Anbindung

Der vollständige Lizenztext ist in
[`licenses/whisper.cpp-MIT.txt`](licenses/whisper.cpp-MIT.txt) enthalten.

Das Whisper-Modell wird nicht mit der APK ausgeliefert. Die App lädt das vom
`whisper.cpp`-Projekt bereitgestellte GGML-Modell erst auf Wunsch des Nutzers.

## Silero VAD

- Projekt: <https://github.com/snakers4/silero-vad>
- Copyright: 2020–heute Silero Team
- Lizenz: MIT
- Verwendung: Optional herunterladbare lokale Sprachaktivitätserkennung

Das VAD-Modell wird nicht mit der APK ausgeliefert. Der vollständige Lizenztext
ist in [`licenses/Silero-VAD-MIT.txt`](licenses/Silero-VAD-MIT.txt) enthalten.

## llama.cpp

- Projekt: <https://github.com/ggml-org/llama.cpp>
- Copyright: The llama.cpp contributors
- Lizenz: MIT
- Verwendung: Lokale GGUF-Inferenz für die optionale KI-Nachbearbeitung

Der vollständige Lizenztext ist in
[`licenses/llama.cpp-MIT.txt`](licenses/llama.cpp-MIT.txt) enthalten.

## Qwen3.5

- Projekt: <https://huggingface.co/Qwen>
- Copyright: Alibaba Cloud
- Lizenz: Apache License 2.0
- Verwendung: Optional herunterladbare lokale Textmodelle (0,8B, 2B und 4B)
- GGUF-Quantisierungen: Q4_0, Q4_K_M und Q8_0 je nach Modellvariante
- GGUF-Quellen: `ggml-org/Qwen3.5-0.8B-GGUF` sowie `unsloth/Qwen3.5-2B-GGUF` und `unsloth/Qwen3.5-4B-GGUF`, jeweils über im Modellkatalog gepinnte Revisionen und SHA-256-Prüfsummen

Die Modelle werden nicht mit der APK ausgeliefert. Jede auswählbare GGUF-Datei
wird erst auf Wunsch des Nutzers geladen und nach dem Download gegen die im
Modellkatalog hinterlegte SHA-256-Prüfsumme geprüft. Der vollständige Lizenztext
ist in [`licenses/Qwen3.5-Apache-2.0.txt`](licenses/Qwen3.5-Apache-2.0.txt)
enthalten.

## CrispASR

- Projekt: <https://github.com/CrispStrobe/CrispASR>
- Version: v0.8.31
- Lizenz: MIT
- Verwendung: Native Android-ARM64-Inferenz für die optionale Stimmisolierungsvariante `Kim Vocal 2 · Native/GGUF`
- CI-Runtime-Archiv: `crispasr-android-arm64-v8a.tar.gz`
- Archiv-SHA-256: `78b03fe6ea86b62b7f16e168b0d932d1ced2d705b1e9999de73c4510fdc5b6c4`

Der vollständige Lizenztext ist in
[`licenses/CrispASR-MIT.txt`](licenses/CrispASR-MIT.txt) enthalten. Die native
Runtime wird beim reproduzierbaren CI-Build aus dem gepinnten v0.8.31-Release
bezogen und nicht als Binärdatei im Repository gespeichert.

## Kim Vocal 2 / Mel-Band RoFormer GGUF

- Gewichte: `KimberleyJSN/melbandroformer`
- GGUF-Konvertierung: `cstr/mel-band-roformer-vocals-GGUF`
- Datei: `mel-band-roformer-vocals-f16.gguf`
- Lizenz der Kim-Vocal-Gewichte: MIT
- Architektur-Referenz: `lucidrains/BS-RoFormer`, MIT
- Verwendung: Optional herunterladbares F16-GGUF für die native Stimmisolierung

Das GGUF wird nicht mit der APK ausgeliefert. Es wird erst nach Auswahl des
Nutzers geladen. Für den Android-Integrationsstand wird die Datei auf plausible
Größe und GGUF-Magic geprüft; die endgültige Release-Pinnung der konkreten
Modelldatei inklusive SHA-256 bleibt vor Veröffentlichung als eigener
Nachweis festzuhalten.
