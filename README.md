# Transcript für Android

Eine lokale Android-App, die MP3- und andere Audiodateien mit
[`whisper.cpp`](https://github.com/ggml-org/whisper.cpp) transkribiert.
Die Audiodatei bleibt auf dem Gerät; es wird kein kostenpflichtiger
Transkriptionsdienst benötigt.

## Stand der ersten Ausbaustufe

- Android-Dateiauswahl für MP3 und weitere unterstützte Audioformate
- Offline-Dekodierung zu 16 kHz Mono-PCM
- lokaler Download des Whisper-Base-Modells
- automatische, deutsche oder englische Spracherkennung
- Ausgabe mit Segmentzeitstempeln
- Export als TXT, SRT und JSON
- automatischer Debug-APK-Build mit GitHub Actions
- sichtbare Diagnosekette für Decoder, Modellladung und native Whisper-Engine
- laufende Zeit- und Whisper-Fortschrittsanzeige mit Stillstandshinweis

## Diagnose während der Transkription

Version `0.2.0-diagnostic` zeigt, welchen Verarbeitungsschritt die App gerade
ausführt. Die native Whisper-Engine meldet ihren tatsächlichen Fortschritt in
Prozent an die Oberfläche zurück. Bleibt eine Rückmeldung länger aus, zeigt die
App außerdem an, seit wann kein neuer Fortschrittswert empfangen wurde.

Die Debug-APK baut den rechenintensiven nativen Whisper-Code mit
Release-Optimierungen. Die Optimierung muss im `lib`-Modul gesetzt sein, weil
dort der CMake-/NDK-Build stattfindet.

Die erste Version dient als technisches Fundament. Gesangstrennung,
Wortzeitstempel und die Synchronisierung eines bereits bekannten Songtexts
sind für spätere Ausbaustufen vorgesehen.

## APK bauen

Das Repository bindet `whisper.cpp` als Git-Submodul ein. Beim lokalen Klonen
daher die Submodule mit abrufen:

```bash
git clone --recurse-submodules https://github.com/matthias-ennen/transcript.git
cd transcript
./gradlew assembleDebug
```

Die APK liegt anschließend unter
`app/build/outputs/apk/debug/app-debug.apk`.

Alternativ kann der Workflow **Build Android APK** in GitHub Actions gestartet
und die APK als Build-Artefakt heruntergeladen werden.

## Modell und Speicherbedarf

Beim ersten Start lädt die App `ggml-base.bin` mit ungefähr 142 MB. Das Modell
wird im privaten App-Speicher abgelegt und nicht in das Git-Repository
eingecheckt.

## Datenschutz

Die Transkription läuft vollständig auf dem Android-Gerät. Nur der einmalige
Modelldownload benötigt Internetzugriff.

## Drittanbieter

`whisper.cpp` steht unter der MIT-Lizenz. Der vollständige Lizenztext befindet
sich unter [`licenses/whisper.cpp-MIT.txt`](licenses/whisper.cpp-MIT.txt).
