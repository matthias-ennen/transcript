# Arbeitsplan: Videoimport und stabile Modelldownloads

## Ziel

- Gängige Android-Videoformate in der Dateiauswahl zulassen und ausschließlich
  ihre Audiospur an den bestehenden Decoder übergeben.
- Whisper-Modelldownloads unabhängig vom geöffneten App-Fenster ausführen.
- Unvollständige Downloads behalten und mit HTTP-Range fortsetzen.
- Fortschritt über App-Oberfläche und Android-Benachrichtigung sichtbar machen.
- Modell erst nach Größen- und SHA-256-Prüfung aktivieren.

## Prüfpunkte

- Audioimport und Aufnahme bleiben unverändert nutzbar.
- App-Wechsel beendet den Download nicht.
- Ein unterbrochener Download beginnt nicht unnötig bei null.
- Ein Server ohne Range-Unterstützung löst einen kontrollierten Neustart aus.
- Android-Manifest, Compose, Service und Tests bauen gemeinsam.
