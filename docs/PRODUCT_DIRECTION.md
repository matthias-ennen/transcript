# Produktrichtung Transcript 1.0

Stand: **06.09.2026**

Verbindliche GitHub-Issues: #26, #101, #102, #61, #41, #115, #116, #103,
#78, #113, #39, #51, #33, #34, #27, #35, #40, #44

## Produktentscheidung

Transcript 1.0 ist ein **local-first Werkzeug für einen aktuellen
Transkriptionsvorgang**. Die fachlichen Rollen sind verbindlich getrennt:

- **Whisper transkribiert.**
- **Der Benutzer korrigiert bei Bedarf manuell.**
- **Qwen wertet das fertige akzeptierte Transkript optional aus.**

Die frühere automatische KI-Korrektur des Whisper-Transkripts wird nicht als
Produktfunktion weitergeführt. Der lokale Qwen-/`llama.cpp`-Unterbau bleibt
Bestandteil der App und dient der getrennten Transkript-Auswertung.

## Zielworkflow

```text
Audio/Video auswählen oder aufnehmen
→ optional Stimmisolierung
→ optional Silero VAD
→ Whisper
→ Whisper-Original
→ anhören / Fragmente wiederholen
→ bei Bedarf manuell korrigieren
→ akzeptierte Transkriptfassung
→ exportieren / teilen
→ optional lokale KI-Auswertung
→ separates KI-Ergebnis
→ kopieren
```

Stimmisolierung und VAD sind unabhängige Vorverarbeitungsschritte. Das
Originalaudio wird nicht überschrieben.

## Transkriptzustand

Für neu entstehende Produktzustände gilt:

- unveränderliches Whisper-Original,
- manuell bearbeitete beziehungsweise akzeptierte Fassung,
- Herkunft `ORIGINAL` / `MANUAL`.

Alte Entwicklungs-/Teststände mit früherer `AI`-Herkunft erzeugen keine neue
Kompatibilitätsanforderung. Die heutige KI-Auswertung erzeugt keinen
Transkript-Herkunftsstatus `AI`.

## Lokale KI-Auswertung für Version 1.0

#102 ist abgeschlossen und stellt vier feste Standardaktionen bereit:

1. **Zusammenfassen** – kompakte inhaltlich treue Zusammenfassung.
2. **Kernaussagen / Stichpunkte** – wichtigste Aussagen strukturiert verdichten.
3. **Aufgaben & To-dos** – nur tatsächlich erkennbare Aufgaben und nächste Schritte.
4. **Entscheidungen / Besprechungsprotokoll** – Entscheidungen, Ergebnisse und
   gegebenenfalls nächste Schritte strukturiert darstellen.

Die Auswahl bleibt bewusst klein. Ein allgemeiner freier KI-Chat ist keine
Kernfunktion von Version 1.0.

### Regeln für KI-Ergebnisse

- KI-Auswertung startet niemals automatisch nach Whisper.
- Der Benutzer löst jede Auswertung bewusst aus.
- Quelle ist die **aktuell akzeptierte Transkriptfassung**, einschließlich
  manueller Korrekturen.
- KI-Ausgabe wird getrennt vom Transkript dargestellt.
- KI-Ausgabe darf Transkripttext, Zeitstempel, Segmentreihenfolge und Herkunft
  niemals verändern.
- Ergebnis ist kopierbar; Abbruch und erneute Erzeugung gehören zum Produktumfang.
- Wenn keine Aufgaben oder Entscheidungen erkennbar sind, soll das Modell dies
  mitteilen statt Inhalte zu erfinden.
- Ergebnis soll standardmäßig in der Sprache des Transkripts erzeugt werden.
- GUI-Sprache und Sprache des Benutzerinhalts bleiben getrennt.
- Ein fehlendes Qwen-Modell darf die normale Whisper-Nutzung nicht blockieren.

### Lange Transkripte

Ein fertiges Transkript darf nicht still am Kontextlimit des lokalen Modells
abgeschnitten werden. Für längere Texte arbeitet die App mehrstufig:

1. vollständige Quelle in sinnvolle Textabschnitte teilen,
2. Abschnitte mit derselben fachlichen Aufgabe auswerten,
3. Teilresultate sammeln,
4. daraus ein Gesamtergebnis erzeugen,
5. keinen Quellenbereich unbemerkt auslassen.

#61 hat die erste Mess-/Optimierungsbasis dafür geschaffen. #113 übernimmt später
die weitergehende Beschleunigung.

## Qwen-Modellstrategie

Die sechs bereits integrierten Qwen3.5-Varianten bleiben zunächst als echte
Vergleichsmatrix erhalten. Eine Reduzierung erfolgt nicht ohne ausdrückliche
Produktentscheidung.

Entscheidend sind gemeinsam:

- Ende-zu-Ende-Laufzeit,
- Zeit bis zum ersten Token,
- Prompt-/Prefill- und Generierungsleistung,
- RAM und thermisches Verhalten,
- Stabilität,
- tatsächlich aktiver CPU-/KleidiAI-/Vulkan-/Hybridpfad,
- inhaltliche Qualität der vier Standardaktionen.

Gesucht wird nicht automatisch das kleinste oder schnellste Modell, sondern die
**schnellste ausreichend gute** Kombination für den realen Produktnutzen.

## Stimmisolierung vor Whisper

#41 / PR #114 und #115 / PR #117 sind abgeschlossen. Die frühere sichtbare
Produktlogik eines getrennten „Songmodus“ wurde durch eine allgemein nutzbare
optionale **Stimmisolierung** ersetzt.

Die Verarbeitung ist:

```text
Originalaudio
→ optional Stimmisolierung
→ optional Silero VAD
→ Whisper
```

Die Stimmisolierung erzeugt eine zusammenhängende interne Spur auf derselben
Zeitachse wie das Original. Im Player kann zwischen **Original** und
**Stimmisolierung** umgeschaltet werden. Whisper verwendet bei aktiver
Stimmisolierung genau diese vorbereitete Spur.

### Modellkatalog Stimmisolierung

Verbindliche Reihenfolge:

1. **Schnell – Open-Unmix UMXHQ**
2. **Ausgewogen – Deezer Spleeter 2-stem FP16**
3. **Kim Vocal 2 – Native/GGUF**
4. **Hohe Qualität – Kim Vocal 2 / Mel-Band RoFormer (ONNX)**

Native/GGUF basiert auf einer nativen CrispASR-/Mel-Band-RoFormer-Integration mit
OpenBLAS und einem Android-Vulkan-Payload. Für jedes Separator-Modell gibt es ein
eigenes Leistungsprofil. Performanceänderungen dürfen die fachliche
Trennkonfiguration nicht unbemerkt verändern.

### Bekannte Grenze des großen ONNX-Kim auf Android

Der ca. 747-MB-FP16-ONNX-Export bleibt bewusst erhalten, weil er auf stärkerer
Hardware beziehungsweise später auf Desktop sinnvoll sein kann. Auf den beiden
vorhandenen Xiaomi-Android-Geräten ist er praktisch nicht nutzbar:

- Smartphone: `REASON_LOW_MEMORY` während der ersten Inferenz bestätigt.
- Xiaomi Pad 8 Pro: Android-Bugreport zeigt einen RSS-Anstieg bis ungefähr
  **5,9 GB** innerhalb der ersten Inferenz und anschließenden Kill durch `lmkd`.

Daraus folgt:

- **Native/GGUF ist der primäre mobile Kim-Pfad.**
- der große ONNX-Kim bleibt als High-End-/Desktop-Variante erhalten,
- weitere Android-Wiederholungstests sind kein offener 1.0-Blocker,
- ein späterer Desktop-/Windows-Test gehört zu #40.

## Pipeline-Transparenz

Die Ergebnis-Kachel zeigt für neue Läufe die tatsächlich verwendete
Transkriptions-Pipeline und ihre Zeitanteile. Je nach Lauf werden ausgewiesen:

- Stimmisolierung einschließlich Modell,
- Audioaufbereitung,
- VAD / Segmentierung,
- Whisper einschließlich Modell,
- Gesamtzeit,
- Audio- und Verarbeitungsdauer,
- Echtzeitfaktor,
- Engpass.

Diese Messung dient der Gerätebewertung und verhindert, dass langsame
Vorverarbeitung pauschal Whisper zugeschrieben wird.

## Audioqualität vor Whisper

Nachgelagerte Text-KI darf keine Probleme der Audio-/Whisper-Pipeline verdecken.
Die offenen Bereiche sind deshalb getrennt:

- **#103:** allgemeine Audio-Vorverarbeitung wie Rauschminderung,
  Sprachhervorhebung und Normalisierung per A/B-Test bewerten.
- **#78:** Ursachen von Whisper-Wiederholungsschleifen/Halluzinationen verstehen.
- **#41/#115:** Stimmisolierung ist bereits implementiert und abgeschlossen.

Originalaudio wird bei Vorverarbeitung nicht überschrieben. #103 ist zunächst ein
Analyse- und Entscheidungspaket; eine neue sichtbare Funktion entsteht nur bei
messbarem Nutzen.

## Datenschutz

Die Zielarchitektur bleibt local-first:

- Audio/Video wird lokal dekodiert und transkribiert.
- Stimmisolierung und VAD laufen lokal.
- Whisper-Transkript bleibt lokal, sofern der Benutzer es nicht selbst teilt.
- Qwen verarbeitet den Transkripttext lokal über `llama.cpp`.
- KI-Ergebnis bleibt lokal, sofern der Benutzer es nicht selbst kopiert/teilt.
- Modelldownloads benötigen Netzwerkzugriff, sind aber vom Inhaltsdatenfluss zu
  unterscheiden.
- kein Benutzerkonto und keine Cloud-KI für die Kernfunktion.

Die endgültigen rechtlichen Texte und Play-Data-Safety-Angaben werden in #51 und
#35 mit dem tatsächlichen Releasecode abgeglichen.

## Lizenz- und Release-Gates

#116 bleibt als verbindliches Release-Gate offen: Die konkrete Lizenz der in
Transcript verwendeten Spleeter-2-stem-Gewichte muss vor dem finalen Release
eindeutig belegt sein.

#35 übernimmt am Ende die zusammengeführte Repository-, Lizenz-, SBOM-, Store- und
Release-Prüfung.

## Verbindliche Roadmap

Abgeschlossen beziehungsweise bereits gemergt sind unter anderem:

- #101 – alte KI-Korrektur entfernt
- #102 – lokale KI-Auswertung integriert
- #61 – erste KI-Mess-/Optimierungsphase
- #41 – Stimmisolierung integriert
- #115 – Native/GGUF-Kim ergänzt und Android-Befund abgeschlossen

#118 synchronisiert nach #115 ausschließlich Dokumentation und Projektsteuerung.
Danach lautet die lineare Folge:

**#103 → #78 → #113 → #39 → #51 → #33 → #34 → #27 → #35**

Parallel beziehungsweise als Gate:

- #44 – Produktname, Positionierung und Store-Auftritt
- #116 – Spleeter-Gewichtelizenz

## Windows-/Microsoft-Ausbau nach Android 1.0

#40 ist ausdrücklich **kein Bestandteil der Android-1.0-Roadmap**. Nach Abschluss
von #35 beginnt eine getrennte Desktop-/Windows-Phase. Dort werden unter anderem
Wiederverwendung der lokalen C/C++-Runtimes, Desktop-Paketierung und ein
High-End-Test des großen ONNX-Kim auf leistungsfähigerer Hardware untersucht.

## Historischer Sicherungspunkt

Vor der zwischenzeitlich erwogenen vollständigen KI-Entfernung wurde der Branch
`archive/local-ai-postprocessing-2026-08-29` auf dem damaligen `main`-Stand
`ee00badead1a2d70a15724573179425d3e651e30` angelegt.

Der Branch bleibt als historischer Wiederherstellungspunkt bestehen. Er ist nicht
die Entwicklungsbasis; die aktive Produktentwicklung läuft auf `main` und den
zugehörigen Issue-/Feature-Branches.

## Dokumentationsregel

`README.md`, `docs/PRODUCT_DIRECTION.md`, `docs/ARCHITECTURE.md` und Roadmap #26
müssen den **tatsächlich implementierten** Stand beschreiben. Abgeschlossene
Arbeitspakete dürfen dort nicht weiter als zukünftige Funktionen erscheinen.
