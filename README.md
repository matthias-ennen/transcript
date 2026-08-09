# Transcript für Android

Eine lokale Android-App, die Audio- und Videodateien über ihre Audiospur mit
[`whisper.cpp`](https://github.com/ggml-org/whisper.cpp) transkribiert.
Die Mediendatei bleibt auf dem Gerät; es wird kein kostenpflichtiger
Transkriptionsdienst benötigt. Optional kann ein ebenfalls lokales Qwen3.5-Modell
die erkannten Texte mit `llama.cpp` nachbearbeiten.

Innerhalb der App und unter dem App-Symbol auf dem Android-Startbildschirm lautet
der Produktname **Transcript**.

## Aktueller Entwicklungsstand

Die App benötigt mindestens **Android 8.0 (API 26)**. Sie transkribiert lokal
auf dem Android-Gerät und bietet inzwischen fünf Qualitätsstufen von
**Whisper Tiny („Sehr schnell“)** bis **Whisper Large V3 („Maximale Qualität“)**.
Die Modelle werden bei Bedarf einzeln heruntergeladen und sind nicht Bestandteil
der APK.

Der Schalter mit deutscher und britischer Flagge ist bereits in der Kopfzeile
vorbereitet und speichert die Auswahl dauerhaft. Die vollständige Übersetzung
der Bedienoberfläche, Statusmeldungen, Dialoge und Systembenachrichtigungen ist
noch nicht umgesetzt; die sichtbare GUI bleibt derzeit deutsch.

## Funktionsumfang

- Android-Dateiauswahl für unterstützte Audio- und Videoformate
- Video-Bildspur wird ignoriert; verarbeitet wird ausschließlich die Audiospur
- Offline-Dekodierung zu 16 kHz Mono-PCM
- abschnittsweise Transkription langer Aufnahmen mit konstant begrenztem Speicherbedarf
- Fünf-Minuten-Abschnitte mit zwei Sekunden Kontextüberlappung und absoluten Zeitstempeln
- automatische Wiederholung problematischer Abschnitte mit 2,5 Minuten
- Hintergrundtranskription mit Systemmeldung, Abbruch und gesichertem Wiederaufnahmepunkt
- lokale Modellverwaltung mit fünf Qualitätsstufen
- optionale lokale KI-Nachbearbeitung mit drei auswählbaren Qwen3.5-Größen
- automatische KI-Korrektur nach dem Entladen von Whisper
- gruppenweise KI-Korrektur als kontrollierbarer Entwurf vor der Übernahme
- freier KI-Testbereich für eigene Fragen und den direkten Modellvergleich
- robuste Modellauswahl, die nach einer fertigen Transkription wieder direkt bedienbar ist
- eigene Einstellungsseite mit Speicherübersicht sowie einzelnem und gemeinsamem Löschen der Modelle
- stabiler Hintergrunddownload mit Fortschrittsmeldung, Fortsetzung und Prüfsummenprüfung
- Transkriptionssprache wahlweise automatisch erkannt, Deutsch oder Englisch
- Ausgabe mit Segmentzeitstempeln
- fortlaufend nummerierte Textabschnitte in Anzeige- und Bearbeitungsmodus;
  die abgerundete Nummernkapsel ist auch für drei- und vierstellige Nummern ausgelegt
- gemeinsamer Korrekturmodus für alle Textsegmente bei schreibgeschützten Zeitstempeln
- Export als TXT, SRT und JSON
- Teilen von TXT, SRT und JSON einzeln oder gemeinsam über das Android-Teilen-Menü
- ruhige CannaBot-Hinweissequenz im Teilen-Dialog
- halbtransparente Pfeilkapsel zum schnellen Sprung an den App-Anfang bei langen Transkripten
- Whisper-Modell, erkannte Sprache, Transkriptionsdauer und Erstellungszeitpunkt
  als Metadaten in TXT- und JSON-Exporten
- automatischer Debug-APK-Build mit GitHub Actions
- dauerhafte APK-Signierung für installierbare Updates
- automatisch steigende Versionsnummer bei jedem GitHub-Build
- sichtbare Diagnosekette für Decoder, Modellladung und native Whisper-Engine
- laufende Zeit- und Whisper-Fortschrittsanzeige mit Stillstandshinweis
- nur bei aktiven Vorgängen pulsierende Statusanzeige mit CannaBot
- vollständig umbrechende Statusmeldungen neben CannaBot ohne abgeschnittenen Text
- gezielte Dauer- und Einmalanimationen über alle neun Sprite-Sheet-Zustände
- laufende Transkription direkt über die Hauptschaltfläche abbrechen
- direkte Mikrofonaufnahme mit automatischer Speicherung im App-Bereich
- Play/Pause für ausgewählte Dateien und eigene Aufnahmen
- speicherschonend gestreamte Wellenform mit mitlaufender und per Finger
  verschiebbarer Positionsmarke
- Anzeige der aktuellen Wiedergabezeit und Gesamtdauer
- Anzeige des verwendeten Modells, der erkannten Sprache und der Transkriptionszeit
- vollständige deutsche Bezeichnung aller von Whisper erkannten Sprachen
- kalibrierte, modellabhängige Laufzeitschätzung für eine bereitstehende Datei
- kompakte Kopfzeile mit vorbereitetem Deutsch-/Englisch-Umschalter,
  Einstellungen und App-Informationen
- App-Informationen zu Entwickler, Kontakt, Datenschutz, Impressum und Open-Source-Lizenz
- sichtbarer, noch nicht aktiver Bereich „Entwickler unterstützen“ für einen späteren Store-Ausbau
- modernes adaptives Transcript-App-Symbol mit klassischen, runden und Android-13-Themenvarianten

## Aufnahme und Vorhören

Unter der Dateiauswahl kann eine Aufnahme direkt über die Mikrofontaste
gestartet werden. Während der Aufnahme wird dieselbe Taste zum Stopp-Symbol.
Die fertige AAC-/M4A-Datei wird unter `files/recordings` im privaten
App-Speicher abgelegt und sofort als aktuelle Audiodatei ausgewählt. Ihr Name
enthält Datum und Uhrzeit.

Ausgewählte oder aufgenommene Audiodateien lassen sich vor der Transkription
abspielen und pausieren. Eine verdichtete Wellenform zeigt die Wiedergabeposition;
durch Tippen oder Ziehen kann zu einer anderen Stelle gesprungen werden. Eine
Live-Anzeige visualisiert während der Aufnahme den Mikrofonpegel. Für die
Wellenform wird die Audiospur blockweise dekodiert und sofort auf 180 Werte
verdichtet; vollständige PCM-Daten werden dafür nicht im Arbeitsspeicher
gehalten. Nach spätestens 60 Sekunden wird nur diese optionale Vorschau
übersprungen. Wiedergabe und Transkription bleiben weiterhin verfügbar.

## Transkript korrigieren

Nach einer Transkription stehen unter jeder Fünf-Minuten-Gruppe links
**KI-Nachbearbeitung** und rechts **Bearbeiten**. **Bearbeiten** öffnet die Gruppe
direkt zur manuellen Korrektur. **KI-Nachbearbeitung** öffnet denselben Entwurf,
überarbeitet aber zunächst nur diese Gruppe mit dem in den Einstellungen gewählten
lokalen Modell. Danach lässt sich der Vorschlag weiter manuell ändern. Die
Zeitstempel bleiben sichtbar und unveränderbar. **Abbrechen** verwirft den Entwurf;
**Änderungen übernehmen**
aktualisiert das Ergebnis, das anschließend einheitlich für TXT, SRT und JSON
verwendet wird. Solange Änderungen noch nicht übernommen wurden, sind die
Exporte gesperrt. Vor einer neuen Datei, Aufnahme oder Transkription warnt die
App, wenn dadurch ein geänderter Entwurf verloren ginge.

Die vollständige Fünf-Minuten-Gruppe wird einmal als gemeinsamer, schreibgeschützter
Whisper-Rohkontext an das lokale Modell übergeben. Danach prüft die KI jedes
Zielsegment einzeln gegen denselben Ausgangskontext. Vorherige Antworten wirken
nicht auf das nächste Segment ein. `llama.cpp` begrenzt die Antwort technisch auf
ein einziges Ergebnisfeld. In der aktuellen Erprobungsstufe behält die App nur bei
einem leeren oder nicht lesbaren Ergebnis das Original; weitere inhaltliche
Plausibilitätsprüfungen werden erst nach den Praxistests ergänzt.

Die Kachel **KI-Testbereich** enthält statt einer fest eingebauten Testfrage ein
mehrzeiliges Eingabefeld. Eigene Fragen oder Aufgaben lassen sich unverändert an
das ausgewählte Qwen-Modell senden; die vollständige Antwort kann anschließend
ein- und ausgeblendet werden.

## Status und Diagnose während der Transkription

Die App zeigt, welchen Verarbeitungsschritt sie gerade ausführt. Die native
Whisper-Engine meldet ihren tatsächlichen Fortschritt in Prozent an die
Oberfläche zurück. Bleibt eine Rückmeldung länger aus, zeigt die App außerdem
an, seit wann kein neuer Fortschrittswert empfangen wurde.

CannaBot nutzt alle neun Zeilen des Sprite-Sheets. Dauerzustände laufen ruhig
weiter; kurze Ereignisse wie Springen, Winken, Richtungswechsel oder ein Fehler
werden einmal abgespielt und kehren anschließend automatisch zum passenden
Grundzustand zurück. Der Statustext pulsiert, solange die App arbeitet,
aufnimmt oder wiedergibt, und blendet dabei deutlich zwischen 20 und 100
Prozent Deckkraft. Ist eine Datei bereit, wechselt die pulsierende Statuszeile
am schwächsten Punkt zwischen der Bereitschaftsmeldung und einer
modellabhängigen Schätzung der Transkriptionsdauer. Die Schätzfaktoren wurden
anhand praktischer Laufzeitmessungen auf dem Zielgerät festgelegt.

Kurze Statusmeldungen bleiben einzeilig. Längere Status- und Fehlermeldungen
werden neben CannaBot vollständig auf mehrere Zeilen umgebrochen und nicht mit
Auslassungspunkten abgeschnitten. Bei der Wiedergabe verwendet die Oberfläche
bewusst den allgemeinen Text **Audio wird wiedergegeben …**.

## Lange Aufnahmen und Hintergrundbetrieb

Die Transkription lädt nicht mehr die vollständige Aufnahme als PCM in den
Arbeitsspeicher. `TranscriptionService` verarbeitet nacheinander
Fünf-Minuten-Hauptabschnitte. Jeder Abschnitt enthält an den Grenzen zwei
Sekunden zusätzlichen Audiokontext, damit Wörter und Sätze nicht abgeschnitten
werden. Die Überlappung wird anschließend anhand der zeitlichen Mitte jedes
Whisper-Segments genau einem Hauptabschnitt zugeordnet. Auf die lokalen
Whisper-Zeitstempel wird die absolute Startposition des Decoderabschnitts
addiert; dadurch bleiben auch Zeitstempel über einer Stunde korrekt.

Der Decoder toleriert einen kleinen, fest begrenzten Codec-Überhang und schneidet
das Ergebnis vor Whisper wieder auf die angeforderte Abschnittslänge zu.
Scheitert ein Fünf-Minuten-Abschnitt aus einem anderen Grund, wird dieser einmal
in zwei 2,5-Minuten-Sicherheitsabschnitte geteilt. Nach jedem fertigen Abschnitt
werden Textsegmente, erkannte Sprache und nächste Audioposition atomar im
privaten App-Speicher gesichert. Eine erneute Transkription derselben Datei mit
demselben Modell und derselben Sprache setzt dort fort. Ein bewusster Abbruch
entfernt den Zwischenstand.

Der Foreground-Service läuft auch bei ausgeschaltetem Bildschirm oder
geschlossener Oberfläche weiter. Seine Systemmeldung zeigt Gesamtfortschritt
und Abschnittsnummer und bietet einen Abbruchknopf. Bei automatischer
Spracherkennung wird die Sprache erst nach einem Abschnitt mit erkanntem Text
festgehalten und für alle folgenden Abschnitte wiederverwendet.

Die Debug-APK baut den rechenintensiven nativen Whisper-Code mit
Release-Optimierungen. Die Optimierung muss im `lib`-Modul gesetzt sein, weil
dort der CMake-/NDK-Build stattfindet.

Gesangstrennung, Wortzeitstempel und die Synchronisierung eines bereits
bekannten Songtexts gehören nicht zum aktuellen Funktionsumfang.

## APK bauen

Das Repository bindet `whisper.cpp` und `llama.cpp` als Git-Submodule ein. Beim lokalen Klonen
daher die Submodule mit abrufen:

```bash
git clone --recurse-submodules https://github.com/matthias-ennen/transcript.git
cd transcript
./gradlew assembleDebug
```

Die APK liegt anschließend unter
`app/build/outputs/apk/debug/app-debug.apk`.

Alternativ baut der Workflow **Build Android APK** bei jedem Push und Pull
Request eine APK. Zum Herunterladen auf GitHub:

1. Im Repository den Bereich **Actions** öffnen.
2. Den neuesten erfolgreichen Lauf **Build Android APK** auswählen.
3. Unten unter **Artifacts** die Datei `transcript-signed-apk-…` herunterladen.
4. Das ZIP entpacken und `app-debug.apk` auf dem Android-Gerät installieren.

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

Der Workflow führt vor dem APK-Build die JVM-Unit-Tests aus und prüft vor dem
Upload zusätzlich die APK-Signatur.

## Modelle und Speicherbedarf

Die App bietet fünf mehrsprachige Modelle für automatische, deutsche und
englische Transkriptionen:

| Qualitätsstufe | Whisper-Modell | Modelldatei | Downloadgröße |
| --- | --- | --- | ---: |
| Sehr schnell | Whisper Tiny | `ggml-tiny.bin` | ca. 77,7 MB |
| Schnell | Whisper Base | `ggml-base.bin` | ca. 148 MB |
| Ausgewogen | Whisper Small Q5_1 | `ggml-small-q5_1.bin` | ca. 190 MB |
| Sehr genau | Whisper Large V3 Turbo Q5_0 | `ggml-large-v3-turbo-q5_0.bin` | ca. 574 MB |
| Maximale Qualität | Whisper Large V3 Q5_0 | `ggml-large-v3-q5_0.bin` | ca. 1,08 GB |

Jedes Modell kann einzeln heruntergeladen, ausgewählt und gelöscht werden. Die
App merkt sich die zuletzt verwendete Qualitätsstufe. Nach dem Download wird
die SHA-256-Prüfsumme kontrolliert, damit unvollständige oder beschädigte
Modelldateien nicht verwendet werden. Die Modelle liegen im privaten
App-Speicher und werden nicht in das Git-Repository oder die APK eingecheckt.

Der Modelldownload läuft als Android-Foreground-Dienst weiter, wenn die App in
den Hintergrund wechselt. Eine Systembenachrichtigung zeigt den Fortschritt.
Unterbrochene `.part`-Downloads bleiben erhalten und werden beim nächsten
Versuch per HTTP-Range fortgesetzt, sofern der Downloadserver dies unterstützt.

## Sprachen

Die App unterscheidet zwei voneinander unabhängige Spracheinstellungen:

- **Transkriptionssprache:** automatische Erkennung, Deutsch oder Englisch.
  Bei automatischer Erkennung zeigt die App den von Whisper gelieferten
  Sprachcode als vollständige deutsche Sprachbezeichnung an, zum Beispiel
  **Dänisch** statt `da`.
- **GUI-Sprache:** Deutsch oder Englisch über den Flaggenumschalter. Die Auswahl
  wird bereits gespeichert; die vollständige englische Oberfläche folgt in
  einem späteren Arbeitsschritt.

Die angezeigte voraussichtliche Transkriptionsdauer wird aus Mediendauer und
gewähltem Whisper-Modell berechnet. Sie ist eine bewusst grobe Erstschätzung
und soll anhand praktischer Messwerte auf dem Zielgerät kalibriert werden.

## Datenschutz

Die Transkription läuft vollständig auf dem Android-Gerät. Nur der einmalige
Modelldownload benötigt Internetzugriff.

## Drittanbieter

`whisper.cpp` steht unter der MIT-Lizenz. Der vollständige Lizenztext befindet
sich unter [`licenses/whisper.cpp-MIT.txt`](licenses/whisper.cpp-MIT.txt).

Die technische Struktur und der Datenfluss sind in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) beschrieben.
