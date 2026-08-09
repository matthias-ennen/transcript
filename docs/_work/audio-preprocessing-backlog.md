# Backlog: Audio-Vorverarbeitung für Whisper

## Fragestellung

Prüfen, ob eine optionale lokale Vorverarbeitung von Audio- und Video-Tonspuren
die Whisper-Erkennung messbar verbessert. Denkbare Ansätze sind Sprachhervorhebung,
Rauschminderung, Normalisierung, Hoch-/Tiefpassfilter und weitere Decoder- oder
Whisper-Parameter.

## Leitplanken

- Noch keine Funktion oder Einstellungsoption festlegen, bevor Vergleichstests
  einen tatsächlichen Qualitätsgewinn zeigen.
- Originalton nie überschreiben; Vorverarbeitung nur im temporären Audiopfad.
- Sprache, Musik, Hintergrundgeräusche und bereits sauber aufgenommene Dateien
  getrennt testen, weil aggressive Filter Erkennungsdetails zerstören können.
- Vollständig lokale Android-Verarbeitung und zusätzlicher Zeit-/Speicherbedarf
  müssen zur App passen.
- Erst nach der technischen Untersuchung entscheiden, welche wenigen verständlichen
  Optionen gegebenenfalls in die Einstellungen gehören.

## Nächster Schritt

Nach Abschluss der lokalen KI-Nachbearbeitung verfügbare Decoder-, DSP- und
Whisper-Parameter analysieren und anschließend eine kleine Testmatrix mit denselben
Audiodateien im Original und mit ausgewählten Vorverarbeitungen definieren.
