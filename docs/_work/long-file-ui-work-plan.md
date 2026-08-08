# Arbeitsplan: Langdatei-UI und Wellenform

## Ziel

Die vier freigegebenen Verbesserungen werden im bestehenden Draft-PR umgesetzt:

1. Laufzeitschätzung nach einem Modellwechsel sofort neu anzeigen.
2. Sprachauswahl exakt im Stil der Qualitätsstufe darstellen.
3. Wellenformerzeugung ohne starres Zeitlimit, mit Anzeige-Fortschritt, reduzierter Abtastung und lokalem Cache weiterlaufen lassen.
4. Transkriptabschnitte in einklappbare Fünf-Minuten-Gruppen gliedern und jeweils gruppenweise bearbeiten.

## Technische Leitplanken

- `main` bleibt unverändert; Ziel ist `agent/simple-transcript-finalization` / Draft-PR #11.
- Die Wellenform bleibt eine leichte Zusatzanzeige und blockiert die Transkription nicht.
- Es werden nur wenige Messpunkte je sichtbarem Wellenformbalken ausgewertet.
- Fertige Wellenformen werden begrenzt lokal zwischengespeichert.
- Es kann immer nur eine Fünf-Minuten-Gruppe bearbeitet werden.
- Zeitstempel, Segmentreihenfolge und Exportformat bleiben unverändert.

## Prüfpunkte

- Modellwechsel zeigt die neue Schätzung ohne erneutes Laden der Datei sofort an.
- Qualitäts- und Sprachauswahl verwenden dieselbe sichtbare Feldkomponente.
- Kein 60-Sekunden-Abbruch mehr; Fortschritt erreicht 100 Prozent; Cache liefert dieselbe Wellenform zurück.
- Gruppengrenzen liegen bei 00:00–05:00, 05:00–10:00 usw.
- Änderungen einer Gruppe verändern keine anderen Segmente und werden in allen Exporten verwendet.
- Sämtliche JVM-Tests sowie Android-/Native-Build und Signaturprüfung laufen erfolgreich.
