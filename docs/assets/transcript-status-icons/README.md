# Statussymbole für Transkriptabschnitte

Freigegebene Symbolfamilie für Issue #46 und die technische Einbindung in Issue #31.

| Zustand | SVG-Master | Android-Ressource | Zugängliche Bezeichnung |
|---|---|---|---|
| Whisper-Original | `transcript-status-original.svg` | `ic_transcript_status_original.xml` | Unverändertes Whisper-Original |
| Manuell bearbeitet | `transcript-status-manual.svg` | `ic_transcript_status_manual.xml` | Manuell bearbeitet |
| Mit KI bearbeitet | `transcript-status-ai.svg` | `ic_transcript_status_ai.xml` | Mit KI bearbeitet |

## Gestaltung

- Gemeinsame moderne Dokumentgrundform mit gefalteter Ecke und Textlinien.
- Manuelle Bearbeitung: diagonaler blauer Bleistift mit rotem Radiergummi.
- KI-Bearbeitung: großes, sauber konstruiertes `KI` und drei Funkelsterne oben links.
- Die Zustände unterscheiden sich durch ihre Form und nicht ausschließlich durch Farbe.
- Verbindliche Anzeigegröße: 32 dp; 24 dp bleibt als zusätzliche Mindestgrößenprüfung erhalten.
- Vorgesehene Position: links neben der vorhandenen Nummernkapsel eines Transkriptabschnitts.

## Farben und Android-Rendering

Die SVG-Master verwenden das freigegebene Blau `#5B7CFA`. Nach der Geräteabnahme von Issue #31 wurde dieses Blau auch in den Android-VectorDrawables fest hinterlegt. Zuvor verwendete `?attr/colorPrimary`; auf dem hellen Transkript-Hintergrund konnte die dynamische Primary-Farbe dadurch mit zu wenig Kontrast gerendert werden, insbesondere beim MANUAL-Stift.

Damit entsprechen die Android-Ressourcen farblich wieder exakt den freigegebenen SVG-Mastern und bleiben unabhängig vom Material-Theme eindeutig erkennbar. Der Radiergummi des MANUAL-Symbols bleibt als roter Akzent `#E85B5B` fest definiert.

## Abgrenzung

Die Symbolgestaltung selbst wurde nicht neu entworfen. Issue #31 bindet die drei freigegebenen Ressourcen produktiv in die GUI ein. `ORIGINAL` und `MANUAL` sind im aktuellen Transkriptfluss unmittelbar sichtbar prüfbar; die produktive Geräteabnahme des `AI`-Status folgt zusammen mit der zuverlässigen KI-Nachbearbeitung in Issue #30.
