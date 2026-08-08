# Arbeitsplan: zuverlässige Transkription langer Audiodateien

## Ausgangslage

Der bisherige Transkriptionspfad dekodiert die komplette Audiospur, sammelt sie
in einem wachsenden `FloatArray` und erzeugt anschließend eine weitere
16-kHz-Kopie. Bei einer Aufnahme von mehr als einer Stunde überschreitet dieser
Spitzenbedarf den verfügbaren Android-Arbeitsspeicher.

Die Wellenformerstellung ist bereits ein eigener, blockweise arbeitender Pfad
und bleibt von diesem Umbau getrennt.

## Zielstruktur

1. Die Mediendatei wird nur abschnittsweise dekodiert.
2. Ein Hauptabschnitt umfasst fünf Minuten; an beiden Grenzen werden bis zu
   zwei Sekunden Kontext mitgegeben.
3. Whisper erhält immer nur den aktuellen 16-kHz-Mono-Abschnitt.
4. Segmentzeitstempel werden auf die absolute Position in der Gesamtdatei
   verschoben.
5. Segmente aus den Überlappungen werden anhand ihres zeitlichen Mittelpunkts
   genau einem Hauptabschnitt zugeordnet.
6. Scheitert ein Fünf-Minuten-Abschnitt, wird er einmal in 2,5-Minuten-
   Abschnitte geteilt.
7. Nach jedem fertigen Hauptabschnitt werden Ergebnis, erkannte Sprache und
   nächste Position im privaten App-Speicher gesichert.
8. Die Verarbeitung läuft in einem Android-Foreground-Service weiter, wenn die
   Oberfläche geschlossen oder der Bildschirm ausgeschaltet wird.
9. Eine erneute Verarbeitung derselben Datei mit demselben Modell und derselben
   Sprache setzt einen kompatiblen Zwischenstand fort.

## Prüfpunkte

- Speicherbedarf wächst nicht mit der Gesamtdauer der Datei.
- Fünf-Minuten-Planung und 2,5-Minuten-Rückfall sind durch Unit-Tests abgedeckt.
- Überlappungen erzeugen weder doppelte noch fehlende Zeitbereiche.
- Zeitstempel über einer Stunde bleiben korrekt.
- Abbruch aus App oder Benachrichtigung beendet Decoder und Whisper.
- Teilergebnisse bleiben bei einer Prozessunterbrechung erhalten.
- Wellenformfehler beeinflussen den Transkriptionsdienst nicht.
- README und Architekturübersicht beschreiben den tatsächlichen Stand.
