# Arbeitsplan: Aufnahme und Audiowiedergabe

Stand: 7. August 2026

Status: umgesetzt; die Datei bleibt als nachvollziehbarer Arbeitsplan erhalten.

## Ziel

Die vorhandene Dateiauswahl wird um eine vollständig lokale Aufnahme- und
Vorhörfunktion ergänzt. Der bestehende Paketname, die Transkriptionslogik und
die dauerhafte APK-Signierung bleiben unverändert.

## Module

1. `AudioRecorder`: Mikrofonaufnahme als AAC/M4A in `files/recordings`.
2. `AudioPlayerController`: Wiedergabe, Pause, Positionswechsel und Lebenszyklus.
3. `WaveformGenerator`: verdichtete Amplitudenwerte aus dem vorhandenen Decoder.
4. `AudioControls`: Compose-Oberfläche für Aufnahme, Play/Pause, Wellenform und Zeit.
5. `MainScreenViewModel`: verbindet die Module und hält den sichtbaren Zustand.

## Prüfpunkte

- Aufnahme lässt sich über denselben Button starten und beenden.
- Dateiname enthält Datum und Uhrzeit; die Aufnahme wird sofort ausgewählt.
- Play/Pause funktioniert für Aufnahme und importierte Audiodatei.
- Positionsmarke läuft mit und lässt sich per Finger verschieben.
- Wellenform wird für die ausgewählte Datei berechnet; bei Aufnahme ist ein Live-Pegel sichtbar.
- Transkription und vorhandene Exporte bleiben unverändert nutzbar.
- Lokaler Debug-Build ist erfolgreich.
- GitHub erzeugt eine höher versionierte, dauerhaft signierte Update-APK.
