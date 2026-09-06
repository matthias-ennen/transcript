# Architektur von Transcript

Stand: **06.09.2026**

## Zielbild

Transcript ist eine lokale Android-App. Audio- und Videodateien werden auf dem
Gerät dekodiert, optional vorverarbeitet und mit `whisper.cpp` transkribiert. Die
akzeptierte Transkriptfassung kann anschließend auf ausdrücklichen Benutzerwunsch
lokal mit Qwen3.5 über `llama.cpp` ausgewertet werden.

Die Architektur trennt bewusst fünf Ebenen:

1. Medienimport, Aufnahme und Wiedergabe,
2. optionale Audio-Vorverarbeitung mit Stimmisolierung und VAD,
3. Whisper-Transkription und Chunk-Stitching,
4. sichtbare/editierbare Transcript-Timeline,
5. optionale lokale KI-Auswertung des fertigen Transkripts.

Die frühere produktive KI-Korrektur des Whisper-Transkripts ist entfernt. Intern
vorhandene historische Postprocessing-Bausteine sind kein Bestandteil des heutigen
Produktworkflows.

## Module

- `app`: Oberfläche, Medienauswahl, Aufnahme, Wiedergabe, Downloads,
  Transkriptionssteuerung, Stimmisolierung, VAD, Timeline, Export und Android-Services
- `lib`: Kotlin-/JNI-Brücke zu `whisper.cpp` und nativer CMake-Build
- `llm`: Kotlin-/JNI-Brücke zu `llama.cpp` für lokale Qwen-GGUF-Inferenz
- `third_party/whisper.cpp`: gepinnte Whisper-Inferenzbibliothek
- `third_party/llama.cpp`: gepinnte lokale LLM-Inferenzbibliothek

Wichtige App-Bereiche liegen unter `app/src/main/java/com/whispercppdemo/`:

- `media/`: Dekodierung, Aufnahme, Player und Wellenform
- `song/`: Stimmisolierung und Separator-Runtimes
- `transcription/`: Transkriptionsauftrag, Checkpoints, VAD, Worker und Ergebniszustand
- `ai/`: lokale KI-Modelle, Runtime, Diagnose und Transkript-Auswertung
- `ui/main/`: Compose-Oberfläche und zentraler UI-Zustand

## Gesamtpipeline

Der produktive Datenfluss lautet:

```text
Originalaudio
→ optional Stimmisolierung
→ optional Silero VAD
→ Whisper
→ Whisper-Original
→ Timeline / manuelle Korrektur
→ akzeptierte Transkriptfassung
→ optional lokale KI-Auswertung
```

Stimmisolierung und VAD sind unabhängig voneinander aktivierbar. Das
Originalaudio wird nicht überschrieben.

## Medien, Aufnahme und Player

`RecordingService` besitzt eine laufende Mikrofonaufnahme unabhängig von der
Activity. `AndroidAudioDecoder` bereitet importierte Audio-/Videodateien für die
weitere Verarbeitung vor. `AudioPlayerController`, `WaveformGenerator` und
`WaveformCache` bilden die Wiedergabe- und Wellenformstrecke.

Liegt eine vorbereitete Stimmisolierung vor, kann die Oberfläche zwischen
**Original** und **Stimmisolierung** umschalten. Position und Zeitbasis bleiben
identisch; die Wellenform wechselt passend zur aktiven Quelle.

## Stimmisolierung

Die Stimmisolierung liegt technisch im Paket `song/`. Zentrale Bausteine sind
unter anderem:

- `SongSeparationModel`
- `SongSeparationPreferences`
- `SongModelDownloadService`
- `SongSeparatorEngine`
- `OnnxSongSeparatorRuntime`
- `CrispSongSeparatorRuntime`
- `SongPreparedTrack`
- `KimMemoryDiagnosticsExport`

Die sichtbare Produktfunktion heißt **Stimmisolierung**; historische interne
Klassennamen mit `Song` bleiben aus technischen Gründen bestehen.

### Modellkatalog

Die vier Varianten sind:

1. Schnell – Open-Unmix UMXHQ
2. Ausgewogen – Deezer Spleeter 2-stem FP16
3. Kim Vocal 2 – Native/GGUF
4. Hohe Qualität – Kim Vocal 2 / Mel-Band RoFormer (ONNX)

### Native/GGUF

`CrispSongSeparatorRuntime` bindet die native CrispASR-/Mel-Band-RoFormer-Runtime
an. Der Android-Build erzeugt die gepinnte Runtime reproduzierbar mit OpenBLAS und
Vulkan-Payload. Die JNI-Brücke stellt Laden, Separation und Freigabe bereit.

Für Native/GGUF kann ein automatischer Pfad mit Vulkan, wenn verfügbar, oder ein
reproduzierbarer CPU-/OpenBLAS-Pfad gewählt werden. CPU-Threads und Backendprofil
werden modellbezogen gespeichert und beim Start eines Transkriptionsauftrags
fest in dessen Konfiguration übernommen.

Ändert der Benutzer ein Leistungsprofil, wird eine dazu nicht mehr passende
vorbereitete Stimmspur invalidiert, damit ein erneuter Lauf tatsächlich mit der
neuen Konfiguration rechnet.

### Kontinuierliche Stimmspur

Die Stimmisolierung erzeugt eine zusammenhängende interne Spur auf derselben
Zeitachse wie das Original. Whisper liest seine späteren Abschnitte aus dieser
vorbereiteten Spur. Separator-Ressourcen werden vollständig freigegeben, bevor
Whisper geladen beziehungsweise gestartet wird.

### Großer ONNX-Kim

`OnnxSongSeparatorRuntime` hält den bestehenden großen FP16-ONNX-Pfad bewusst
weiter verfügbar. Auf den beiden vorhandenen Android-Testgeräten steigt sein
Speicherbedarf innerhalb der ersten Inferenz jedoch so stark an, dass Android den
Transkriptionsprozess beendet. Beim Xiaomi Pad 8 Pro wurde im Bugreport ein RSS-
Peak bis ungefähr 5,9 GB beobachtet.

Dieser Befund ist eine bekannte Plattform-/Modellgrenze, kein offener Android-1.0-
Blocker. Native/GGUF ist der primäre mobile Kim-Pfad. Ein späterer Desktop-Test des
großen ONNX-Modells ist #40 zugeordnet.

## Whisper-Datenfluss

1. `MainScreenViewModel` hält den zentralen UI-Zustand und erzeugt einen
   unveränderlichen `TranscriptionJobConfiguration` für den Start.
2. `TranscriptionService` läuft in einem privaten Android-Prozess
   `:transcription`.
3. Vor Whisper werden je nach Auftrag Stimmisolierung und/oder VAD verarbeitet.
4. Audio wird abschnittsweise vorbereitet; große PCM-Payloads werden nicht über
   Binder/Intent transportiert.
5. `PreparedAudioStore` hält vorbereitete Abschnitte im privaten App-Speicher.
6. `SequentialTranscriptionResourceGuard` unterstützt die nacheinander ausgeführte
   Ressourcennutzung großer Verarbeitungsschritte.
7. Whisper verwendet Hauptabschnitte von einer bis fünf Minuten mit zwei Sekunden
   Kontextüberlappung.
8. Nach jedem Abschnitt werden Ergebnis, erkannte Sprache und nächste Position als
   Wiederaufnahmepunkt gesichert.

`TranscriptionCheckpointStore`, `TranscriptionStateStore` und
`WorkerHeartbeatStore` bilden die persistente Zustands-/Watchdog-Basis.
`TranscriptionControlReceiver` verarbeitet Steuerbefehle für laufende Aufträge.

## VAD

Silero VAD ist optional. `VadAutomaticAnalyzer` unterstützt den Automatikmodus;
`VadProcessingSummary` hält die nachvollziehbare Entscheidung beziehungsweise
Messbasis.

VAD arbeitet auf dem für den Auftrag gültigen Audiosignal. Bei aktiver
Stimmisolierung liegt dieses nach der Stimmtrennung, bei deaktivierter
Stimmisolierung auf dem Originalpfad.

## Chunk-Grenzen und Stitching

`TranscriptionChunking` verschiebt lokale Whisper-Zeitstempel auf die absolute
Position in der vollständigen Aufnahme. Zwei Sekunden Kontextüberlappung bleiben
Teil der Architektur, damit Wörter und Sätze nicht an harten Chunk-Grenzen
abgeschnitten werden.

Beim Zusammenführen gilt:

- grenzüberschreitende Alternativen desselben Audiobereichs werden bereinigt,
- echte zeitlich getrennte Wiederholungen bleiben erhalten,
- absolute Zeitstempel bleiben auf die Originaldatei bezogen.

Whisper-Wiederholungsschleifen beziehungsweise echte Halluzinationen werden
separat in #78 untersucht und nicht durch Text-KI verdeckt.

## Persistenz und Timeline

`TranscriptResultStore` hält nach Abschluss das Whisper-Original und den gültigen
Anzeige-/Exportstand. Schreibvorgänge erfolgen atomar.

`TranscriptTimeline` ergänzt das Whisper-Ergebnis zu einer sichtbaren Zeitleiste
vom Dateianfang bis Dateiende. Größere Lücken werden als leere, abspielbare und
editierbare Bereiche ergänzt. Jede sichtbare Karte erhält eine fortlaufende
Fragmentnummer; die technische Herkunft bleibt davon getrennt.

Manuelle Änderungen werden erst nach bewusster Übernahme zum gültigen
Anzeige-/Exportstand. Zeitstempel bleiben schreibgeschützt.

Die fachlich relevanten neuen Herkunftszustände sind Whisper-Original und manuelle
Bearbeitung. Die lokale KI-Auswertung erzeugt keinen neuen Transkript-
Herkunftsstatus.

## Lokale KI-Auswertung

Die produktive KI-Strecke verwendet unter anderem:

- `AiTranscriptAnalysis`
- `AiTranscriptAnalysisRequestStore`
- `AiTranscriptAnalysisService`
- `AiTranscriptAnalysisState`
- `AiTranscriptAnalysisPerformance`
- `AiTranscriptAnalysisPerformanceStore`

Die Oberfläche liegt in
`AiTranscriptAnalysisComponents` und zugehörigen UI-State-/Performance-Komponenten.

Der Benutzer startet eine von vier festen Aufgaben auf der aktuell akzeptierten
Transkriptfassung:

1. Zusammenfassen
2. Kernaussagen / Stichpunkte
3. Aufgaben & To-dos
4. Entscheidungen / Besprechungsprotokoll

Die KI-Ausgabe wird separat gespeichert/dargestellt und verändert das Transkript
nicht. Lange Transkripte werden mehrstufig verarbeitet; Teil- und Merge-Phasen
werden getrennt messbar gehalten.

`AiEngineSessionManager`, `AiPerformancePreferences`, `AiRuntimeSafety` und
`AiHardwareProbe` bilden Runtime-, Profil-, Speicher- und Thermalgrundlagen.
`AiDiagnosticsScreen` und `AiPerformanceScreen` bleiben als technische Diagnose-
und Optimierungsoberflächen erhalten.

Historische Klassen wie `AiPostProcessingService` existieren noch als interner
Entwicklungsbestand, sind aber nicht mehr die fachliche Produktstrecke für die
Transkriptkorrektur.

## Pipeline-Diagnose und Leistungsmessung

Für Transkriptionsläufe erfassen `TranscriptionPipelineTiming`,
`TranscriptionPipelineProgressPresentation` und
`TranscriptionDiagnosticsReport` die reale Pipeline.

Die Ergebnisdarstellung kann ausweisen:

- Stimmisolierung und Modell
- Audioaufbereitung
- VAD / Segmentierung
- Whisper und Modell
- Gesamtzeit
- Audio-/Verarbeitungsdauer
- Echtzeitfaktor
- Engpass

Die Seite `SongIsolationPerformanceScreen` stellt modellbezogene
Stimmisolierungs-Leistungsprofile bereit.

Ziel dieser Diagnose ist die Trennung realer Zeitanteile: langsame
Stimmisolierung, Whisper und übrige Pipeline-Schritte sollen nicht vermischt
bewertet werden.

## Hintergrundbetrieb und Fehlerisolation

Transkription läuft in einem privaten Nebenprozess, damit native Workerfehler die
sichtbare Activity nicht zwangsläufig beenden. Foreground-Service, Wake-Lock,
Heartbeat, Checkpoints und kontrollierte Abbruchpfade halten lange lokale Läufe
nachvollziehbar.

Ein riskanter GPU/Vulkan-Pfad darf den normalen AUTO-Pfad nicht unkontrolliert
instabil machen. Für Whisper nutzt AUTO deshalb den portablen CPU-Pfad; Vulkan ist
explizites Opt-in mit Prozess-/CPU-Recovery.

Große Audio- oder Modellobjekte werden nicht über Binder/Intent transportiert.

## Export

`TranscriptExport` verwendet den übernommenen Timeline-Zustand.

- JSON enthält die vollständige Timeline einschließlich Herkunft.
- leere virtuelle Pausen bleiben im JSON erhalten,
- TXT und SRT lassen leere Pausen weg,
- manuell befüllte Pausen werden als normaler Textinhalt exportiert.

`TranscriptShare` stellt Exportdateien über `FileProvider` mit zeitlich begrenztem
Leserecht für das Android-Teilen-Menü bereit.

## Modelle, Integrität und Datenschutz

Whisper-, VAD-, Stimmisolierungs- und Qwen-Modelle werden separat verwaltet.
Downloads werden vor Verwendung auf die erwartete Integrität geprüft. Große
Modelldateien gehören nicht in die APK.

Die Verarbeitung von Audio, Video, Transkript und KI-Auswertung bleibt lokal.
Netzwerkzugriff ist für Modelldownloads erforderlich, nicht für die eigentliche
Inhaltsverarbeitung.

Android-Cloud-Backup und Geräteübertragung sind für die App deaktiviert.

## Release-Architektur und nächste Schritte

Die aktuelle Android-Architektur bleibt bis Version 1.0 die Basis. #103 untersucht
als nächstes allgemeine Audio-Vorverarbeitung. #78 analysiert anschließend
Whisper-Halluzinationen/Wiederholungsschleifen; #113 übernimmt die zweite
KI-Performancephase.

#116 bleibt Spleeter-Lizenz-Release-Gate. #40 ist ausdrücklich der getrennte
Windows-/Microsoft-Desktop-Ausbau nach Android 1.0.
