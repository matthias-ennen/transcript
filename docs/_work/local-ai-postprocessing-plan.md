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
   versionierte Arbeitsrahmen `transcript-correction-v1` erlaubt nur konservative
   Textkorrekturen. Die KI liefert ausschließlich echte Änderungen als kleine
   `[[PATCH_0001]]<Tab>Text`-Zeilen zurück; die App behält das Original und
   übernimmt jede gültige Änderung einzeln. Ungültige Einzelzeilen werden
   verworfen, ohne andere gültige Änderungen zu verlieren.
6. Bis zu acht Textsegmente vor und nach dem aktiven Bereich werden als ausdrücklich
   schreibgeschützter Kontext mitgegeben. Die KI darf nur sichere Rechtschreibung,
   Zeichensetzung und eindeutig erkannte Wortfehler korrigieren. Bei Unsicherheit,
   Gesang, Dialekt, Fülllauten oder Gebrabbel bleibt der Originaltext unverändert.
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

## Prüfpunkte

- Markerparser übernimmt nur vollständige, exakt zuordenbare Antworten.
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
