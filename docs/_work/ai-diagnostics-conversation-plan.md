# Arbeitsplan: flüchtige KI-Unterhaltung

## Aktueller Umfang

- Die Seite **KI-Diagnose** verwendet über mehrere Anfragen dieselbe native
  Unterhaltung und denselben KV-Cache.
- Es wird kein Chatverlauf angezeigt oder dauerhaft gespeichert. Sichtbar bleibt
  ausschließlich die jeweils letzte KI-Antwort mit ihren Messwerten.
- Ein Modellwechsel, das Löschen des Modells, das Beenden des App-Prozesses oder
  **Unterhaltung zurücksetzen** beendet den flüchtigen Gesprächskontext.
- Reicht das Kontextfenster nicht mehr für eine weitere Antwort, fordert die App
  zu einem bewussten Zurücksetzen auf. Der Kontext wird nicht unbemerkt verworfen.

## Technische Prüfpunkte

- Die erste Anfrage legt Systemanweisung, Benutzeranfrage und Antwort im nativen
  Kontext ab.
- Die nächste Anfrage wird an den bestehenden Chatpräfix angehängt und kann auf
  frühere Aussagen Bezug nehmen.
- Das eingebettete Qwen-Chattemplate bleibt mit `enable_thinking=false` aktiv.
- Messwerte kennzeichnen, ob eine neue Unterhaltung begonnen oder die vorhandene
  Unterhaltung fortgeführt wurde.
- Modell und Gesprächskontext werden weiterhin nur einmal gleichzeitig benutzt.

## Backlog: automatisierte Transkriptkorrektur

Die automatisierte KI-Nachbearbeitung wird später gesondert überarbeitet:

1. **KI-Nachbearbeitung** startet für genau den ausgewählten, aufgeklappten
   Fünf-Minuten-Abschnitt eine eigene Arbeitssitzung.
2. Feste Korrekturanweisung und Abschnittskontext bleiben während der Bearbeitung
   aller Segmente dieses Abschnitts erhalten.
3. Nach dem letzten Segment wird die Abschnittsunterhaltung sofort beendet und
   ihr KV-Cache freigegeben.
4. Vor der Umsetzung wird noch festgelegt, welche Antworten im laufenden Kontext
   verbleiben und wie ein unnötig wachsender Kontext verhindert wird.

Dieser Backlogpunkt verändert den vorhandenen Korrekturpfad in diesem Paket noch
nicht.
