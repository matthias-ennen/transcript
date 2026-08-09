# Arbeitsplan: lokale KI-Nachbearbeitung

## Ziel

Transcript ergänzt die bestehende lokale Whisper-Transkription um eine optionale,
ebenfalls vollständig lokale Textkorrektur. Die Hauptseite erhält keine dauerhafte
KI-Modellauswahl. Auswahl, Download, Aktivierung und Löschen liegen ausschließlich
in den Einstellungen. Unter jeder geöffneten Transkriptgruppe stehen links
**KI-Nachbearbeitung** und rechts **Bearbeiten** im selben Schaltflächenstil.

## Festgelegter Umfang

1. Drei Qwen3.5-GGUF-Stufen mit SHA-256-Prüfung:
   - Schnell: 0,8B Q4_0, ungefähr 563 MB
   - Ausgewogen: 2B Q4_K_M, ungefähr 1,28 GB
   - Sehr genau: 4B Q4_K_M, ungefähr 2,74 GB
2. Einstellungsbereich **KI-Nachbearbeitung** im vorhandenen Kartenstil:
   - KI-Nachbearbeitung aktivieren
   - Nach der Transkription automatisch ausführen
   - Modellauswahl, Downloadstatus, Installation und Löschen
3. Automatische Korrektur nach erfolgreicher Transkription, wenn beide Schalter
   aktiv und das gewählte Modell vollständig installiert sind.
4. **KI-Nachbearbeitung** öffnet die Fünf-Minuten-Gruppe sofort als Entwurf und
   korrigiert nur diese Gruppe. Das Ergebnis kann anschließend weiter manuell
   geändert und erst über **Änderungen übernehmen** gespeichert werden.
5. Zeitstempel, Reihenfolge und Segmentanzahl bleiben unverändert. Der feste,
   versionierte Arbeitsrahmen `transcript-correction-v2` beschreibt den gesamten
   Fünf-Minuten-Bereich einmal als schreibgeschützten Whisper-Rohkontext. Danach
   wird jedes Segment einzeln gegen genau diesen gemeinsamen Ausgangskontext
   geprüft; vorherige KI-Antworten fließen nicht in die nächste Prüfung ein.
6. Die native `llama.cpp`-Grammatik begrenzt jede Korrekturantwort technisch auf
   genau `{"result":"..."}`. Die App ordnet die Antwort selbst dem aktuell
   geprüften Segment zu. In dieser ersten Erprobungsstufe greift bewusst nur eine
   inhaltliche Sicherheitsregel: Ist `result` leer, bleibt das Whisper-Original
   erhalten. Längen-, Ähnlichkeits- und Fremdkontextprüfungen folgen erst nach den
   Praxistests.
7. Während der Korrektur zeigt die bestehende Statuszeile pulsierende Meldungen,
   CannaBot verwendet die Review-/Tablet-Animation und Diagnosemeldungen erklären
   Modellladung, Gruppe und Validierung. Nach Erfolg folgt die vorhandene Sequenz
   aus Sprung und Winken.

## Technische Trennung

- `llm`: schlanke JNI-Brücke zur offiziellen `llama.cpp`-Laufzeit
- `third_party/llama.cpp`: fest gepinnter Git-Submodulstand
- `ai`: Modellkatalog, Download, gespeicherte Einstellungen, Prompt/Parser und
  Hintergrunddienst
- `ui/main`: Zustandsabbildung und bestehende Compose-Oberfläche

Whisper und das Korrekturmodell werden nicht gleichzeitig geladen. Die automatische
KI-Stufe beginnt erst nach der vollständigen Freigabe des Whisper-Kontexts.

## Freier KI-Testbereich

- Die feste Mondfrage wird durch ein mehrzeiliges Eingabefeld ersetzt.
- Der unveränderte Feldinhalt geht an das aktuell ausgewählte lokale KI-Modell.
- Die vollständige Modellantwort lässt sich weiterhin ein- und ausblenden.
- Eingabe und Antwort bleiben beim Modellwechsel sichtbar, damit die drei Modelle
  mit demselben Auftrag verglichen werden können.

## Prüfpunkte

- Strukturierte Korrekturantworten werden zuverlässig ausgelesen.
- Leere Ergebnisse behalten das Original; alle anderen Inhalte werden in dieser
  Etappe ohne weitere Inhaltsprüfung als Vorschlag übernommen.
- Der gemeinsame Gruppen-Kontext wird nur einmal dekodiert und vor jeder
  Segmentaufgabe auf denselben Ausgangszustand zurückgesetzt.
- Schalter und gewähltes Modell bleiben nach App-Neustart erhalten.
- Unvollständige Downloads sind fortsetzbar und löschbar.
- Manuelle KI-Korrektur verändert nur die aktive Gruppe und bleibt ein Entwurf.
- Automatische KI-Korrektur aktualisiert das gesamte Transkript gruppenweise.
- Status, Diagnose und CannaBot-Zustände wechseln bei Start, Fortschritt, Erfolg
  und Fehler nachvollziehbar.
- Die Statuszeile unterscheidet immer zwischen geprüften Segmenten, erkannten
  Korrekturen, übernommenen Korrekturen und verworfenen Rückgaben.
- JVM-Tests, Kotlin-/Compose-Build, nativer whisper.cpp-/llama.cpp-Build,
  Signierung und APK-Upload sind grün.

## Qualitätsgrenze dieser Etappe

Die Integration macht die drei Modelle auf dem Zielgerät vergleichbar. Ob 0,8B,
2B oder 4B den gewünschten Qualitätsgewinn liefert, wird anschließend mit derselben
echten deutschen Transkriptpassage geprüft. Die App behauptet vor diesem Test keine
bestimmte Korrekturqualität.

## Backlog: präzise KI-Fortschrittsanzeige

Die Statuszeile und die Diagnosekachel dürfen **geprüfte Zielsegmente** nicht als
**überarbeitete Segmente** bezeichnen. Die App führt und zeigt getrennte Zähler:

- `geprüft`: alle Segmente, die das KI-Modell im aktuellen Lauf bewertet hat;
- `Korrekturen vorgeschlagen`: alle gültigen Änderungen, die das Modell
  zurückgeliefert hat;
- manuell: `Korrekturen im Entwurf`, bis der Benutzer sie über
  **Änderungen übernehmen** speichert;
- automatisch: `Korrekturen übernommen`, sobald die App sie gespeichert hat;
- `verworfen`: ungültige oder nicht sicher zuordenbare Einzeländerungen.

Beispiel für die Abschlusssmeldung: „KI-Prüfung abgeschlossen: 65 Segmente
geprüft, 4 Korrekturen übernommen.“ Sie darf nicht behaupten, alle 65 Segmente
seien überarbeitet worden. Zwischenstände und Diagnoseeinträge verwenden dieselben
Begriffe und enthalten weiterhin ihren Zeitstempel.

## Build-Verifikation

Der APK-Workflow wird für jeden veröffentlichten Stand des Agent-Branches erneut
ausgeführt. Die Prüfung umfasst JVM-Tests, Kotlin-/Compose-Kompilierung, native
Bibliotheken, Signatur und den Artefakt-Upload.

## Arbeitsstand 09.08.2026

Der v2-Umbau wird zunächst auf `agent/simple-transcript-finalization` umgesetzt und
vollständig geprüft. Danach wird der Gesamtstand kontrolliert nach `main`
übernommen. Der abschließende signierte APK-Workflow muss auf dem resultierenden
`main`-Commit laufen.
