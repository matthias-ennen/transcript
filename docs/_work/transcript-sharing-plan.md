# Arbeitsplan: Transkript-Karte und Teilen-Funktion

Stand: 8. August 2026

Status: Implementiert; Quell- und Diff-Prüfung abgeschlossen. Der vollständige
Gradle-/CI-Build folgt mit dem nächsten APK-Lauf.

## Ziel

Der vollständige Transkriptbereich wird als gemeinsame Karte im Stil von
**Ergebnis** und **Diagnose** dargestellt. Neben TXT, SRT und JSON steht ein
gleich gestalteter Symbol-Button zum Teilen bereit.

## Umsetzung

1. Die Karte umfasst Überschrift, Ein-/Ausklappen, Gruppen, Textsegmente,
   Bearbeitung und sämtliche Exportmöglichkeiten.
2. Der Teilen-Button öffnet einen abgerundeten Cannabot-Dialog mit der Frage
   **„Welche Formate möchten Sie teilen?“**.
3. TXT ist zunächst ausgewählt; TXT, SRT und JSON können einzeln oder gemeinsam
   ausgewählt werden. Ohne Auswahl bleibt die Teilen-Aktion deaktiviert.
4. Alle Formate verwenden dieselbe vorhandene Exportlogik und denselben
   Metadatenzeitpunkt.
5. Temporäre Dateien liegen ausschließlich unter `cache/shared_transcripts`.
6. Ein Android-`FileProvider` erteilt der ausgewählten Ziel-App nur Leserechte
   für diese Dateien.
7. Ein Format wird über `ACTION_SEND`, zwei oder drei Formate werden über
   `ACTION_SEND_MULTIPLE` an das Android-Teilen-Menü übergeben.

## Prüfpunkte

- Dateinamen sind für Export und Teilen identisch und dateisystemsicher.
- Die Reihenfolge bleibt TXT, SRT, JSON – unabhängig von der Antippfolge.
- Korrigierter und übernommener Text wird in allen geteilten Dateien verwendet.
- Während einer noch nicht übernommenen Bearbeitung bleiben Export und Teilen
  deaktiviert.
- Manifest, `FileProvider`-Pfad und URI-Leserecht stimmen überein.
- JVM-Tests, Kotlin-/Compose-Build, nativer Build und Signaturprüfung laufen im
  APK-Workflow erfolgreich durch.
