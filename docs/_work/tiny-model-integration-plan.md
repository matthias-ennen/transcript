# Arbeitsplan: Whisper Tiny

Stand: 8. August 2026

Status: Implementiert; Katalog-, Quell- und Diff-Prüfung abgeschlossen. Der
vollständige Gradle-/CI-Build erfolgt zusammen mit dem nächsten APK-Lauf.

## Ziel

Das mehrsprachige Whisper-Tiny-Modell wird vor dem nächsten APK-Build als
fünfte Qualitätsstufe **Sehr schnell** vollständig in die bestehende lokale
Modellverwaltung aufgenommen. Medium bleibt bewusst außerhalb des Umfangs.

## Umsetzung

1. `ggml-tiny.bin` mit offizieller Dateigröße und SHA-256-Prüfsumme in den
   zentralen Modellkatalog aufnehmen.
2. Tiny vor Base in der Qualitätsauswahl und Modellverwaltung anzeigen.
3. Download, Fortsetzung, Prüfsummenprüfung, Auswahl, Speicherung, Löschen und
   Transkriptionsübergabe über den bestehenden gemeinsamen Modellpfad nutzen.
4. Einen vorläufigen Tiny-Faktor in die modellabhängige Laufzeitschätzung
   aufnehmen.
5. Katalogreihenfolge, mehrsprachige Datei, Prüfsumme und Schätzreihenfolge per
   JVM-Test absichern.
6. README und Architekturübersicht auf fünf Modelle aktualisieren.

## Prüfpunkte

- **Sehr schnell** steht als erste Qualitätsstufe zur Verfügung.
- Tiny lädt ausschließlich `ggml-tiny.bin`, nicht `tiny.en`.
- Ein vollständiger Download wird gegen die offizielle SHA-256-Prüfsumme
  geprüft.
- Tiny kann wie jedes andere Modell ausgewählt, verwendet und gelöscht werden.
- Die Tiny-Laufzeitschätzung liegt unter der Base-Schätzung.
- Bestehende gespeicherte Modellauswahlen und der Base-Fallback bleiben
  kompatibel.
- Der nächste APK-Build enthält Tiny gemeinsam mit allen zuvor vereinbarten
  UI-Änderungen.
