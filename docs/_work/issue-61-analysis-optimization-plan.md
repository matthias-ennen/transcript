# Issue #61 – KI-Auswertung: Vergleichs- und Optimierungsplan

Stand: 29.08.2026

Dieses Arbeitsdokument beschreibt das freigegebene Arbeitspaket für die lokale KI-Auswertung aus #102. Die nächste an Matthias ausgegebene APK ist der gemeinsame Geräte-Testbuild; technische Zwischenläufe werden nicht zur Installation ausgegeben.

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

Jeder abgeschlossene Auswertungslauf friert seine tatsächlich verwendete Konfiguration, den aktiven Runtimepfad sowie die zu diesem Auftrag gehörenden nativen Generationsmetriken zum Abschlusszeitpunkt ein. Die UI rekonstruiert diese Werte nicht nachträglich aus veränderbaren Einstellungen oder einem späteren KI-Lauf.

Erfasst werden:

- Service-Ende-zu-Ende-Dauer
- Modellladezeit und Information, ob das passende Modell bereits geladen war
- Vorbereitung vor der Analyse und Nachbereitung danach
- gesamte Inferenzzeit und Analyse-Wanduhrzeit
- je KI-Aufruf die sichtbare Teil-/Merge-Phase, Phasendauer und Overhead
- Promptverarbeitung, Time-to-first-token und Generierungszeit
- Prompt-Decode/Prefill und Generierung jeweils mit Tokens/s
- Eingabe-/Ausgabetokens und Finish-Grund
- Kontext, Batch/Micro-Batch und Prompt-/Generierungsthreads
- tatsächlich aktiver CPU-/KleidiAI-/Vulkanpfad, GPU-Schichten und Fallbackstatus
- Start-, maximal gemessener und abschließender App-PSS sowie höchster gemessener Android-Thermalstatus
- Anzahl Quellteile und Anzahl hierarchischer KI-Aufrufe

Die Ressourcenmessung erfolgt an den Phasengrenzen des realen Produktpfads. Der ausgewiesene RAM-Wert heißt deshalb bewusst „Max. Messwert“ und behauptet keine nicht beobachtbare punktgenaue Spitze zwischen zwei Messpunkten. Die Compose-Renderzeit selbst wird nicht als vermeintlich exakter Wert ausgegeben; die für #61 entscheidenden mehrsekündigen oder mehrminütigen Laufzeitanteile liegen im Service-/Runtimepfad.

## Geräte-Testbasis

Für einen fairen Modellvergleich bleibt das akzeptierte Transkript während eines Vergleichs unverändert. Zuerst wird mit Standardprofilen und CPU/AUTO gearbeitet; Vulkan/Hybrid wird wegen des bereits nachgewiesenen nativen Absturzrisikos nicht unkontrolliert in die Baseline gemischt. Der separate Sicherheitsstand aus PR #100 bleibt für die anschließende kontrollierte GPU-Prüfung relevant.

Die erste Geräteauswertung soll mit derselben Transkriptquelle über alle sechs Modelle erfolgen. Die eingeblendeten Leistungsdaten können direkt über „Leistungsdaten kopieren“ übernommen werden, damit Modell, Runtimepfad und Messwerte nicht manuell abgeschrieben werden müssen.

Für die Qualitätsprüfung werden anschließend dieselben vier Produktaktionen auf den aufgrund der Baseline sinnvollen Referenzmodellen betrachtet. Kurze und lange Transkripte werden getrennt bewertet; ein langer Lauf muss seine Quellteile und Zusammenführungsaufrufe einzeln ausweisen.

## Entscheidungsregel

Eine Konfiguration wird nur bevorzugt, wenn sie bei identischem Material messbar schneller ist und Ergebnisqualität, Stabilität und vollständige Quellenabdeckung erhält. Ausgabelimits werden nicht lediglich zur Beschleunigung versteckt gekürzt. Eine endgültige Modell-/Backendempfehlung oder Reduzierung des sichtbaren Modellkatalogs erfolgt erst nach den realen Xiaomi-Messungen und ausdrücklicher Produktentscheidung von Matthias.
