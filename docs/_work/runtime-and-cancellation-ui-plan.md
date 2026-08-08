# Arbeitsplan: Laufzeit und Abbruchbestätigung

Stand: 8. August 2026

## Ziel

1. Die anhand praktischer Messungen festgelegten Echtzeitfaktoren werden zentral
   verwendet: `0,75 / 1,0 / 1,6 / 6,0 / 7,0`.
2. Die sichtbare Laufzeit wird unabhängig von Decoder- und Whisper-Meldungen
   jede Sekunde aus der verbindlichen Startzeit berechnet.
3. Hinter der echten Laufzeit steht die feste Schätzung, zum Beispiel
   `Laufzeit: 03:42 (≈ 05:00)`.
4. Die Hauptschaltfläche bricht nicht mehr unmittelbar ab, sondern öffnet eine
   abgerundete Bestätigung mit CannaBot, pulsierender Frage sowie den
   Kapselschaltflächen **Weiter** und **Abbrechen**. Diese zusätzliche Abfrage
   gilt für **Ausgewogen**, **Sehr genau** und **Maximale Qualität**. Bei
   **Sehr schnell** und **Schnell** wird weiterhin direkt abgebrochen.
5. Oberhalb der gemeinsamen Ein-/Ausklappschaltflächen grenzt die kompakte
   Zwischenüberschrift **Transkript** den Ergebnisbereich optisch ab.
6. Die Schätzung wird als eigener Zustandswert aus der bereits bekannten
   Audiodauer neu gesetzt: beim Laden einer Datei, nach dem Beenden einer
   Aufnahme, beim Qualitätswechsel und unmittelbar vor dem Start. Eine erneute
   Analyse der Audiodatei ist dafür nicht erforderlich.

## Prüfpunkte

- Alle fünf Faktoren sind durch einen Unit-Test abgesichert.
- Eine ausbleibende Whisper-Rückmeldung kann die Laufzeituhr nicht anhalten.
- Nach erneutem Öffnen wird die Uhr aus der Startzeit korrekt fortgeführt.
- Die Schätzung bleibt während eines Laufs unverändert.
- Nur **Abbrechen** sendet das Abbruchsignal; **Weiter** und Schließen des
  Dialogs lassen die Transkription weiterlaufen.
- Die Modellregel ist durch einen Unit-Test für alle fünf Qualitätsstufen
  abgesichert.
- Der gespeicherte Schätzwert folgt der aktuellen Audiodauer und Qualitätsstufe
  und wird ohne geladene Dauer zurückgesetzt.
