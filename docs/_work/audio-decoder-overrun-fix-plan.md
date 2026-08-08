# Arbeitsplan: Decoder-Überhang bei Abschnittstranskription

Stand: 8. August 2026

## Ausgangslage

Der abschnittsweise Android-Decoder begrenzt den Speicher korrekt auf den
aktuellen Transkriptionsabschnitt. Seine bisherige Zielgröße entsprach jedoch
nahezu exakt der mathematischen Zahl der 16-kHz-Samples. AAC/M4A-Decoder können
durch Codec-Vorlauf, Padding oder Zeitstempelrundung geringfügig mehr PCM-Daten
liefern. Dieser zulässige Überhang löste bislang einen Abbruch aus und wurde
anschließend wirkungslos mit kleineren Abschnitten wiederholt.

## Umsetzung

1. Die abschnittsweise Verarbeitung mit fünf Minuten und 2,5-Minuten-Rückfall
   bleibt unverändert erhalten.
2. Der Decoder erhält einen festen zusätzlichen Sicherheitsspielraum von fünf
   Sekunden 16-kHz-Mono-PCM.
3. Vor der Übergabe an Whisper wird das Ergebnis wieder exakt auf die
   angeforderte Abschnittslänge begrenzt.
4. Ein Überhang innerhalb des Sicherheitsspielraums wird mit der Zahl der
   verworfenen Samples diagnostiziert.
5. Ein Überschreiten auch des Sicherheitsspielraums erhält einen eigenen
   Fehlertyp und löst keine wirkungslose 2,5-Minuten-Wiederholung aus.
6. Ein Zwischenstand gilt nur dann als fortsetzbar, wenn bereits eine
   Audioposition größer als `00:00` abgeschlossen wurde.

## Prüfpunkte

- Speicherbedarf bleibt unabhängig von der Gesamtdauer der Datei begrenzt.
- Sollgröße und Sicherheitsspielraum sind durch JVM-Tests abgedeckt.
- Überzählige Samples werden am Abschnittsende entfernt.
- Ein leerer Start-Checkpoint wird nicht als Wiederaufnahmepunkt angeboten.
- Android-, Kotlin-, Native- und Signaturprüfung laufen im APK-Workflow durch.
