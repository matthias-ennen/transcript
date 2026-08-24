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
auf dem Android-Gerät und bietet fünf Qualitätsstufen von **Whisper Tiny
(„Sehr schnell“)** bis **Whisper Large V3 („Maximale Qualität“)**. Die Modelle
werden bei Bedarf einzeln heruntergeladen und sind nicht Bestandteil der APK.

Die lokale KI-Nachbearbeitung mit Qwen3.5 ist technisch funktionsfähig. Zwei
Strategien stehen zur Verfügung: **Segmentweise** und **Abschnittsweise**. Die
aktuelle Hauptbaustelle ist nicht mehr die grundsätzliche Machbarkeit, sondern
die Laufzeitoptimierung der lokalen Qwen-Inferenz auf Android.

## Funktionsumfang

- Android-Dateiauswahl für unterstützte Audio- und Videoformate
- Video-Bildspur wird ignoriert; verarbeitet wird ausschließlich die Audiospur
- Offline-Dekodierung zu 16 kHz Mono-PCM
- abschnittsweise Transkription langer Aufnahmen mit konstant begrenztem Speicherbedarf
- frei wählbare Ein- bis Fünf-Minuten-Abschnitte mit zwei Sekunden Kontextüberlappung
- robuste Zusammenführung der Whisper-Segmente an Chunk-Grenzen
- absolute Zeitstempel über die gesamte Aufnahme hinweg
- Hintergrundtranskription mit Systemmeldung, Abbruch und gesichertem Wiederaufnahmepunkt
- atomare Wiederherstellung des fertigen Whisper-Originals und des zuletzt übernommenen Bearbeitungsstands
- lokale Modellverwaltung mit fünf Whisper-Qualitätsstufen
- optionales Silero VAD 6.2.0 zur lokalen Sprachanalyse
- optionale lokale KI-Nachbearbeitung mit Qwen3.5 in drei Modellgrößen
- globale KI-Nachbearbeitungsstrategie **Segmentweise** oder **Abschnittsweise**
- dauerhafte KI-Diagnose-Seite für eigene Fragen, Modellvergleich und App-Protokoll
- eigene Seite **KI-Leistung und Hardware** mit CPU-/KleidiAI-/Vulkan-Steuerung,
  Hardwarediagnose, Wärme- und Speicherschutz sowie reproduzierbarem Benchmark
- Transkriptionssprache wahlweise automatisch erkannt, Deutsch oder Englisch
- Ausgabe mit Segmentzeitstempeln
- lückenlose, bearbeitbare Timeline vom Dateianfang bis Dateiende
- fortlaufende sichtbare Fragmentnummerierung für **jede** Timeline-Karte,
  einschließlich künstlich erzeugter leerer Pausen
- gemeinsamer Korrekturmodus bei schreibgeschützten Zeitstempeln
- Export als TXT, SRT und JSON
- Teilen von TXT, SRT und JSON einzeln oder gemeinsam über das Android-Teilen-Menü
- direkte Mikrofonaufnahme mit Speicherung im App-Bereich
- Play/Pause, Wellenform und positionsgenaue Wiedergabe
- halbtransparente Pfeilkapsel zum schnellen Sprung an den App-Anfang bei langen Transkripten
- automatischer Debug-/Release-Build mit GitHub Actions
- dauerhafte APK-Signierung für installierbare Updates

## Aufnahme und Vorhören

Unter der Dateiauswahl kann eine Aufnahme direkt über die Mikrofontaste gestartet
werden. Während der Aufnahme wird dieselbe Taste zum Stopp-Symbol. Die fertige
AAC-/M4A-Datei wird im privaten App-Speicher abgelegt und sofort als aktuelle
Audiodatei ausgewählt.

Die Aufnahme läuft in einem Mikrofon-Foreground-Service mit sichtbarer
Android-Systemmeldung und Beenden-Aktion. Dadurch bleibt sie bei ausgeschaltetem
Bildschirm, Gerätesperre und einem Wechsel zu einer anderen App aktiv.

Ausgewählte oder aufgenommene Audiodateien lassen sich vor und nach der
Transkription abspielen. Eine verdichtete Wellenform zeigt die Wiedergabeposition;
durch Tippen oder Ziehen kann zu einer anderen Stelle gesprungen werden.

## Whisper-Abschnitte und Chunk-Grenzen

Längere Aufnahmen werden in einstellbaren Hauptabschnitten von einer bis fünf
Minuten verarbeitet. Jeder Abschnitt enthält an seinen Grenzen zusätzlich zwei
Sekunden Audiokontext. Dieser Overlap ist gewollt, damit Wörter oder Sätze nicht
an einer harten Chunk-Grenze verloren gehen.

Die App verschiebt die lokalen Whisper-Zeitstempel anschließend auf die absolute
Audioposition. Segmente werden einem Hauptabschnitt zugeordnet und beim
Zusammenführen benachbarter Chunks zusätzlich gegeneinander abgeglichen. Wenn
Whisper denselben Overlap in zwei Chunks unterschiedlich segmentiert, werden die
Alternativen bereinigt. Verbleibende echte Teilüberschneidungen werden zeitlich
sauber getrennt. Die Logik arbeitet mit den tatsächlichen Abschnittsgrenzen und
ist deshalb nicht auf eine bestimmte Einstellung wie zwei oder drei Minuten
festgelegt.

## Timeline und Fragmentnummern

Die sichtbare Zeitleiste reicht vom Anfang bis zum Ende der Audiodatei. Größere
Lücken zwischen Whisper-Segmenten werden als eigene leere, abspielbare und
bearbeitbare Timeline-Karten ergänzt. Dasselbe gilt gegebenenfalls für eine
Pause am Anfang oder Ende der Aufnahme.

Die sichtbare Nummer rechts an einer Karte ist **keine von Whisper gelieferte
stabile ID**. Sie bezeichnet die Position des Fragments in der fertigen Timeline.
Deshalb werden alle sichtbaren Karten konsequent von `1` bis `N` durchnummeriert,
auch virtuelle Pausen. Die interne Herkunft bleibt davon getrennt erhalten:
Whisper-Rohsegmente, virtuelle Pausen sowie manuell oder per KI veränderte Inhalte
werden weiterhin technisch unterschieden.

Leere virtuelle Pausen werden nur im JSON exportiert und mit
`origin: "virtual_pause"` gekennzeichnet. Wird eine solche Pause manuell mit Text
gefüllt, erhält sie `origin: "manual"` und erscheint zusätzlich in TXT und SRT.

## Transkript korrigieren

Whisper-Original und übernommener Bearbeitungsstand werden getrennt und atomar im
privaten App-Speicher gehalten. Nach einem Prozessneustart stellt die App das
zuletzt fertige Ergebnis wieder her. Eine neue Datei, Aufnahme oder Transkription
ersetzt diesen Stand bewusst.

Einzelne Fragmente können direkt geöffnet, während der Wiedergabe kontrolliert
und manuell korrigiert werden. Zusätzlich können komplette Zeitgruppen zur
KI-Nachbearbeitung übergeben werden. Zeitstempel und Reihenfolge bleiben dabei in
der App unter Kontrolle.

## Lokale KI-Nachbearbeitung

Für Qwen3.5 existieren zwei globale Verarbeitungsstrategien, die mit derselben
inhaltlichen Korrekturanweisung arbeiten:

### Segmentweise

Die vollständige Zeitgruppe wird einmal als gemeinsamer, schreibgeschützter
Kontext in die Modellsitzung aufgenommen. Danach werden die Zielsegmente
nacheinander als kleine Aufgaben **append-only** ergänzt. Wegen der hybriden
Qwen3.5-Architektur wird der native Modellzustand zwischen den Segmenten nicht
auf einen früheren KV-Zustand zurückgespult. Jedes Ergebnis wird von der App dem
jeweiligen Zielsegment zugeordnet und validiert.

### Abschnittsweise

Die gesamte Zeitgruppe wird als eine Korrekturaufgabe verarbeitet. Das Modell
soll nur die tatsächlich geänderten Fragment-IDs samt korrigiertem Text
zurückgeben. Fehlende IDs gelten als unverändert. Die App prüft unbekannte oder
doppelte IDs und übernimmt nur gültige Ergebnisse.

Beide Strategien funktionieren technisch. Die derzeitige Herausforderung ist die
noch zu hohe Laufzeit auf dem mobilen Gerät. Die systematische Optimierung von
CPU-Konfiguration, Threads, Batchgrößen, Kontext, KleidiAI und Vulkan/GPU ist das
nächste eigene Arbeitspaket.

Die Qwen-Chatvorlage wird für freie Tests und Transkriptkorrekturen mit
`enable_thinking=false` verwendet. Korrekturantworten werden strukturiert
zurückgegeben und die Diagnose protokolliert Modellladezeit, Promptverarbeitung,
Zeit bis zum ersten Token, Antwortdauer, Gesamtdauer und Tokenzahlen.

## KI-Diagnose und Leistung

Die dauerhafte Seite **KI-Diagnose** enthält einen freien KI-Testbereich und das
App-Diagnoseprotokoll. Ein geladenes Modell kann für mehrere Anfragen im Speicher
bleiben. Die freie Unterhaltung ist von der Transkriptkorrektur getrennt.

Die Seite **KI-Leistung und Hardware** verwaltet modellbezogene Leistungsprofile.
Dort können unter anderem Kontext, Batchgrößen, Prompt- und Ausgabethreads,
CPU-Affinität, Standard-/KleidiAI-Kernel und CPU-/Vulkan-/Hybridpfade untersucht
werden. Diagnose und Benchmark sollen ausdrücklich nachweisen, welcher native
Backendpfad bei einem Lauf tatsächlich aktiv war.

Vor lokaler KI-Ausführung prüfen Schutzmechanismen verfügbare Speicherreserve und
thermischen Zustand. Fehler eines GPU-Pfades können kontrolliert auf CPU
zurückfallen.

## Lange Aufnahmen und Hintergrundbetrieb

`TranscriptionService` läuft in einem privaten Android-Nebenprozess. Audio wird
nicht vollständig als PCM im Arbeitsspeicher gehalten. Die benötigten Abschnitte
werden einzeln dekodiert und als 16-kHz-Mono-PCM vorbereitet; anschließend wird
das Whisper-Modell einmal geladen und über die vorbereiteten Abschnitte
wiederverwendet.

Nach jedem fertigen Abschnitt werden Textsegmente, erkannte Sprache und nächste
Audioposition atomar gesichert. Ein bewusster Abbruch hält den Lauf an und
bewahrt einen kompatiblen Zwischenstand. Die Foreground-Systemmeldung zeigt den
Fortschritt und bietet einen Abbruchknopf.

Fertige Transkripte, Modelle, Aufnahmen und Einstellungen bleiben im privaten
App-Speicher. Android-Cloud-Backup und Geräteübertragung sind für die App
deaktiviert.

## APK bauen

Das Repository bindet `whisper.cpp` und `llama.cpp` als Git-Submodule ein. Beim
lokalen Klonen daher die Submodule mit abrufen:

```bash
git clone --recurse-submodules https://github.com/matthias-ennen/transcript.git
cd transcript
./gradlew assembleDebug
```

Für einen vollständigen lokalen Produktbuild:

```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease bundleRelease
```

Der Workflow **Build Android APK** erstellt bei Pushes und Pull Requests die
entsprechenden Android-Artefakte. GitHub Actions verwendet eine dauerhaft
hinterlegte Signierung und eine steigende `versionCode`-Nummer, sodass neuere
signierte APKs als Update über eine bestehende Installation eingespielt werden
können.

## Modelle und Speicherbedarf

| Qualitätsstufe | Whisper-Modell | Modelldatei | Downloadgröße |
| --- | --- | --- | ---: |
| Sehr schnell | Whisper Tiny | `ggml-tiny.bin` | ca. 77,7 MB |
| Schnell | Whisper Base | `ggml-base.bin` | ca. 148 MB |
| Ausgewogen | Whisper Small Q5_1 | `ggml-small-q5_1.bin` | ca. 190 MB |
| Sehr genau | Whisper Large V3 Turbo Q5_0 | `ggml-large-v3-turbo-q5_0.bin` | ca. 574 MB |
| Maximale Qualität | Whisper Large V3 Q5_0 | `ggml-large-v3-q5_0.bin` | ca. 1,08 GB |

Jedes Modell kann einzeln heruntergeladen, ausgewählt und gelöscht werden. Nach
dem Download wird die SHA-256-Prüfsumme kontrolliert. Die Modelle liegen im
privaten App-Speicher und werden nicht in das Git-Repository oder die APK
eingecheckt.

## Sprachen

Die App unterscheidet zwei voneinander unabhängige Spracheinstellungen:

- **Transkriptionssprache:** automatische Erkennung, Deutsch oder Englisch.
- **GUI-Sprache:** Deutsch oder Englisch über den vorbereiteten Flaggenumschalter;
  die vollständige englische Oberfläche ist noch ein eigener späterer Schritt.

## Datenschutz

Die Transkription und die optionale KI-Nachbearbeitung laufen lokal auf dem
Android-Gerät. Nur Modelldownloads benötigen Internetzugriff.

## Drittanbieter

`whisper.cpp` und `llama.cpp` sind als lokale Open-Source-Komponenten eingebunden.
Die Lizenzhinweise befinden sich im Repository unter `licenses/` und
`THIRD_PARTY_NOTICES.md`.

Die technische Struktur und der Datenfluss sind in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) beschrieben.
