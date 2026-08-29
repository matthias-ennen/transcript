# Produktrichtung Transcript 1.0

Stand: **29.08.2026**

Verbindliche GitHub-Issues: #26, #101, #102, #61, #78, #103, #41

## Entscheidung

Transcript 1.0 behält den bereits entwickelten lokalen Qwen-/LLM-Unterbau, setzt
ihn aber nicht mehr als zweite Korrekturschicht hinter Whisper ein.

Die fachlichen Rollen sind:

- **Whisper transkribiert.**
- **Der Benutzer korrigiert bei Bedarf manuell.**
- **Qwen wertet das fertige akzeptierte Transkript aus.**

Diese Trennung ist die Grundlage der weiteren Version-1.0-Entwicklung.

## Warum die KI-Korrektur nicht weitergeführt wird

Whisper arbeitet direkt auf der Audiospur. Ein nachgelagertes Textmodell kennt das
Audio dagegen nicht und kann bei einer „Korrektur“ nur aus dem erkannten Text und
Kontext schließen, was vermutlich gemeint war. Für Transcript ist die bereits
vorhandene Kombination aus guter Whisper-Transkription, Audiowiedergabe,
Fragment-Wiederholung und manueller Korrektur die verlässlichere Grundlage für
den eigentlichen Transkripttext.

Die technische Arbeit am lokalen LLM war damit nicht umsonst. Modelldownload,
GGUF-/`llama.cpp`-Runtime, Sessions, Diagnose, Leistungsprofile, Speicher- und
Thermalschutz sowie CPU-/KleidiAI-/Vulkanpfade sind für die neue Rolle weiterhin
wertvoll.

## Zielworkflow

```text
Audio/Video auswählen oder aufnehmen
→ optional Audio-Vorverarbeitung
→ im Sprachmodus optional Silero VAD
→ Whisper
→ Whisper-Original
→ anhören / Fragmente wiederholen
→ bei Bedarf manuell korrigieren
→ akzeptierte Transkriptfassung
→ exportieren / teilen
→ optional lokale KI-Auswertung
→ separates KI-Ergebnis
→ kopieren / teilen
```

Der Songmodus aus #41 ergänzt vor Whisper eine eigene lokale
Gesangstrennung. Allgemeine Audio-Vorverarbeitung wird zunächst in #103 geprüft
und nur bei messbarem Nutzen als Produktfunktion weiterverfolgt.

## Transkriptzustand

Für neu entstehende Produktzustände gilt:

- unveränderliches Whisper-Original,
- manuell bearbeitete beziehungsweise akzeptierte Fassung,
- Herkunft `ORIGINAL` / `MANUAL`.

Historische Entwicklungsstände können bereits `AI` als Transkript-Herkunft
enthalten. #101 muss solche Zustände lesesicher behandeln, wenn das für
Update-/Persistenzkompatibilität erforderlich ist. Die neue KI-Auswertung erzeugt
keine Transkript-Herkunft `AI`.

## KI-Auswertung für Version 1.0

#102 definiert bewusst nur vier feste Standardaktionen:

1. **Zusammenfassen** – kompakte inhaltlich treue Zusammenfassung.
2. **Kernaussagen / Stichpunkte** – wichtigste Aussagen strukturiert verdichten.
3. **Aufgaben & To-dos** – nur tatsächlich erkennbare Aufgaben und nächste Schritte.
4. **Entscheidungen / Besprechungsprotokoll** – Entscheidungen, Ergebnisse und
   gegebenenfalls nächste Schritte strukturiert darstellen.

Die Auswahl bleibt absichtlich klein. Ein allgemeiner freier KI-Chat ist keine
Kernfunktion von Version 1.0.

## Regeln für KI-Ergebnisse

- KI-Auswertung startet niemals automatisch nach Whisper.
- Benutzer löst jede Auswertung bewusst aus.
- Quelle ist die **aktuell akzeptierte Transkriptfassung**, einschließlich
  manueller Korrekturen.
- KI-Ausgabe wird getrennt vom Transkript dargestellt.
- KI-Ausgabe darf Transkripttext, Zeitstempel, Segmentreihenfolge und Herkunft
  niemals verändern.
- Ergebnis soll mindestens kopierbar und teilbar sein; Abbruch und erneute
  Erzeugung gehören zum Produktumfang.
- Wenn keine Aufgaben oder Entscheidungen erkennbar sind, soll das Modell dies
  mitteilen statt Inhalte zu erfinden.
- Ergebnis soll standardmäßig in der Sprache des Transkripts erzeugt werden.
- GUI-Sprache und Sprache des Benutzerinhalts bleiben getrennt.
- Ein fehlendes Qwen-Modell darf die normale Whisper-Nutzung nicht blockieren.

## Lange Transkripte

Ein fertiges Transkript darf nicht still am Kontextlimit des lokalen Modells
abgeschnitten werden.

Für längere Texte ist deshalb ein mehrstufiger Ablauf vorgesehen:

1. vollständige Quelle in sinnvolle Textabschnitte teilen,
2. Abschnitte mit derselben fachlichen Aufgabe auswerten,
3. Teilresultate sammeln,
4. daraus ein Gesamtergebnis erzeugen,
5. prüfen, dass kein Quellenbereich unbemerkt ausgelassen wurde.

Die konkrete Strategie gehört zu #102. Laufzeit und Qualität dieses Verfahrens
werden in #61 mitgemessen.

## Qwen-Modellstrategie

Die sechs bereits integrierten Qwen3.5-Varianten bleiben zunächst als echte
Vergleichsmatrix erhalten. #61 bewertet sie künftig mit realen
Transkript-Auswertungsaufgaben statt mit Transkriptkorrektur.

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

Erst nach den Gerätevergleichen wird entschieden, ob alle sechs Varianten in
Version 1.0 sichtbar bleiben oder der Katalog kontrolliert reduziert wird.

## Audioqualität vor Whisper

Nachgelagerte Text-KI darf keine Probleme der Audio-/Whisper-Pipeline verdecken.
Deshalb werden zwei weitere Bereiche getrennt behandelt:

- **#78:** Ursachen von Whisper-Wiederholungsschleifen/Halluzinationen verstehen.
- **#103:** allgemeine Audio-Vorverarbeitung wie Rauschminderung,
  Sprachhervorhebung und Normalisierung per A/B-Test bewerten.
- **#41:** speziell für Songs Gesangstrennung/Source Separation vor Whisper.

Originalaudio wird bei Vorverarbeitung nicht überschrieben; Zwischenprodukte
bleiben temporär und lokal.

## Datenschutz

Die Zielarchitektur bleibt local-first:

- Audio/Video wird lokal dekodiert und transkribiert.
- Whisper-Transkript bleibt lokal, sofern der Benutzer es nicht selbst teilt.
- Qwen verarbeitet den Transkripttext lokal über `llama.cpp`.
- KI-Ergebnis bleibt lokal, sofern der Benutzer es nicht selbst kopiert/teilt.
- Modelldownloads benötigen Netzwerkzugriff, sind aber vom Inhaltsdatenfluss zu
  unterscheiden.
- kein Benutzerkonto und keine Cloud-KI für die Kernfunktion.

Die endgültigen rechtlichen Texte und Play-Data-Safety-Angaben werden in #51 und
#35 mit dem tatsächlichen Releasecode abgeglichen.

## Verbindliche Roadmap

Gemäß #26 ist die aktuelle Reihenfolge:

**#101 → #102 → #61 → #78 → #103 → #41 → #39 → #51 → #33 → #34 → #27 → #35**

#44 läuft als Produktakte parallel. #40 ist Zukunftsausbau nach Android 1.0.

### Nächster Schritt

#101 ist das nächste Arbeitspaket. Es entfernt die alte KI-Korrektursemantik aus
dem produktiven Workflow, **ohne** den LLM-Unterbau zu löschen. Danach baut #102
die neue Auswertungsoberfläche und Datenlogik auf.

## Historischer Sicherungspunkt

Vor der zwischenzeitlich erwogenen vollständigen KI-Entfernung wurde der Branch
`archive/local-ai-postprocessing-2026-08-29` auf dem damaligen `main`-Stand
`ee00badead1a2d70a15724573179425d3e651e30` angelegt.

Der Branch bleibt als unveränderter historischer Wiederherstellungspunkt bestehen.
Er ist nicht die neue Entwicklungsbasis; die aktive Produktentwicklung läuft
weiter auf `main` und den zugehörigen Issue-/Feature-Branches.

## Dokumentationsregel während des Übergangs

`docs/ARCHITECTURE.md` beschreibt die **tatsächlich implementierte** Architektur.
Da der aktuelle Code bis zur Umsetzung von #101 noch die alte KI-Korrekturstrecke
enthält, wird die Architekturdatei nicht vorzeitig so umgeschrieben, als sei #101
bereits implementiert.

Mit der tatsächlichen Codeumstellung in #101 muss `docs/ARCHITECTURE.md` dann
synchron auf die neue Ist-Architektur aktualisiert werden.
