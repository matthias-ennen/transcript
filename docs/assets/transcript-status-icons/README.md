# Statussymbole für Transkriptabschnitte

Freigegebene Symbolfamilie für Issue #46 und die spätere technische Einbindung in Issue #31.

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

## Farben

Die Android-Ressourcen verwenden für die blauen Bestandteile `?attr/colorPrimary`. Damit entspricht die Symbolfarbe der dynamischen Material-Primary-Farbe und somit exakt der Farbe der Nummernkapseln. Der Radiergummi ist als bewusster roter Akzent fest definiert.

## Abgrenzung

Die Dateien sind vorbereitete Ressourcen. Statusmodell, Persistenz, barrierefreie Inhaltsbeschreibungen und die sichtbare Einbindung in die GUI erfolgen erst in Issue #31.
