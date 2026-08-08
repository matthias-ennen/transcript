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
2. `WaveformGenerator` dekodiert die Audiospur blockweise und verdichtet die
   gelesenen Puffer unmittelbar auf 180 Spitzenwerte. Die vollständigen PCM-
   Daten werden dabei nie im Speicher gehalten. Nach 60 Sekunden wird nur die
   optionale Wellenform abgebrochen; die Datei bleibt nutzbar.
3. `TranscriptionService` plant Fünf-Minuten-Hauptabschnitte mit je zwei
   Sekunden Kontextüberlappung und arbeitet unabhängig von der Activity.
4. `AndroidAudioDecoder` dekodiert ausschließlich den aktuellen Bereich und
   resampelt MediaCodec-Ausgabepuffer unmittelbar auf 16-kHz-Mono-PCM. PCM in
   der ursprünglichen Abtastrate wird nicht gesammelt. Ein fester
   Fünf-Sekunden-Sicherheitsspielraum fängt Codec-Vorlauf, Padding und
   Zeitstempelrundungen auf; vor Whisper wird der Abschnitt wieder exakt auf
   seine Sollgröße begrenzt.
5. `WhisperContext` verarbeitet den aktuellen Abschnitt. Bei automatischer
   Auswahl wird eine anhand eines brauchbaren Textabschnitts erkannte Sprache
   für die folgenden Abschnitte festgehalten.
6. `TranscriptionChunking` verschiebt lokale Segmentzeiten auf die absolute
   Audioposition und ordnet Überlappungssegmente über ihren Mittelpunkt genau
   einem Hauptbereich zu. Ein fehlgeschlagener Hauptabschnitt wird einmal in
   2,5-Minuten-Bereiche geteilt.
7. `TranscriptionCheckpointStore` schreibt Segmente, Sprache und nächste
   Position nach jedem fertigen Abschnitt atomar in den privaten App-Speicher.
8. `TranscriptionCoordinator` übergibt Fortschritt, Diagnose und Teilergebnisse
   an jedes aktive `MainScreenViewModel`.
9. Die Ergebnisansicht stellt jedes Segment mit Zeitstempel und GUI-Nummer dar.
   Eine 52 × 32 dp große, abgerundete Nummernkapsel hält auch drei- und
   vierstellige Nummern vollständig sichtbar.
10. Der Korrekturmodus hält Änderungen zunächst in `draftSegments`. Erst
   **Änderungen übernehmen** ersetzt die Ergebnis-Segmente; Zeitstempel und
   Reihenfolge bleiben erhalten.
11. `TranscriptExport` erzeugt TXT, SRT oder JSON aus dem übernommenen Stand.
12. `TranscriptShare` schreibt die ausgewählten Formate in einen privaten
    Cache-Unterordner. Ein nicht exportierter `FileProvider` gibt ausschließlich
    diese Dateien mit zeitlich begrenztem Leserecht an das Android-Teilen-Menü
    weiter. Ein Format verwendet `ACTION_SEND`, mehrere Formate verwenden
    `ACTION_SEND_MULTIPLE`.
13. Lange Transkripte blenden abhängig von Segmentanzahl und Scrollposition eine
    schwebende Navigationskapsel ein. Sie verwendet denselben Scrollzustand wie
    die gesamte Hauptansicht und führt deshalb bis an den Anfang der App zurück.

## GUI-Sprache

`AppLanguage` hält die von der Whisper-Transkriptionssprache unabhängige
GUI-Sprachauswahl. Der Umschalter in der Kopfleiste bietet Deutsch und Englisch
an; `AppLanguagePreference` speichert die Auswahl dauerhaft. Die vollständige
Umstellung aller sichtbaren Texte auf Android-Stringressourcen erfolgt in einem
separaten Lokalisierungsschritt.

Die von Whisper erkannte Transkriptionssprache bleibt davon unabhängig. Die
Ergebnisansicht löst sämtliche Whisper-Sprachcodes über
`WhisperLanguageNames` in vollständige deutsche Bezeichnungen auf. Unbekannte
Codes werden weiterhin sichtbar mit ihrem Code ausgegeben.

## Status- und Animationssteuerung

Die sichtbare Statuszeile verbindet Text und CannaBot. Dauerzustände sind
`IDLE`, `WAITING`, `REVIEW` und `RUNNING`. Kurze Ereignisse verwenden
`RUNNING_RIGHT`, `RUNNING_LEFT`, `JUMPING`, `WAVING` und `FAILED`. Eine
Erfolgssequenz spielt Springen und Winken nacheinander ab und kehrt anschließend
zum Grundzustand zurück. Fortschrittsereignisse werden nur an festgelegten
Meilensteinen ausgelöst.

Der Teilen-Dialog besitzt eine eigene, nur beim Öffnen gestartete Sequenz aus
Rechtslauf, Sprung und Winken. Kurze Idle-Pausen trennen die Gesten; anschließend
bleibt CannaBot ruhig im Idle-Zustand.

Im Zustand `REVIEW` ergänzt `TranscriptionTimeEstimate` die unveränderte
Bereitschaftsmeldung um eine kalibrierte Laufzeitschätzung. Die Statuszeile
wechselt am 20-Prozent-Punkt der Pulsation zwischen beiden Texten. Pro
Whisper-Modell liegt ein zentraler, anhand der Messreihen auf dem Zielgerät
festgelegter Echtzeitfaktor vor.

Während einer Transkription liefert `TranscriptionService` die verbindliche
Startzeit. `MainScreenViewModel` berechnet daraus in einem eigenen Sekundentakt
die sichtbare Laufzeit. Dieser Takt ist unabhängig von Decoder- und
Whisper-Fortschrittsmeldungen und wird nach einem erneuten Öffnen anhand der
Startzeit wieder aufgenommen. Neben der echten Laufzeit bleibt die vor dem Lauf
berechnete Gesamtschätzung sichtbar.

## Modelle und Speicherung

Der zentrale `WhisperModel`-Katalog enthält fünf mehrsprachige Qualitätsstufen
von **Sehr schnell** (`ggml-tiny.bin`) bis **Maximale Qualität**. Sämtliche
Modelle durchlaufen denselben Auswahl-, Download-, Prüfsummen-, Speicher-,
Lösch- und Transkriptionspfad; Tiny benötigt keine Sonderbehandlung.

Modelle, Aufnahmen und Transkriptionszwischenstände liegen im privaten
App-Speicher. Modelldownload und Transkription besitzen getrennte
Foreground-Services, getrennte Zustandskoordinatoren und getrennte
Fehlerbehandlung. Downloads können über `.part`-Dateien fortgesetzt werden und
werden vor der Aktivierung per SHA-256 geprüft. Modelle werden nicht in die APK
aufgenommen. Ein bewusster Transkriptionsabbruch entfernt den Zwischenstand;
eine Prozessunterbrechung lässt ihn für die Wiederaufnahme bestehen.

## Build und Veröffentlichung

`.github/workflows/build-apk.yml` führt die JVM-Unit-Tests aus, baut eine
dauerhaft signierte Debug-APK, prüft deren Signatur und lädt sie als GitHub-
Actions-Artefakt hoch. Die Signierdaten werden ausschließlich aus geschützten
Repository-Secrets gelesen.
