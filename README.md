# Transcript für Android

Transcript ist eine lokale Android-App, die Audio- und Videodateien über ihre
Audiospur mit [`whisper.cpp`](https://github.com/ggml-org/whisper.cpp)
transkribiert. Audio, Video und Transkriptinhalte werden für die Verarbeitung
nicht an einen Transkriptions- oder KI-Server übertragen. Nur Modelldownloads
benötigen eine Internetverbindung.

Innerhalb der App und unter dem App-Symbol lautet der aktuelle Produktname
**Transcript**. **Local Transcript** ist in Issue #44 als bevorzugter späterer
Produktname vorgemerkt, aber noch nicht freigegeben oder technisch umgesetzt.

## Produktprinzip

Die Rollen der lokalen Modelle sind verbindlich getrennt:

- **Whisper transkribiert.**
- **Der Benutzer korrigiert das Transkript bei Bedarf manuell.**
- **Qwen wertet das fertige akzeptierte Transkript optional aus.**

Die frühere Qwen-Korrektur einzelner Whisper-Segmente wird nicht als
Produktfunktion weitergeführt. Der lokale LLM-Unterbau bleibt für die getrennte
Transkript-Auswertung erhalten.

Für Version 1.0 stehen vier lokale KI-Aktionen zur Verfügung:

1. **Zusammenfassen**
2. **Kernaussagen / Stichpunkte**
3. **Aufgaben & To-dos**
4. **Entscheidungen / Besprechungsprotokoll**

Die KI arbeitet auf der aktuell akzeptierten Transkriptfassung einschließlich
manueller Korrekturen. Das KI-Ergebnis ist ein separates Resultat und verändert
weder Transkripttext noch Zeitstempel, Segmentreihenfolge oder Herkunft.

Die verbindliche Produktrichtung ist zusätzlich in
[`docs/PRODUCT_DIRECTION.md`](docs/PRODUCT_DIRECTION.md) dokumentiert.

## Aktueller Entwicklungsstand – 06.09.2026

Die App benötigt mindestens **Android 8.0 (API 26)** und verarbeitet Inhalte lokal
auf dem Gerät. Fünf Whisper-Qualitätsstufen reichen von **Whisper Tiny** bis
**Whisper Large V3**. Modelle werden bei Bedarf einzeln heruntergeladen und sind
nicht Bestandteil der APK.

Abgeschlossen sind unter anderem:

- #101 – alte KI-Korrektur aus dem produktiven Transkriptworkflow entfernt
- #102 – separate lokale KI-Auswertung des fertigen Transkripts integriert
- #61 – erste Mess-/Optimierungsphase der lokalen KI-Auswertung
- #41 / PR #114 – optionale Stimmisolierung vor Whisper integriert
- #115 / PR #117 – Kim Vocal 2 Native/GGUF ergänzt und Android-Nutzung stabilisiert

Der aktuelle Stimmisolierungsstand wurde auf zwei Xiaomi-Android-Geräten praktisch
geprüft. **Kim Vocal 2 – Native/GGUF** läuft dort vollständig bis einschließlich
Whisper. Der bestehende große 747-MB-FP16-ONNX-Kim bleibt bewusst erhalten, ist auf
den beiden vorhandenen Android-Geräten wegen eines sehr hohen Speicherpeaks in der
ersten Inferenz aber nicht praktisch nutzbar. Ein späterer High-End-Test auf
Windows-/Desktop-Hardware ist #40 zugeordnet.

## Zielworkflow für Version 1.0

```text
Audio/Video auswählen oder aufnehmen
→ optional Stimmisolierung
→ optional Silero VAD
→ lokal mit Whisper transkribieren
→ Whisper-Original prüfen
→ Audio anhören / Fragmente wiederholen
→ bei Bedarf manuell korrigieren
→ akzeptierte Transkriptfassung
→ exportieren / teilen
→ optional lokale KI-Auswertung
→ separates KI-Ergebnis kopieren
```

Stimmisolierung und VAD sind unabhängige Vorverarbeitungsschritte. Allgemeine
Audio-Vorverarbeitung wie Rauschminderung, Sprachhervorhebung oder Normalisierung
wird in #103 zunächst anhand reproduzierbarer A/B-Tests bewertet und erst bei
nachgewiesenem Qualitätsgewinn als Produktfunktion geplant.

## Funktionsumfang und stabile Grundlagen

- Android-Dateiauswahl für unterstützte Audio- und Videoformate
- Video-Bildspur wird ignoriert; verarbeitet wird die Audiospur
- direkte Mikrofonaufnahme im App-Bereich
- lokale Dekodierung und abschnittsweise Whisper-Verarbeitung
- ein- bis fünfminütige Hauptabschnitte mit zwei Sekunden Kontextüberlappung
- robuste Zusammenführung der Whisper-Segmente an Chunk-Grenzen
- absolute Zeitstempel über die gesamte Aufnahme
- Hintergrundtranskription mit Systemmeldung, Abbruch und Wiederaufnahmepunkt
- atomare Wiederherstellung von Whisper-Original und Bearbeitungsstand
- lokale Modellverwaltung mit fünf Whisper-Qualitätsstufen
- optionales Silero VAD 6.2.0
- optionale lokale Stimmisolierung mit vier Separator-Varianten
- kontinuierliche isolierte Audiospur auf derselben Zeitachse wie das Original
- Player-Umschaltung **Original | Stimmisolierung** mit passender Wellenform
- Pipeline-Diagnose mit Laufzeiten, Echtzeitfaktor und Engpass
- modellbezogene Seite **Stimmisolierungs-Leistung**
- lokaler Qwen3.5-/`llama.cpp`-Unterbau mit Modelldownload, Diagnose und Leistungsprofilen
- CPU-, KleidiAI- und Vulkan-/Hybrid-Testpfade für lokale LLM-Inferenz
- bearbeitbare Timeline vom Dateianfang bis Dateiende
- fortlaufende sichtbare Fragmentnummerierung einschließlich virtueller Pausen
- manuelle Korrektur bei schreibgeschützten Zeitstempeln
- Einzel-Wiederholungsmodus für Transkriptfragmente
- Export als TXT, SRT und JSON
- Teilen der Exportformate über das Android-Teilen-Menü
- Play/Pause, Wellenform und positionsgenaue Wiedergabe
- automatisierte Debug-/Release-Builds über GitHub Actions
- dauerhafte APK-Signierung für installierbare Updates

## Stimmisolierung vor Whisper

Die Stimmisolierung ist optional und unabhängig vom VAD. Die aktuelle Pipeline ist:

```text
Originalaudio
→ optional Stimmisolierung
→ optional Silero VAD
→ Whisper
```

Der Modellkatalog enthält vier Varianten in dieser Reihenfolge:

1. **Schnell – Open-Unmix UMXHQ**
2. **Ausgewogen – Deezer Spleeter 2-stem FP16**
3. **Kim Vocal 2 – Native/GGUF**
4. **Hohe Qualität – Kim Vocal 2 / Mel-Band RoFormer (ONNX)**

Die Native/GGUF-Variante verwendet einen nativen CrispASR-/Mel-Band-RoFormer-Pfad
mit OpenBLAS und eingebautem Vulkan-Payload. Pro Separator-Modell kann ein eigenes
Leistungsprofil gespeichert werden. Für Native/GGUF stehen ein automatischer Pfad
mit Vulkan, wenn verfügbar, und ein reproduzierbarer CPU-/OpenBLAS-Pfad zur
Verfügung.

Die App erzeugt bei aktiver Stimmisolierung eine zusammenhängende isolierte Spur
auf derselben Zeitachse wie das Original. Whisper liest anschließend aus dieser
vorbereiteten Spur. Separator-Ressourcen werden freigegeben, bevor Whisper startet.

### Bekannte Android-Grenze des großen ONNX-Kim

Der 747-MB-FP16-ONNX-Export des großen Kim Vocal 2 bleibt als High-End-Variante im
Katalog. Auf den beiden vorhandenen Android-Testgeräten scheitert seine erste
Inferenz an massivem Speicherbedarf. Beim Xiaomi Pad 8 Pro wurde im Android-
Bugreport ein RSS-Anstieg bis ungefähr **5,9 GB** und ein anschließender Kill durch
`lmkd` bestätigt.

Deshalb ist **Native/GGUF der primäre mobile Kim-Pfad**. Der große ONNX-Kim wird
nicht entfernt; ein späterer Test auf stärkerer Windows-/Desktop-Hardware gehört zu
#40.

## Pipeline-Diagnose

Nach einer Transkription zeigt die Ergebnis-Kachel die tatsächlich verwendete
Pipeline und ihre Laufzeiten. Erfasst werden je nach Lauf:

- Stimmisolierung einschließlich Modell
- Audioaufbereitung
- VAD / Segmentierung
- Whisper einschließlich Modell
- Gesamtzeit
- Audiodauer und Verarbeitungsdauer
- Echtzeitfaktor
- ermittelter Engpass

Damit lassen sich Stimmisolierung, Whisper und übrige Verarbeitungsschritte auf
einem konkreten Gerät getrennt bewerten.

## Aufnahme und Vorhören

Unter der Dateiauswahl kann eine Aufnahme direkt über die Mikrofontaste gestartet
werden. Die Aufnahme läuft in einem Mikrofon-Foreground-Service, bleibt bei
Bildschirmsperre beziehungsweise App-Wechsel aktiv und wird als aktuelle
Audiodatei übernommen.

Ausgewählte oder aufgenommene Audiodateien lassen sich vor und nach der
Transkription abspielen. Eine verdichtete Wellenform zeigt die Wiedergabeposition;
durch Tippen oder Ziehen kann gezielt gesprungen werden. Liegt eine passende
isolierte Spur vor, kann der Player zwischen **Original** und
**Stimmisolierung** umschalten, ohne die aktuelle Position zu verlieren.

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
nachgelagerte Text-KI verdeckt werden.

## Timeline und manuelle Korrektur

Die sichtbare Timeline reicht vom Anfang bis zum Ende der Audiodatei. Größere
Lücken zwischen Whisper-Segmenten werden als leere, abspielbare und editierbare
Timeline-Karten ergänzt. Alle sichtbaren Karten erhalten eine fortlaufende
Fragmentnummer von `1` bis `N`; die interne Herkunft bleibt davon getrennt.

Whisper-Original und übernommener Bearbeitungsstand werden getrennt und atomar im
privaten App-Speicher gehalten. Einzelne Fragmente können während der Wiedergabe
kontrolliert und manuell korrigiert werden. Zeitstempel bleiben schreibgeschützt.

Für neue Produktzustände sind Whisper-Original und manuelle Änderung die fachlich
relevanten Transkriptzustände. Die lokale KI-Auswertung erzeugt keinen
Transkript-Herkunftsstatus `AI`.

## Lokale KI-Auswertung

Nach einem fertigen beziehungsweise wiederhergestellten Transkript steht der klar
getrennte Bereich **Mit KI auswerten** zur Verfügung.

Die Auswertung:

- wird ausschließlich durch den Benutzer gestartet,
- verwendet die aktuell akzeptierte Transkriptfassung,
- verändert das Transkript niemals,
- zeigt ihr Ergebnis getrennt vom Transkript,
- unterstützt Kopieren, Neu erzeugen und Abbrechen,
- blockiert eine normale Whisper-Transkription ohne installiertes Qwen-Modell nicht,
- verarbeitet lange Transkripte mehrstufig, statt sie still am Modellkontext abzuschneiden.

Die sechs vorhandenen Qwen3.5-Varianten bleiben derzeit als Vergleichsmatrix
sichtbar. Die erste Mess-/Optimierungsphase wurde in #61 abgeschlossen; eine
weitergehende Beschleunigung folgt später in #113.

## Audioqualität vor Whisper

#103 ist das nächste funktionale Analysepaket. Es evaluiert allgemeine lokale
Vorverarbeitung wie Rauschminderung, Sprachhervorhebung oder Normalisierung anhand
identischer Quelldateien. Eine sichtbare neue Produktoption entsteht nur bei einem
reproduzierbar nachgewiesenen Whisper-Qualitätsgewinn.

## Lange Aufnahmen und Hintergrundbetrieb

`TranscriptionService` läuft in einem privaten Android-Nebenprozess. Große
Medien-/Modellobjekte werden nicht über Binder oder Intent transportiert.
Zwischenstände werden atomar gesichert, sodass ein kontrollierter Abbruch und eine
spätere Fortsetzung möglich bleiben.

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

Der CI-Build erzeugt außerdem die für Native/GGUF benötigte gepinnte
CrispASR-/OpenBLAS-/Vulkan-Runtime für Android ARM64.

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

## Offene Roadmap

Die verbindliche Reihenfolge wird in Issue #26 gepflegt. Nach dem reinen
Dokumentationspaket #118 ist der nächste funktionale Schritt **#103**.

Danach folgen derzeit:

`#103 → #78 → #113 → #39 → #51 → #33 → #34 → #27 → #35`

#116 bleibt parallel als verbindliches Spleeter-Lizenz-Release-Gate offen.
#40 ist ausdrücklich der Windows-/Microsoft-Desktop-Ausbau **nach Android 1.0**.

## Sprachen

Die App unterscheidet zwei unabhängige Spracheinstellungen:

- **Transkriptionssprache:** Whisper-Sprache beziehungsweise automatische Erkennung.
- **GUI-Sprache:** Deutsch/Englisch; die vollständige Lokalisierung ist Aufgabe von #33.

Ein Wechsel der GUI-Sprache übersetzt Benutzerinhalte nicht automatisch.

## Datenschutz

Whisper-Transkription, VAD, Stimmisolierung und lokale Qwen-Auswertung laufen auf
dem Android-Gerät. Nur Modelldownloads benötigen Internetzugriff.
Transkriptinhalte werden nicht an einen externen KI-Dienst übertragen.

Die endgültigen rechtlichen Texte und Play-Data-Safety-Angaben werden in #51 und
#35 mit dem tatsächlichen Releaseverhalten abgeglichen.

## Drittanbieter

`whisper.cpp`, `llama.cpp` und weitere lokale Open-Source-Komponenten werden mit
gepinnten beziehungsweise dokumentierten Versionen eingebunden. Lizenzhinweise
befinden sich unter `licenses/` und in `THIRD_PARTY_NOTICES.md`.

Die Spleeter-Gewichtelizenz wird separat in #116 als Release-Gate geklärt.

Die aktuelle technische Ist-Struktur ist in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) beschrieben. Die verbindliche
Produktrichtung steht in
[`docs/PRODUCT_DIRECTION.md`](docs/PRODUCT_DIRECTION.md).
