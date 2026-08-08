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

## UI-Nachpolitur

- **Alle einklappen** und **Alle ausklappen** verwenden gleich große, blaue
  Kapselschaltflächen im Stil der Exportaktionen.
- TXT, SRT und JSON bleiben durch kompakte, einheitliche Innenabstände auch bei
  vier gleich breiten Aktionen einzeilig; insbesondere wird JSON nicht mehr
  umgebrochen.
- **Abbrechen** und **Änderungen übernehmen** erhalten dieselbe Höhe. Mehrzeiliger
  Text wird innerhalb der Kapsel horizontal und vertikal mittig ausgerichtet.

## Dialoganimation und Navigation

- Beim Öffnen des Teilen-Dialogs zeigt CannaBot mit ruhigen Zwischenpausen die
  Folge **nach rechts laufen**, **springen** und **Arm heben/winken**. Danach
  kehrt er in den Idle-Zustand zurück.
- Bei Transkripten mit mindestens 20 Segmenten erscheint nach deutlichem
  Herunterscrollen unten rechts eine kompakte Pfeil-nach-oben-Kapsel.
- Die Kapsel verwendet einen zu 38 Prozent transparenten Primärfarb-Hintergrund,
  liegt über dem Inhalt und scrollt die gesamte Hauptansicht bis ganz nach oben.
