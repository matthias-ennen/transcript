# Backlog: Audio-Vorverarbeitung für Whisper

## Status – 29.08.2026

Die bisherige Backlog-Idee ist mit **Issue #103 – Audio-Vorverarbeitung vor
Whisper systematisch evaluieren** in ein eigenes prüfbares Arbeitspaket überführt.
Dieses Dokument bleibt als kurze fachliche Herkunftsnotiz bestehen; der
verbindliche Umfang, Testkorpus und die Abnahmekriterien stehen in #103.

## Fragestellung

Prüfen, ob eine optionale lokale Vorverarbeitung von Audio- und Video-Tonspuren
die Whisper-Erkennung messbar verbessert. Denkbare Ansätze sind
Sprachhervorhebung, Rauschminderung, Normalisierung, Hoch-/Tiefpassfilter sowie
Decoder- oder Whisper-Parameter.

## Leitplanken

- Noch keine Funktion oder Einstellungsoption festlegen, bevor reproduzierbare
  Vergleichstests einen tatsächlichen Qualitätsgewinn zeigen.
- Originalton niemals überschreiben; Vorverarbeitung nur im temporären Audiopfad.
- Sprache, Musik, Hintergrundgeräusche, leise Aufnahmen, lange Pausen und bereits
  sauber aufgenommene Dateien getrennt testen, weil aggressive Filter
  Erkennungsdetails zerstören können.
- Vollständig lokale Android-Verarbeitung und zusätzlicher Zeit-/Speicherbedarf
  müssen zur App passen.
- Entscheidend ist die Qualität des **Whisper-Ergebnisses**, nicht allein, ob das
  vorverarbeitete Audio subjektiv angenehmer klingt.
- Erst nach der technischen Untersuchung entscheiden, ob überhaupt und welche
  wenigen verständlichen Optionen in das Produkt gehören.

## Abgrenzung

- **#78** untersucht zuerst Ursachen echter Whisper-Wiederholungsschleifen und
  Halluzinationen bei langen Dateien.
- Reproduzierbare Problemfälle aus #78 können anschließend Teil der A/B-Matrix
  von #103 werden.
- **#41** behandelt separat Source-/Vocal-Separation für Songs.
- **#102** ist nachgelagerte lokale KI-Auswertung des fertigen Transkripts und
  darf Whisper-/Audiofehler nicht kaschieren.

## Nächster Schritt

Gemäß Roadmap #26 wird nach #78 in **#103** eine kontrollierte Testmatrix mit
denselben Quelldateien im unveränderten Originalpfad und mit ausgewählten
Vorverarbeitungen durchgeführt. Erst die dort dokumentierten Ergebnisse können
ein späteres Implementierungs-Issue begründen.
