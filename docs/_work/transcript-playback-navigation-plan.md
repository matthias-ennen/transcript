# Arbeitsplan: abschnittsbezogene Audiowiedergabe

Stand: 13. August 2026

Status: technisch umgesetzt; erster Gerätetest abgeschlossen, Endabnahme steht aus.

GitHub-Arbeitspaket: Issue #48

## Ziel

Die Wiedergabe eines fertigen Transkripts wird direkt mit dessen Textabschnitten
verbunden. Die Oberfläche bleibt dabei ruhig, scrollt nicht automatisch und nutzt
die bereits vorhandene Wiedergabeaktualisierung im 100-ms-Takt.

## Bedienung

1. Die obere Schaltflächenleiste enthält von links nach rechts Zurück, Play/Pause,
   Vor und Aufnahme. Die Wellenform nutzt darunter die gesamte verfügbare Breite.
2. Zurück springt zuerst an den Anfang des aktiven Abschnitts. Ein zweiter Druck
   am Abschnittsanfang springt zum vorherigen Abschnitt. Eine Toleranz von 500 ms
   macht den Doppelschritt auch während laufender Wiedergabe zuverlässig.
3. Vor springt an den Anfang des nächsten Abschnitts.
4. Sobald die Überschrift der Transkriptkachel oberhalb des sichtbaren Bereichs
   liegt, erscheinen unten Zurück, Play/Pause und Vor. Der Aufnahmebutton wird
   dort nicht dupliziert. Der Nach-oben-Button bleibt ganz rechts; die drei
   Wiedergabebuttons sind exakt unter ihren oberen Gegenstücken ausgerichtet.
   Alle vier schwebenden Buttons übernehmen mit 52 × 32 dp exakt die Kapselform
   der Abschnittsnummern. Der Nach-oben-Button liegt auf deren horizontaler Achse.
5. Sobald die feste Exportleiste mit TXT, SRT, JSON und Teilen in den sichtbaren
   Bereich eintritt, werden alle schwebenden Buttons ausgeblendet. Entscheidend
   sind gemessene Element- und Viewportpositionen, kein fest codierter dp-Abstand.

## Abschnittsmarkierung

- Ausschließlich bei einem vollständig erstellten Transkript wird der zur
  Wiedergabeposition gehörende Textbereich weiß umrandet.
- Hinter dem Text wächst innerhalb des Abschnitts ein weißes Overlay mit demselben
  Alpha-Wert von 0,62 wie die schwebenden Buttons von links nach rechts. Eine
  zusätzliche senkrechte Linie wird nicht dargestellt.
- Die weiße Umrandung verwendet dieselbe Strichstärke von 2 dp wie die Markierung
  des ausgewählten Whisper-Modells. Die übrige Textfeldgestaltung bleibt erhalten.
- Pause friert den Zustand ein, Verschieben der Wellenform aktualisiert ihn, und
  das Ende der Datei bleibt am vollständig gefüllten letzten Abschnitt stehen.
- Es gibt kein automatisches Scrollen und keine zusätzliche Zeitgeberschleife.

## Prüfpunkte

- Navigation an Abschnittsanfang, -mitte, Lücken und Dateiende.
- Markierung und Overlay nach Play, Pause und manuellem Positionswechsel.
- Schwebende Leiste erscheint und verschwindet an den gemessenen Grenzen.
- Ausrichtung der drei duplizierten Buttons auf einem realen Gerät prüfen.
- Exportleiste bleibt vollständig bedienbar und wird nicht überdeckt.
- Der Übernahmebutton der manuellen Bearbeitung bleibt mit „Übernehmen“ einzeilig.
