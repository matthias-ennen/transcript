# Arbeitsplan: Ergebnisanzeige und Laufzeitschätzung

Stand: 8. August 2026

Status: Implementiert; lokale Logik- und Diff-Prüfung abgeschlossen. Der
vollständige Gradle-/CI-Build steht wegen der Laufzeitumgebung noch aus.

## Ziel

Die bereits vorbereiteten UI-Korrekturen werden vervollständigt, ohne die
Transkriptionslogik oder die bestehende Langzeitverarbeitung zu verändern.

## Umfang

1. Erkannte Whisper-Sprachcodes werden für alle unterstützten Sprachen als
   deutsche Sprachbezeichnung angezeigt. Unbekannte Codes bleiben mit ihrem
   Code nachvollziehbar.
2. Abschnittsnummern erhalten bei unveränderter Höhe eine breitere, vollständig
   abgerundete Kapsel für bis zu vier Ziffern.
3. Eine bereitstehende Audio- oder Videodatei zeigt abwechselnd die vorhandene
   Bereitschaftsmeldung und eine vorläufige, modellabhängige Laufzeitschätzung.
4. Der Textwechsel erfolgt am schwächsten Punkt der vorhandenen Pulsation.
5. Die Schätzfaktoren liegen zentral und können später anhand echter Messwerte
   pro Modell angepasst werden.
6. Die vorbereitete Deutsch-/Englisch-Auswahl bleibt von der Whisper-Sprache
   getrennt. Die vollständige GUI-Lokalisierung ist weiterhin ein eigenes
   Folgepaket.

## Prüfpunkte

- Dänisch (`da`) und weitere Whisper-Sprachen werden ausgeschrieben.
- Ein unbekannter Code wird als `Unbekannt (xx)` angezeigt.
- Drei- und vierstellige Abschnittsnummern werden nicht abgeschnitten.
- Die Schätzung reagiert sofort auf Datei- und Modellwechsel.
- Laufzeiten unter und über einer Stunde werden verständlich formatiert.
- Aktive Export-Schaltflächen bleiben blau; „Bearbeiten“ bleibt unverändert.
- JVM-Tests, Android-Build und Diff-Prüfung laufen ohne Fehler.
