# Arbeitsplan: flüchtige KI-Unterhaltung

## Aktueller Umfang

- Die Seite **KI-Diagnose** verwendet über mehrere Anfragen dieselbe flüchtige
  Unterhaltung. Das Modell bleibt geladen; für jeden Chat-Turn wird ein frischer
  nativer Rechenkontext aus der vollständigen Unterhaltung aufgebaut.
- Es wird kein Chatverlauf angezeigt oder dauerhaft gespeichert. Sichtbar bleibt
  ausschließlich die jeweils letzte KI-Antwort mit ihren Messwerten.
- Ein Modellwechsel, das Löschen des Modells, das Beenden des App-Prozesses oder
  **Unterhaltung zurücksetzen** beendet den flüchtigen Gesprächskontext.
- Reicht das Kontextfenster nicht mehr für eine weitere Antwort, fordert die App
  zu einem bewussten Zurücksetzen auf. Der Kontext wird nicht unbemerkt verworfen.

## Technische Prüfpunkte

- Die erste Anfrage legt Systemanweisung, Benutzeranfrage und Antwort als
  unsichtbare Nachrichten ausschließlich im Arbeitsspeicher ab.
- Die nächste Anfrage wird an diese Nachrichten angehängt. Die jeweilige
  Modellvorlage rendert anschließend die vollständige Unterhaltung neu, sodass
  die Antwort auf frühere Aussagen Bezug nehmen kann.
- Jeder Auftrag erhält einen frischen `llama_context`. Dadurch gibt es zwischen
  zwei Chat-Turns keine fehleranfällige KV-Cache-, Tokenpositions- oder
  Präfix-Synchronisierung. Nach der Antwort wird dieser Rechenkontext sofort
  freigegeben; das geladene Modell und die flüchtigen Nachrichten bleiben erhalten.
- Das eingebettete Qwen-Chattemplate bleibt mit `enable_thinking=false` aktiv.
- Messwerte kennzeichnen, ob eine neue Unterhaltung begonnen oder die vorhandene
  Unterhaltung fortgeführt wurde.
- Modell und Gesprächskontext werden weiterhin nur einmal gleichzeitig benutzt.
- Die Diagnosewerte für Eingabetokens beziehen sich auf den vollständigen, für
  den jeweiligen Turn erneut eingelesenen Gesprächskontext.

## Manueller Gerätetest

Für jedes der drei Qwen-Modelle wird derselbe Test durchgeführt:

1. **Unterhaltung zurücksetzen**.
2. `Ich heiße Matthias.` senden.
3. `Wie heiße ich?` senden und prüfen, ob die Antwort `Matthias` berücksichtigt.
4. Eine dritte Bezugnahme senden, um mehr als einen Folge-Turn zu prüfen.
5. Modell wechseln und prüfen, ob eine neue Unterhaltung beginnt.

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
