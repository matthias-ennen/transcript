# Transcript für Android

Transcript ist eine lokale Android-App, die Audio- und Videodateien über ihre
Audiospur mit [`whisper.cpp`](https://github.com/ggml-org/whisper.cpp)
transkribiert. Audio, Video und Transkriptinhalte werden für die Verarbeitung
nicht an einen Transkriptions- oder KI-Server übertragen. Nur Modelldownloads
benötigen eine Internetverbindung.

Innerhalb der App und unter dem App-Symbol lautet der aktuelle Produktname
**Transcript**. **Local Transcript** ist in Issue #44 als bevorzugter späterer
Produktname vorgemerkt, aber noch nicht freigegeben oder technisch umgesetzt.

## Produktentscheidung vom 29.08.2026

Die Rollen der lokalen Modelle sind verbindlich getrennt:

- **Whisper transkribiert.**
- **Der Benutzer korrigiert das Transkript bei Bedarf manuell.**
- **Qwen wertet das fertige akzeptierte Transkript optional aus.**

Die bisher entwickelte Qwen-Korrektur einzelner Whisper-Segmente beziehungsweise
eine automatische KI-Nachbearbeitung des Transkripts wird nicht als
Produktfunktion weitergeführt. Der lokale LLM-Unterbau selbst bleibt ausdrücklich
Bestandteil der App und wird für die neue Transkript-Auswertung weiterverwendet.

Für Version 1.0 sind vier bewusst begrenzte KI-Aktionen vorgesehen:

1. **Zusammenfassen**
2. **Kernaussagen / Stichpunkte**
3. **Aufgaben & To-dos**
4. **Entscheidungen / Besprechungsprotokoll**

Die KI arbeitet dabei auf der aktuell akzeptierten Transkriptfassung einschließlich
manueller Korrekturen. Das KI-Ergebnis ist ein **separates Resultat** und verändert
weder Transkripttext noch Zeitstempel, Segmentreihenfolge oder Transkript-Herkunft.

Issue #101 ist abgeschlossen: Die frühere KI-Nachbearbeitung ist aus dem
produktiven Transkriptworkflow entfernt, während Qwen-Modellverwaltung, Diagnose,
Benchmarks und der lokale `llama.cpp`-Unterbau erhalten bleiben. Issue #102 baut
als nächsten Schritt die separate KI-Auswertung am Ende des fertigen Transkripts
auf.

Die verbindliche Produkt- und Planungsentscheidung ist zusätzlich in
[`docs/PRODUCT_DIRECTION.md`](docs/PRODUCT_DIRECTION.md) dokumentiert.

## Aktueller Entwicklungsstand

Die App benötigt mindestens **Android 8.0 (API 26)** und transkribiert lokal auf
dem Gerät. Fünf Whisper-Qualitätsstufen reichen von **Whisper Tiny** bis
**Whisper Large V3**. Die Modelle werden bei Bedarf einzeln heruntergeladen und
sind nicht Bestandteil der APK.

Der lokale Qwen3.5-/`llama.cpp`-Unterbau funktioniert technisch end-to-end und
bleibt erhalten. Die Performancearbeit aus #61 wird auf die neuen realen
Auswertungsaufgaben umgestellt. Die sechs vorhandenen Qwen3.5-Varianten dienen
zunächst als Vergleichsmatrix, um die schnellste ausreichend gute Kombination aus
Modell, Quantisierung und Runtimepfad zu bestimmen.

## Zielworkflow für Version 1.0

```text
Audio/Video auswählen oder aufnehmen
→ optional Audio-Vorverarbeitung
→ im Sprachmodus optional Silero VAD
→ lokal mit Whisper transkribieren
→ Whisper-Original prüfen
→ Audio anhören / Fragmente wiederholen
→ bei Bedarf manuell korrigieren
→ akzeptierte Transkriptfassung
→ exportieren / teilen
→ optional lokale KI-Auswertung
→ separates KI-Ergebnis kopieren
```

Für Songs ist in #41 zusätzlich eine lokale Gesangstrennung **vor Whisper**
geplant. Allgemeine Audio-Vorverarbeitung wie Rauschminderung oder
Sprachhervorhebung wird in #103 zunächst anhand reproduzierbarer A/B-Tests bewertet
und erst bei nachgewiesenem Qualitätsgewinn als Produktfunktion geplant.

## Funktionsumfang und stabile Grundlagen

- Android-Dateiauswahl für unterstützte Audio- und Videoformate
- Video-Bildspur wird ignoriert; verarbeitet wird die Audiospur
- lokale Dekodierung zu 16-kHz-Mono-PCM
- abschnittsweise Transkription langer Aufnahmen mit begrenztem Speicherbedarf
- ein- bis fünfminütige Hauptabschnitte mit zwei Sekunden Kontextüberlappung
- robuste Zusammenführung der Whisper-Segmente an Chunk-Grenzen
- absolute Zeitstempel über die gesamte Aufnahme
- Hintergrundtranskription mit Systemmeldung, Abbruch und Wiederaufnahmepunkt
- atomare Wiederherstellung des fertigen Whisper-Originals und des übernommenen Bearbeitungsstands
- lokale Modellverwaltung mit fünf Whisper-Qualitätsstufen
- optionales Silero VAD 6.2.0
- lokaler Qwen3.5-/`llama.cpp`-Unterbau mit Modelldownload, Diagnose und Leistungsprofilen
- CPU-, KleidiAI- und Vulkan-/Hybrid-Testpfade für lokale LLM-Inferenz
- bearbeitbare Timeline vom Dateianfang bis Dateiende
- fortlaufende sichtbare Fragmentnummerierung einschließlich virtueller Pausen
- manuelle Korrektur bei schreibgeschützten Zeitstempeln
- Einzel-Wiederholungsmodus für Transkriptfragmente
- Export als TXT, SRT und JSON
- Teilen der Exportformate über das Android-Teilen-Menü
- direkte Mikrofonaufnahme im App-Bereich
- Play/Pause, Wellenform und positionsgenaue Wiedergabe
- automatisierte Debug-/Release-Builds über GitHub Actions
- dauerhafte APK-Signierung für installierbare Updates

## Aufnahme und Vorhören

Unter der Dateiauswahl kann eine Aufnahme direkt über die Mikrofontaste gestartet
werden. Die Aufnahme läuft in einem Mikrofon-Foreground-Service, bleibt bei
Bildschirmsperre beziehungsweise App-Wechsel aktiv und wird als aktuelle
Audiodatei übernommen.

Ausgewählte oder aufgenommene Audiodateien lassen sich vor und nach der
Transkription abspielen. Eine verdichtete Wellenform zeigt die Wiedergabeposition;
durch Tippen oder Ziehen kann gezielt gesprungen werden.

## Whisper-Abschnitte und Chunk-Grenzen

Längere Aufnahmen werden in Hauptabschnitten von einer bis fünf Minuten
verarbeitet. An den Grenzen werden jeweils zwei Sekunden zusätzlicher Audiokontext
verwendet, damit Wörter oder Sätze nicht an einem harten Chunk-Schnitt verloren
gehen.

Die lokalen Whisper-Zeitstempel werden anschließend auf die absolute Audioposition
verschoben. Überlappende Alternativen aus benachbarten Chunks werden anhand der
tatsächlichen Abschnittsgrenzen zusammengeführt. Echte zeitlich getrennte
Wiederholungen bleiben erhalten.

Verbleibende echte Whisper-Halluzinationen beziehungsweise Wiederholungsschleifen
bei langen Dateien werden separat in #78 untersucht. Sie sollen nicht durch eine
nachgelagerte LLM-Korrektur verdeckt werden.

## Timeline und manuelle Korrektur

Die sichtbare Timeline reicht vom Anfang bis zum Ende der Audiodatei. Größere
Lücken zwischen Whisper-Segmenten werden als leere, abspielbare und editierbare
Timeline-Karten ergänzt. Alle sichtbaren Karten erhalten eine fortlaufende
Fragmentnummer von `1` bis `N`; die interne Herkunft bleibt davon getrennt.

Whisper-Original und übernommener Bearbeitungsstand werden getrennt und atomar im
privaten App-Speicher gehalten. Einzelne Fragmente können während der Wiedergabe
kontrolliert und manuell korrigiert werden. Zeitstempel bleiben schreibgeschützt.

Für neu entstehende Produktzustände sind Whisper-Original und manuelle Änderung
die fachlich relevanten Transkriptzustände. Alte Entwicklungs-/Teststände mit
`AI`-Herkunft erhalten keine zusätzliche Migrationsanforderung; die neue
KI-Auswertung erzeugt ebenfalls keine Transkript-Herkunft `AI`.

## Geplante lokale KI-Auswertung

Nach einem fertigen beziehungsweise wiederhergestellten Transkript wird in #102
ein klar getrennter Bereich **KI-Auswertung** beziehungsweise **Mit KI auswerten**
ergänzt.

Die Auswertung:

- wird ausschließlich durch den Benutzer gestartet,
- verwendet die aktuell akzeptierte Transkriptfassung,
- verändert das Transkript niemals,
- zeigt ihr Ergebnis getrennt vom Transkript,
- muss mindestens Kopieren, Neu erzeugen und Abbrechen ermöglichen,
- darf eine normale Whisper-Transkription ohne installiertes Qwen-Modell nicht blockieren,
- darf lange Transkripte nicht still am Modellkontext abschneiden.

Für lange Transkripte ist deshalb in #102 ein mehrstufiger Ablauf vorgesehen:
sinnvolle Textabschnitte auswerten und deren Teilresultate anschließend zu einem
Gesamtergebnis zusammenführen.

## KI-Diagnose und Leistung

Der vorhandene Qwen3.5-/`llama.cpp`-Unterbau bleibt erhalten. Dazu gehören die
KI-Diagnose, modellbezogene Leistungsprofile, Hardwarediagnose, Speicher- und
Thermalschutz sowie CPU-, KleidiAI- und Vulkan-/Hybridpfade.

#61 misst künftig nicht mehr „Korrekturqualität“, sondern Laufzeit und Qualität
der vier realen Auswertungsaufgaben. Bewertet werden unter anderem:

- Modellladezeit
- Prompt-/Prefill-Zeit
- Zeit bis zum ersten Token
- Generierungszeit
- Ende-zu-Ende-Laufzeit
- RAM und thermisches Verhalten
- tatsächlich aktiver Backendpfad
- inhaltliche Treue, Abdeckung und Erfindungen bei den vier Auswertungen

Die sechs Qwen-Varianten bleiben zunächst eine echte Vergleichsmatrix. Eine
mögliche spätere Reduzierung wird erst nach den Gerätevergleichen bewusst
entschieden.

## Audioqualität vor Whisper

#103 evaluiert allgemeine lokale Vorverarbeitung wie Rauschminderung,
Sprachhervorhebung oder Normalisierung anhand identischer Quelldateien. #41
behandelt separat die Gesangstrennung/Source Separation für Songs. Beides liegt
vor Whisper und darf das Originalaudio nicht überschreiben.

## GUI-Sammelpaket

Kleine visuelle Korrekturen werden in #107 gesammelt und später gebündelt
umgesetzt, ohne die aktuelle Funktionsroadmap zu unterbrechen.

## Lange Aufnahmen und Hintergrundbetrieb

`TranscriptionService` läuft in einem privaten Android-Nebenprozess. Audio wird
nicht vollständig als PCM im Arbeitsspeicher gehalten. Abschnitte werden einzeln
vorbereitet; das Whisper-Modell wird über die vorbereiteten Abschnitte
wiederverwendet.

Nach jedem fertigen Abschnitt werden Textsegmente, erkannte Sprache und nächste
Audioposition atomar gesichert. Ein bewusster Abbruch hält den Lauf an und bewahrt
einen kompatiblen Zwischenstand.

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

## Whisper-Modelle

| Qualitätsstufe | Whisper-Modell | Modelldatei | Downloadgröße |
| --- | --- | --- | ---: |
| Sehr schnell | Whisper Tiny | `ggml-tiny.bin` | ca. 77,7 MB |
| Schnell | Whisper Base | `ggml-base.bin` | ca. 148 MB |
| Ausgewogen | Whisper Small Q5_1 | `ggml-small-q5_1.bin` | ca. 190 MB |
| Sehr genau | Whisper Large V3 Turbo Q5_0 | `ggml-large-v3-turbo-q5_0.bin` | ca. 574 MB |
| Maximale Qualität | Whisper Large V3 Q5_0 | `ggml-large-v3-q5_0.bin` | ca. 1,08 GB |

Jedes Modell kann einzeln heruntergeladen, ausgewählt und gelöscht werden. Die
SHA-256-Prüfsumme wird nach dem Download kontrolliert.

Die Qwen-Vergleichsmatrix ist separat in
[`docs/AI_MODELS.md`](docs/AI_MODELS.md) dokumentiert.

## Sprachen

Die App unterscheidet zwei unabhängige Spracheinstellungen:

- **Transkriptionssprache:** Whisper-Sprache beziehungsweise automatische Erkennung.
- **GUI-Sprache:** Deutsch/Englisch; die vollständige Lokalisierung ist Aufgabe von #33.

Die spätere KI-Auswertung soll standardmäßig in der Sprache des Transkripts
antworten. Ein Wechsel der GUI-Sprache übersetzt Benutzerinhalte nicht automatisch.

## Datenschutz

Whisper-Transkription, VAD, geplante Gesangstrennung und lokale Qwen-Auswertung
laufen auf dem Android-Gerät. Nur Modelldownloads benötigen Internetzugriff.
Transkriptinhalte werden nicht an einen externen KI-Dienst übertragen.

Die endgültigen rechtlichen Texte und Play-Data-Safety-Angaben werden in #51 und
#35 mit dem tatsächlichen Releaseverhalten abgeglichen.

## Drittanbieter

`whisper.cpp` und `llama.cpp` sind als lokale Open-Source-Komponenten eingebunden.
Lizenzhinweise befinden sich unter `licenses/` und in
`THIRD_PARTY_NOTICES.md`.

Die aktuelle technische Ist-Struktur ist in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) beschrieben. Die verbindliche
Produktrichtung steht in
[`docs/PRODUCT_DIRECTION.md`](docs/PRODUCT_DIRECTION.md).
