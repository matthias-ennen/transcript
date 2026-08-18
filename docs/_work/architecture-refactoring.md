# Architekturgrenzen – Issue #28

Dieses Dokument beschreibt die technische Aufteilung nach dem Refactoring. Es ist
kein neues Produktkonzept: Navigation, sichtbare Texte, gespeicherte Einstellungen,
Exporte und die Whisper-/VAD-/KI-Ergebnisse bleiben unverändert.

## Verantwortlichkeiten

| Bereich | Zuständig | Nicht zuständig |
|---|---|---|
| Compose-Hülle | `MainScreen`, `MainScreenChrome`, `MainScreenSelectors` | Fachlogik und Dateizugriffe |
| UI-Orchestrierung | `MainScreenViewModel` | Lokale Modell-Dateinamen, Transkript-Editregeln |
| Medien | `media/*`, `SharedMediaImport`, Aufnahmeordner-Präferenzen | Whisper- oder KI-Entscheidungen |
| Transkription | `transcription/*`, `TranscriptionService`, `TranscriptionCoordinator` | Compose-Navigation |
| Modelle | `ModelInventory`, Download-Services und -Koordinatoren | sichtbare Seitennavigation |
| KI | `ai/*`, `AiPostProcessingService`, `AiEngineSessionManager` | Audioaufnahme und Wellenform |
| Transkript-Sitzung | `TranscriptSession`, `TranscriptResultStore`, `TranscriptShare` | Modell-Downloads |

## Zustandsfluss

`TranscriptUiState` bleibt bewusst der unveränderte, gemeinsame Rendervertrag für
Hauptseite und Unterseiten. Dienste veröffentlichen ihre fachlichen Zustände über
ihre Koordinatoren. Das ViewModel übersetzt diese in den Rendervertrag und löst
sichtbare Aktionen aus. Dadurch erhält die Oberfläche keine direkte Abhängigkeit
zu Android-Diensten, Modell-Dateien oder nativen Bibliotheken.

## Service- und native Grenze

`TranscriptionService`, `AiPostProcessingService` und `RecordingService` bleiben
eigenständige Android-Dienste. Ihre Start-/Abbruch-Schnittstellen und die
Koordinatoren sind die einzige Brücke zum ViewModel. Die native Whisper- und
lokale-KI-Ausführung wird nicht verändert; Issue #28 verschiebt ausschließlich
Verantwortlichkeiten im Kotlin-Unterbau.

## Prüfabsicherung

- Reine Zustands- und Dateilogik erhält JVM-Tests (`ModelInventoryTest`,
  `TranscriptSessionTest`).
- Bestehende Tests für Aufnahme, Download, Zeitabschätzung, Transkriptbearbeitung,
  Persistenz und Statusmeldungen bleiben Teil der CI.
- Nach der vollständigen Änderung werden Debug-/Release-Tests, Lint, signierter
  APK-Bau und die vorhandenen Signatur-/Paketprüfungen ausgeführt.
- Der Geräte-Smoke-Test prüft nur die vom Refactoring berührten Wege; das komplette
  Referenz-Qualitätsgate bleibt wie geplant Issue #27 vor Version 1.0.
