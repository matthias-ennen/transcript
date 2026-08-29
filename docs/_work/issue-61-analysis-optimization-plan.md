# Issue #61 – KI-Auswertung: Vergleichs- und Optimierungsplan

Stand: 29.08.2026

Dieses Arbeitsdokument beschreibt den aktuell freigegebenen nächsten Schritt für die lokale KI-Auswertung aus #102.

## Gemeinsame Modellmatrix

Alle sechs aktuell sichtbaren Qwen3.5-Varianten bleiben Bestandteil der Vergleichsbasis:

- Qwen3.5 0,8B Q4_0
- Qwen3.5 0,8B Q8_0
- Qwen3.5 2B Q4_K_M
- Qwen3.5 2B Q4_0
- Qwen3.5 4B Q4_K_M
- Qwen3.5 4B Q4_0

Die Mess- und Diagnoseinfrastruktur ist modellunabhängig. Eine spätere Reduzierung des Katalogs erfolgt nicht ohne ausdrückliche Produktentscheidung.

## Reale Produktaktionen

Verglichen werden dieselben vier Aktionen aus #102:

1. Zusammenfassen
2. Kernaussagen / Stichpunkte
3. Aufgaben & To-dos
4. Entscheidungen / Besprechungsprotokoll

## Messintegrität

Jeder abgeschlossene Auswertungslauf friert seine tatsächlich verwendete Konfiguration, den aktiven Runtimepfad und die letzte native Generationsmetrik zum Abschlusszeitpunkt ein. Die UI rekonstruiert diese Werte nicht nachträglich aus veränderbaren Einstellungen oder einem globalen späteren KI-Lauf.

Erfasst werden insbesondere:

- Ende-zu-Ende-Dauer
- Modellladezeit
- gesamte Inferenzzeit
- Prompt-/Prefill-Zeit
- Time-to-first-token
- Generierungszeit
- Eingabe-/Ausgabetokens
- Kontext, Batch/Micro-Batch und Threads
- aktiver CPU-/KleidiAI-/Vulkanpfad und Fallbackstatus
- Anzahl Quellteile und Anzahl hierarchischer KI-Aufrufe

## Nächste Optimierungsstufe

Nach grüner CI für die Messintegrität wird eine reproduzierbare Praxisbasis für alle sechs Modelle aufgebaut. Änderungen an Threads, Batch/Micro-Batch, Ausgabelimits und Backend werden nur übernommen, wenn sie bei identischem Material messbar schneller sind und die Ergebnisqualität/Stabilität erhalten bleibt.

Der nächste an Matthias ausgegebene APK-Build wird ausdrücklich als #61-Testbuild gekennzeichnet und erhält einen kurzen Testplan. Zwischen-Builds dienen nur CI/Entwicklung und müssen nicht installiert werden.
