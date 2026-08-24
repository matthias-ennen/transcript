# Architektur von Transcript

## Zielbild

Transcript ist eine lokale Android-App. Audio- und Videodateien werden auf dem
Gerät dekodiert und mit `whisper.cpp` transkribiert. Optional korrigiert ein
lokales Qwen3.5-Modell das Ergebnis über `llama.cpp`. Nur Modelldownloads
benötigen eine Internetverbindung.

Die Architektur trennt bewusst vier Ebenen:

1. Audioaufnahme und Medienvorbereitung,
2. Whisper-Transkription und Chunk-Stitching,
3. sichtbare/editierbare Transcript-Timeline,
4. optionale lokale KI-Nachbearbeitung.

## Module

- `app`: Oberfläche, Medienauswahl, Aufnahme, Wiedergabe, Modelldownload,
  Statussteuerung, Timeline, Bearbeitung, Export und Android-Services
- `lib`: Kotlin-/JNI-Brücke zu `whisper.cpp` und nativer CMake-Build
- `llm`: Kotlin-/JNI-Brücke zu `llama.cpp` für lokale GGUF-Inferenz
- `third_party/whisper.cpp`: gepinnte Whisper-Inferenzbibliothek
- `third_party/llama.cpp`: gepinnte lokale LLM-Inferenzbibliothek

## Whisper-Datenfluss

1. `MainScreenViewModel` hält den zentralen UI-Zustand.
2. `RecordingService` besitzt eine laufende Mikrofonaufnahme unabhängig von der
   Activity und veröffentlicht Laufzeit, Pegel, Abschluss und Fehler.
3. Medien werden zu 16-kHz-Mono-PCM vorbereitet. Lange Aufnahmen werden nicht als
   vollständiger PCM-Strom im Arbeitsspeicher gehalten.
4. `TranscriptionService` läuft in einem privaten Android-Prozess
   `:transcription` und plant Hauptabschnitte von einer bis fünf Minuten.
5. Jeder Hauptabschnitt erhält an seinen Grenzen zwei Sekunden zusätzlichen
   Audiokontext. Dieser Overlap verhindert harte Schnitte in Wörtern und Sätzen.
6. Decoder und Whisper-Modell werden zweiphasig verwendet: Zuerst werden die
   benötigten PCM-Abschnitte vorbereitet und freigegeben, danach wird der
   Whisper-Kontext einmal geladen und über die vorbereiteten Abschnitte
   wiederverwendet.
7. Bei automatischer Spracherkennung wird eine brauchbar erkannte Sprache für die
   folgenden Abschnitte festgehalten.
8. Nach jedem Abschnitt werden Segmentergebnis, erkannte Sprache und nächste
   Position atomar als Wiederaufnahmepunkt gesichert.

## Chunk-Grenzen und Stitching

Die zwei Sekunden Kontextüberlappung sind Teil der gewünschten Architektur und
werden nicht entfernt. Entscheidend ist die nachgelagerte Bereinigung.

`TranscriptionChunking` verschiebt die lokalen Whisper-Zeitstempel auf die
absolute Position in der vollständigen Aufnahme. Zeitstempel werden dabei auf das
tatsächlich dekodierte Fenster begrenzt, damit Whisper keine sichtbaren Segmente
außerhalb des verfügbaren Audios erzeugen kann.

Für jedes Segment entscheidet zunächst die zeitliche Mitte, welchem Hauptabschnitt
es gehört. Danach führt `mergeCommittedSegments()` die bereits übernommenen
Segmente mit dem Ergebnis des nächsten Chunks zusammen.

Da Whisper denselben Audiobereich im Overlap je nach Chunk-Kontext unterschiedlich
segmentieren kann, gilt beim Stitching:

- nahezu vollständig ineinander liegende grenzüberschreitende Alternativen werden
  als zwei Darstellungen desselben Audiobereichs behandelt und bereinigt,
- die bereits stabile Darstellung wird bevorzugt, wenn beide Varianten denselben
  Bereich weitgehend repräsentieren,
- verbleibende echte Teilüberlappungen werden an einer gemeinsamen zeitlichen
  Grenze getrennt,
- identischer Text an **nicht überlappenden** Zeitstellen bleibt erhalten und wird
  nicht als Duplikat entfernt.

Die Logik arbeitet ausschließlich mit den tatsächlichen `mainStartMs`,
`mainEndMs`, `decodeStartMs` und `decodeEndMs`. Sie ist deshalb unabhängig davon,
ob der Benutzer beispielsweise 1-, 2-, 3-, 4- oder 5-Minuten-Abschnitte gewählt
hat.

## Persistenz

`TranscriptionCheckpointStore` hält den Zwischenstand eines aktiven oder
unterbrochenen Whisper-Laufs.

`TranscriptResultStore` hält nach Abschluss zwei getrennte Ebenen:

- das unveränderte Whisper-Original,
- den zuletzt übernommenen Anzeige-/Exportstand.

Schreibvorgänge werden atomar ausgeführt. Eine neue Datei, Aufnahme oder bewusst
neu gestartete Transkription ersetzt den bisherigen Ergebnisstand.

## Transcript-Timeline

Whisper liefert fachlich nur erkannte Segmente mit Startzeit, Endzeit und Text.
Die in der Oberfläche sichtbare Timeline ist eine eigene App-Ebene.

`TranscriptTimeline` ergänzt das Whisper-Ergebnis einmalig zu einer lückenlosen
Zeitleiste vom Dateianfang bis Dateiende. Größere Lücken werden als leere,
abspielbare und editierbare Pausensegmente eingefügt. Das betrifft auch eine
mögliche Pause am Anfang oder Ende der Audiodatei.

Kurze technische Zwischenräume können für die Anzeige an Nachbarsegmente angelegt
werden; die separaten Whisper-Rohzeitstempel bleiben unverändert erhalten.

### Fragmentnummern

Die sichtbare Nummer einer Timeline-Karte ist **keine Whisper-ID**. Whisper liefert
in der von der App verwendeten Segmentstruktur keine stabile fortlaufende ID.
Frühere App-Versionen leiteten eine Anzeigezahl aus der Position des passenden
Whisper-Rohsegments ab. Dadurch blieben künstliche Pausensegmente unnummeriert
und mehrere sichtbare überlappende Karten konnten dieselbe Zahl erhalten.

Die aktuelle Architektur trennt deshalb Herkunft und Anzeige konsequent:

- jede sichtbare Timeline-Karte erhält nach ihrer Position eine fortlaufende
  Fragmentnummer von `1` bis `N`,
- virtuelle Pausen werden genauso nummeriert wie Whisper-basierte Karten,
- die Nummer ist ausschließlich eine benutzerorientierte Fragmentnummer,
- die interne Herkunft bleibt separat erhalten und wird **nicht** aus der
  sichtbaren Nummer abgeleitet.

Damit kann beispielsweise ein ursprüngliches Whisper-Rohsegment an Position 34
in der fertigen Timeline als Fragment 40 erscheinen, wenn davor sechs virtuelle
Pausen eingefügt wurden. Das ist beabsichtigt.

## Bearbeitung und Herkunft

Zeitstempel sind im Korrekturmodus schreibgeschützt. Änderungen werden zunächst
in einem Entwurfszustand gehalten und erst nach bewusster Übernahme zum gültigen
Anzeige-/Exportstand.

Die App unterscheidet intern weiterhin mindestens folgende Herkunftssituationen:

- unverändertes Whisper-Original,
- manuell bearbeitet,
- durch KI bearbeitet,
- virtuelle Pause ohne Whisper-Text.

Eine virtuelle Pause bleibt intern auch dann als ursprünglich künstlich erzeugter
Timeline-Bereich erkennbar, wenn sie eine normale sichtbare Fragmentnummer besitzt.
Diese technische Unterscheidung darf deshalb nicht von `null` oder einer
Anzeigenummer abhängen.

Leere virtuelle Pausen werden im JSON mit `origin: "virtual_pause"` erhalten,
aber aus TXT und SRT herausgefiltert. Wird dort manuell Text eingetragen, wird der
Bereich als manueller Inhalt behandelt und erscheint regulär in den Textformaten.

## Lokale Qwen3.5-Nachbearbeitung

`AiPostProcessingService` darf erst starten, nachdem der Whisper-Kontext vollständig
freigegeben wurde. Dadurch konkurrieren Whisper und Qwen nicht gleichzeitig um
denselben großen Arbeitsspeicherbereich.

Das gewählte Qwen3.5-GGUF wird lokal über `llama.cpp` geladen. Zeitstempel,
Fragmentreihenfolge und sichtbare IDs bleiben Eigentum der App; das Modell darf
nur Textkorrekturen vorschlagen.

Die globale Einstellung **KI-Nachbearbeitungsstrategie** bietet zwei Pfade.
Beide verwenden dieselbe inhaltliche Kernanweisung: erkennbare
Transkriptionsfehler sowie Rechtschreibung, Grammatik und Zeichensetzung anhand
des Gesprächskontexts korrigieren, Bedeutung und Sprechstil bewahren, nichts
hinzuerfinden und keine vorhandenen Informationen weglassen.

### Strategie: Segmentweise

Die vollständige Zeitgruppe wird einmal als gemeinsamer Gesprächskontext in die
native Modellsitzung aufgenommen. Danach folgen die Zielsegmente nacheinander als
kleine Aufgaben.

Wichtig: Qwen3.5 besitzt eine hybride/recurrente Architektur. Der frühere Ansatz,
den nativen Speicher nach jedem Segment auf einen gemeinsamen KV-Präfixzustand
zurückzusetzen, ist deshalb verworfen. `llama_memory_seq_rm` kann diesen Zustand
für Qwen3.5 nicht zuverlässig partiell zurückspulen.

Der aktuelle Pfad arbeitet **append-only**:

1. gemeinsamen Gruppenkontext einmal laden,
2. Zielsegment 1 anhängen und Antwort erzeugen,
3. Zielsegment 2 an denselben fortlaufenden Sitzungszustand anhängen,
4. weitere Zielsegmente entsprechend fortsetzen,
5. keinen früheren nativen Modellzustand wiederherstellen.

Die App ordnet jede Antwort dem gerade bearbeiteten Zielsegment zu und prüft das
strukturierte Ergebnis. Die native Ausgabe wird auf das erwartete Ergebnisformat
begrenzt.

### Strategie: Abschnittsweise

Die vollständige Zeitgruppe wird in einer einzigen Korrekturaufgabe verarbeitet.
Das Modell soll nur die geänderten Fragment-IDs mit dem jeweils korrigierten Text
zurückgeben.

Die App wertet die strukturierte Antwort selbst aus:

- unbekannte IDs werden verworfen,
- doppelte IDs werden nicht blind übernommen,
- leere Ergebnisse werden verworfen,
- nicht genannte Fragmente bleiben unverändert,
- nur tatsächlich vom Ausgangstext abweichende gültige Ergebnisse werden als
  Änderungen gezählt.

Damit existieren zwei funktionsfähige Pfade, die unabhängig voneinander auf
identischem Transkriptmaterial getestet werden können.

## KI-Machbarkeitsstand

Die Machbarkeit ist nachgewiesen: Segmentweise und abschnittsweise
Qwen3.5-Nachbearbeitung funktionieren auf dem Android-Zielgerät end-to-end und
können reale Korrekturen erzeugen.

Die aktuell dominante Einschränkung ist die Laufzeit. Die bisherige
Standard-CPU-Ausführung erreicht nur einen kleinen Bruchteil der Tokenraten, die
auf vergleichbarer mobiler Hardware grundsätzlich möglich erscheinen. Deshalb
ist die Performanceoptimierung ein eigenes Arbeitspaket und bewusst vor die
vollständige Produktivierung der KI-Nachbearbeitung gelegt.

## KI-Diagnose

Die dauerhafte Seite `AiDiagnosticsScreen` stellt zwei getrennte Zwecke bereit:

- freien lokalen Qwen-Testbereich,
- vollständige technische Diagnose der App-/Modellsitzung.

Die Diagnose erfasst unter anderem:

- gewähltes Modell und Backend,
- Kontextgröße, Batch und Micro-Batch,
- Eingabe- und Ausgabetoken,
- Modellladezeit,
- Promptverarbeitungszeit,
- Zeit bis zum ersten Antworttoken,
- Generierungszeit und Gesamtdauer,
- Parser-/Validierungsentscheidungen,
- native Fehler und Backend-Rückfälle,
- aktuell verwendete KI-Nachbearbeitungsstrategie.

Die ausführliche Diagnose ist bewusst dauerhaft Teil der App, damit spätere
Performance- und Qualitätsprobleme auf dem echten Gerät nachvollzogen werden
können.

## KI-Leistung und Hardware

Die Unterseite **KI-Leistung und Hardware** hält für jedes Qwen-Modell ein eigenes
Leistungsprofil. Sie ist die technische Grundlage für das nächste
Optimierungsarbeitspaket.

Untersucht werden können unter anderem:

- Kontextgröße,
- `n_batch` und `n_ubatch`,
- getrennte Prompt- und Ausgabethreads,
- CPU-Affinität und Priorität,
- Standard-CPU-Kernel,
- KleidiAI-kompatible Pfade,
- Vulkan/GPU-Offload und gemischte Pfade,
- Flash-Attention beziehungsweise verfügbare Backendoptionen,
- thermischer Zustand und Speicherreserve.

Entscheidend ist nicht, ob eine Option in der GUI gewählt wurde, sondern welcher
Backendpfad von der nativen Laufzeit **tatsächlich** benutzt wurde. Benchmarks und
Diagnose müssen diesen realen Pfad ausweisen.

KleidiAI ist quantisierungsabhängig. Q4_0/Q8_0 können von passenden
Weight-Packing-/Kernelpfaden profitieren; Q4_K_M ist nicht automatisch über
denselben optimierten Pfad beschleunigt. Vulkan ist ebenfalls nicht pauschal
schneller als CPU und muss deshalb real gemessen werden.

## Hardware- und Speicherschutz

Vor lokaler KI-Ausführung prüfen RAM-Reserve, maximale Speichernutzung und
thermische Grenzwerte den Start. Bei hoher Wärme kann die effektive Konfiguration
reduziert werden; an der Abbruchgrenze wird die Berechnung kontrolliert beendet.

Ein Vulkan-/GPU-Laufzeitfehler wird als echter Fehler protokolliert. Ist ein
CPU-Rückfall aktiviert, darf der Auftrag anschließend einmal vollständig über CPU
wiederholt werden.

## Status und Nebenprozesse

Modelldownload, Mikrofonaufnahme und Transkription besitzen getrennte
Foreground-Services beziehungsweise Koordinatoren. Die Transkription läuft in
einem privaten Nebenprozess, damit ein nativer Workerfehler nicht zwangsläufig die
sichtbare Activity beendet.

Große Audio- oder Modellobjekte werden nicht über Binder/Intent transportiert.
Fortschritt, Status und Wiederaufnahmepunkte werden über kleine persistierte oder
prozessübergreifend geeignete Zustände vermittelt.

Während rechenintensiver Whisper- oder Qwen-Inferenz bleibt die sichtbare
CannaBot-Animation bewusst ruhig, damit UI-HWUI und Compute-Backend nicht unnötig
um dieselbe GPU konkurrieren.

## Export

`TranscriptExport` verwendet den übernommenen Timeline-Zustand.

- JSON enthält die vollständige Timeline einschließlich Herkunft.
- Leere virtuelle Pausen bleiben im JSON erhalten.
- TXT und SRT lassen leere Pausen weg.
- manuell befüllte Pausen werden als normaler Textinhalt exportiert.

`TranscriptShare` legt ausgewählte Exportdateien in einem privaten Cachebereich ab
und gibt sie über `FileProvider` mit zeitlich begrenztem Leserecht an das
Android-Teilen-Menü weiter.

## Modelle und Speicherung

Der zentrale Whisper-Modellkatalog enthält fünf mehrsprachige Qualitätsstufen von
Tiny bis Large V3. Modelle werden einzeln heruntergeladen, per SHA-256 geprüft und
im privaten App-Speicher gehalten.

Der getrennte `AiModel`-Katalog enthält Qwen3.5 mit 0,8B, 2B und 4B Parametern.
Auswahl, Download, Prüfung und Löschen liegen in den Einstellungen.

Cloud-Backup und Android-Gerätetransfer sind für App-Daten deaktiviert. Modelle,
Aufnahmen, Zwischenstände, fertige Transkripte und Einstellungen sollen den
privaten App-Speicher nicht über das Android-Backup verlassen.

## Aktuelle Entwicklungsreihenfolge

Nach Abschluss der KI-Machbarkeit und der Reparatur der Whisper-Chunk-Grenzen ist
die aktuelle technische Reihenfolge:

1. **KI-Antwortzeiten analysieren und deutlich verkürzen**
2. **KI-Nachbearbeitung produktionsreif fertigstellen**
3. **Whisper-Wiederholungs-/Halluzinationsschleifen bei langen Dateien härten**
4. anschließend weitere Roadmap-Arbeitspakete

Die Performancearbeit soll zuerst klären, welches reale Potenzial CPU,
KleidiAI, Vulkan und gemischte Backends auf dem Zielgerät besitzen. Erst danach
wird der endgültige Umfang der produktiven automatischen KI-Nachbearbeitung
festgelegt.
