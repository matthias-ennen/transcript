# Hintergrunddienste und Modelldownloads

## Verantwortung

`VerifiedModelDownloader` ist der gemeinsame Ablauf für Whisper-, VAD- und KI-Modelle. Er führt HTTP-Fortsetzung, Fortschrittswerte, Prüfsummenprüfung und die sichere Übernahme einer vollständigen `.part`-Datei zusammen. Die drei Android-Dienste behalten nur ihre fachlichen Modellmetadaten und die Anbindung an ihren jeweiligen UI-Zustand.

## Speicherprüfung

Vor einem benutzerinitiierten Download berechnet `DownloadStoragePolicy` den freien Bedarf aus den noch fehlenden Modellbytes, 8 MiB technischem Puffer und 128 MiB Sicherheitsreserve. Der Dienst prüft denselben Wert noch einmal, bevor er eine neue oder bestehende `.part`-Datei befüllt.

Bei unzureichendem Speicher startet kein Download. `DownloadStorageIssueCoordinator` übergibt die Information an die Oberfläche. Diese zeigt eine CannaBot-Sprechblase mit dem Hinweis, Modellname sowie benötigtem und verfügbarem Speicher. Die einzige Aktion ist **Okay**. Transcript löscht keine Daten und öffnet keine Android-Speicherverwaltung.

## Benachrichtigungen

`TranscriptNotifications` vergibt feste, eindeutige IDs für Whisper-Download, Transkription, Abschlussmeldung, Aufnahme, KI-Nachbearbeitung, VAD-Download und KI-Modell-Download. Alle Hintergrundvorgänge verwenden das monochrome Transcript-Symbol.

## Unverändert

Modelldateien verbleiben in ihren bestehenden fachlichen Ordnern (`models`, `vad-models`, `ai-models`). Downloads, Transkription, KI-Nachbearbeitung und Aufnahme bleiben lokale Foreground-Vorgänge; ihre bestehenden Service-Typen im Android-Manifest bleiben erhalten.

## Abnahme

Am 18.08.2026 wurden die Umsetzung und der erfolgreich durchgelaufene GitHub-
Actions-Build #400 manuell abgenommen. Die Änderungen wurden mit PR #84 in
`main` übernommen und Issue #29 geschlossen.
