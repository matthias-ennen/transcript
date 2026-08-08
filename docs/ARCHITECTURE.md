# Architektur von Simple Transcript

## Zielbild

Simple Transcript ist eine lokale Android-App. Audio- und Videodateien werden
auf dem Gerät dekodiert und mit `whisper.cpp` transkribiert. Nur der optionale
Download eines Whisper-Modells benötigt eine Internetverbindung.

## Module

- `app`: Oberfläche, Medienauswahl, Aufnahme, Wiedergabe, Modelldownload,
  Statussteuerung und Export
- `lib`: Kotlin-/JNI-Brücke zu `whisper.cpp` und nativer CMake-Build
- `third_party/whisper.cpp`: als Git-Submodul eingebundene Inferenzbibliothek

## Datenfluss

1. `MainScreenViewModel` hält den zentralen `TranscriptUiState`.
2. `AndroidAudioDecoder` wandelt die gewählte Mediendatei in 16-kHz-Mono-PCM um.
3. `WhisperContext` übergibt die Samples an die native Whisper-Engine.
4. Fortschritt, Diagnosemeldungen und erkannte `WhisperSegment`-Einträge werden
   in den UI-Zustand übernommen.
5. Die Ergebnisansicht stellt jedes Segment mit Zeitstempel und GUI-Nummer dar.
6. Der Korrekturmodus hält Änderungen zunächst in `draftSegments`. Erst
   **Änderungen übernehmen** ersetzt die Ergebnis-Segmente; Zeitstempel und
   Reihenfolge bleiben erhalten.
7. `TranscriptExport` erzeugt TXT, SRT oder JSON aus dem übernommenen Stand.

## Status- und Animationssteuerung

Die sichtbare Statuszeile verbindet Text und CannaBot. Dauerzustände sind
`IDLE`, `WAITING`, `REVIEW` und `RUNNING`. Kurze Ereignisse verwenden
`RUNNING_RIGHT`, `RUNNING_LEFT`, `JUMPING`, `WAVING` und `FAILED`. Eine
Erfolgssequenz spielt Springen und Winken nacheinander ab und kehrt anschließend
zum Grundzustand zurück. Fortschrittsereignisse werden nur an festgelegten
Meilensteinen ausgelöst.

## Modelle und Speicherung

Modelle und Aufnahmen liegen im privaten App-Speicher. Downloads werden über
einen Foreground-Service ausgeführt, können über `.part`-Dateien fortgesetzt
werden und werden vor der Aktivierung per SHA-256 geprüft. Modelle werden nicht
in die APK aufgenommen.

## Build und Veröffentlichung

`.github/workflows/build-apk.yml` führt die JVM-Unit-Tests aus, baut eine
dauerhaft signierte Debug-APK, prüft deren Signatur und lädt sie als GitHub-
Actions-Artefakt hoch. Die Signierdaten werden ausschließlich aus geschützten
Repository-Secrets gelesen.
