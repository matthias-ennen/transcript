# Transcript für Android

Die kompakte CannaBot-Animation in der Titelzeile spiegelt den aktuellen App-Zustand wider, ohne die Höhe der App-Leiste zu verändern.

Eine lokale Android-App, die MP3- und andere Audiodateien mit
[`whisper.cpp`](https://github.com/ggml-org/whisper.cpp) transkribiert.
Die Audiodatei bleibt auf dem Gerät; es wird kein kostenpflichtiger
Transkriptionsdienst benötigt.

## Stand der ersten Ausbaustufe

- Android-Dateiauswahl für MP3 und weitere unterstützte Audioformate
- Offline-Dekodierung zu 16 kHz Mono-PCM
- lokale Modellverwaltung mit vier Qualitätsstufen
- Download, Auswahl, Prüfsummenprüfung und Löschen jedes Modells
- automatische, deutsche oder englische Spracherkennung
- Ausgabe mit Segmentzeitstempeln
- Export als TXT, SRT und JSON
- Whisper-Modell, erkannte Sprache, Transkriptionsdauer und Erstellungszeitpunkt
  als Metadaten in TXT- und JSON-Exporten
- automatischer Debug-APK-Build mit GitHub Actions
- dauerhafte APK-Signierung für installierbare Updates
- automatisch steigende Versionsnummer bei jedem GitHub-Build
- sichtbare Diagnosekette für Decoder, Modellladung und native Whisper-Engine
- laufende Zeit- und Whisper-Fortschrittsanzeige mit Stillstandshinweis
- laufende Transkription direkt über die Hauptschaltfläche abbrechen
- direkte Mikrofonaufnahme mit automatischer Speicherung im App-Bereich
- Play/Pause für ausgewählte Dateien und eigene Aufnahmen
- Wellenform mit mitlaufender und per Finger verschiebbarer Positionsmarke
- Anzeige der aktuellen Wiedergabezeit und Gesamtdauer
- Anzeige des verwendeten Modells, der erkannten Sprache und der Transkriptionszeit

## Aufnahme und Vorhören

Unter der Dateiauswahl kann eine Aufnahme direkt über die Mikrofontaste
gestartet werden. Während der Aufnahme wird dieselbe Taste zum Stopp-Symbol.
Die fertige AAC-/M4A-Datei wird unter `files/recordings` im privaten
App-Speicher abgelegt und sofort als aktuelle Audiodatei ausgewählt. Ihr Name
enthält Datum und Uhrzeit.

Ausgewählte oder aufgenommene Audiodateien lassen sich vor der Transkription
abspielen und pausieren. Eine verdichtete Wellenform zeigt die Wiedergabeposition;
durch Tippen oder Ziehen kann zu einer anderen Stelle gesprungen werden. Eine
Live-Anzeige visualisiert während der Aufnahme den Mikrofonpegel.

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

## APK-Updates und Signierung

GitHub Actions signiert jede APK mit demselben privaten Schlüssel. Die dazu
gehörenden Signierdaten liegen ausschließlich in geschützten Repository-Secrets
und werden nicht eingecheckt. Der Workflow vergibt außerdem bei jedem Lauf eine
höhere `versionCode`-Nummer. Dadurch kann Android eine neuere APK über eine
ältere, dauerhaft signierte APK installieren, ohne App-Daten oder das bereits
geladene Whisper-Modell zu löschen.

Eine APK, die noch mit einem früheren Debug-Schlüssel signiert wurde, muss vor
der ersten dauerhaft signierten Version einmalig deinstalliert werden. Danach
lassen sich weitere GitHub-APKs direkt als Update darüberinstallieren.

Benötigte GitHub-Actions-Secrets:

- `ANDROID_SIGNING_KEYSTORE_BASE64`
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

Build-Test der dauerhaft signierten APK: 6. August 2026.

## Modelle und Speicherbedarf

Die App bietet vier mehrsprachige Modelle für automatische, deutsche und
englische Transkriptionen:

- **Schnell:** `ggml-base.bin` (ca. 148 MB)
- **Ausgewogen:** `ggml-small-q5_1.bin` (ca. 190 MB)
- **Sehr genau:** `ggml-large-v3-turbo-q5_0.bin` (ca. 574 MB)
- **Maximale Qualität:** `ggml-large-v3-q5_0.bin` (ca. 1,08 GB)

Jedes Modell kann einzeln heruntergeladen, ausgewählt und gelöscht werden. Die
App merkt sich die zuletzt verwendete Qualitätsstufe. Nach dem Download wird
die SHA-256-Prüfsumme kontrolliert, damit unvollständige oder beschädigte
Modelldateien nicht verwendet werden. Die Modelle liegen im privaten
App-Speicher und werden nicht in das Git-Repository oder die APK eingecheckt.

## Datenschutz

Die Transkription läuft vollständig auf dem Android-Gerät. Nur der einmalige
Modelldownload benötigt Internetzugriff.

## Drittanbieter

`whisper.cpp` steht unter der MIT-Lizenz. Der vollständige Lizenztext befindet
sich unter [`licenses/whisper.cpp-MIT.txt`](licenses/whisper.cpp-MIT.txt).

Build-Auslöser: 3
