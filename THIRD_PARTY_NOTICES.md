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
- Gepinnter Commit: `a8c0327e2cba08eebd7199e69092dad3dec604a0` (03.09.2026)
- Lizenz: MIT
- Verwendung: Native Android-ARM64-Inferenz für die optionale Stimmisolierungsvariante `Kim Vocal 2 · Native/GGUF`
- Beschleunigung: Vulkan/GGML wird bevorzugt, wenn das Android-Gerät einen nutzbaren Vulkan-Backendpfad bereitstellt; OpenBLAS bleibt CPU-Fallback.

Der vollständige Lizenztext ist in
[`licenses/CrispASR-MIT.txt`](licenses/CrispASR-MIT.txt) enthalten. Die native
Runtime wird beim reproduzierbaren CI-Build aus dem exakt gepinnten
CrispASR-Commit für Android ARM64 gebaut und nicht als Binärdatei im Repository
gespeichert.

## OpenBLAS

- Projekt: <https://github.com/OpenMathLib/OpenBLAS>
- Version: v0.3.34
- Quellarchiv-SHA-256: `cd7e129868320cc2d033afa920e31202dfe0b8066a5b66661900ccc0f197dfed`
- Lizenz: BSD-3-Clause
- Verwendung: Statisch eingebundene CBLAS/SGEMM-Beschleunigung für den CPU-Fallback von Kim Vocal 2 · Native/GGUF auf Android ARM64

OpenBLAS wird ausschließlich im CI für Android ARM64 kompiliert. Der Build ist
auf CBLAS ohne eigene Worker-Threads begrenzt; CrispASR parallelisiert die
Zeit-/Frequenzblöcke selbst. Der vollständige Lizenztext ist in
[`licenses/OpenBLAS-BSD-3-Clause.txt`](licenses/OpenBLAS-BSD-3-Clause.txt)
enthalten.

## Kim Vocal 2 / Mel-Band RoFormer GGUF

- Gewichte: `KimberleyJSN/melbandroformer`
- GGUF-Konvertierung: `cstr/mel-band-roformer-vocals-GGUF`
- Datei: `mel-band-roformer-vocals-f16.gguf`
- Lizenz der Kim-Vocal-Gewichte: MIT
- Architektur-Referenz: `lucidrains/BS-RoFormer`, MIT
- Verwendung: Optional herunterladbares F16-GGUF für die native Stimmisolierung
- Inferenzfenster in Transcript: trainierte 352.800 Samples bei 44,1 kHz = 8 s; 25 % Überlappung, Schrittweite 6 s.

Das GGUF wird nicht mit der APK ausgeliefert. Es wird erst nach Auswahl des
Nutzers geladen. Für den Android-Integrationsstand wird die Datei auf plausible
Größe und GGUF-Magic geprüft; die endgültige Release-Pinnung der konkreten
Modelldatei inklusive SHA-256 bleibt vor Veröffentlichung als eigener
Nachweis festzuhalten. Eine quantisierte Variante ersetzt das F16-GGUF nicht
stillschweigend; sie erfordert eine separat geprüfte, identische Kim-Vocal-2-
Gewichtsquelle und einen Qualitätsvergleich.
