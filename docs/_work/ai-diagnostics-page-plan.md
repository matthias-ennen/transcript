# Arbeitsplan: dauerhafte KI-Diagnose-Seite

## Ziel

Der freie lokale KI-Test wird dauerhaft aus der Hauptseite in eine eigene
**KI-Diagnose**-Seite verschoben. Die Seite bleibt Bestandteil der App und ist
über den KI-Bereich der Einstellungen erreichbar.

## Verbindliche Oberfläche

1. Im Einstellungsbereich **KI-Nachbearbeitung** steht oberhalb der beiden
   Schalter der unterstrichene Link **KI-Diagnose-Seite**.
2. Der Link öffnet eine separate App-Seite mit dem Titel **KI-Diagnose**.
3. Rechts in der Kopfleiste steht der kapselförmige Button **Verlassen**.
4. **Verlassen** führt zurück zur Seite **Einstellungen**, von der die
   Diagnoseseite geöffnet wurde.
5. Die Diagnoseseite zeigt zuerst dieselbe CannaBot-Statuszeile wie die
   Hauptseite und darunter den vollständigen freien KI-Testbereich.
6. Die Hauptseite behält die gemeinsame CannaBot-Statuszeile, zeigt den freien
   KI-Testbereich aber nicht mehr.
7. Die allgemeine Log-Karte **Diagnose** steht ganz unten auf der Diagnoseseite
   und wird auf der Hauptseite nicht mehr angezeigt.
8. Die Karte **Letzte KI-Korrektur** bleibt beim Transkript auf der Hauptseite,
   weil sie einen konkreten Korrekturlauf und nicht den freien KI-Chat erklärt.

## Technische Umsetzung

- Navigation um `AI_DIAGNOSTICS` ergänzen.
- CannaBot-Statuszeile in eine gemeinsam verwendete Compose-Komponente
  auslagern.
- Freien KI-Testbereich in die neue Diagnoseseite verschieben.
- Diagnoseeingabe, Antwort, Messwerte und geladene KI-Sitzung weiterhin aus dem
  bestehenden `MainScreenViewModel` beziehungsweise `AiEngineSessionManager`
  beziehen; kein zweiter Zustand und keine zweite Modellinstanz.
- README und Architekturhinweise an die dauerhafte Navigation anpassen.

## Prüfpunkte

- Einstellungen öffnen → **KI-Diagnose-Seite** antippen → Diagnoseseite öffnet.
- Titel **KI-Diagnose**, gemeinsamer CannaBot-Status und vollständige
  Diagnosekachel sind sichtbar.
- **Verlassen** führt zu den Einstellungen zurück.
- Die freie Diagnosekachel und die allgemeine Diagnose-Logkarte sind auf der
  Hauptseite nicht mehr vorhanden.
- Eingabe und Antwort bleiben im ViewModel erhalten; das ausgewählte Modell
  bleibt bis zum Modellwechsel oder Prozessende im RAM.
- Bestehende JVM-Tests sowie Kotlin-/Compose- und native Android-Builds bleiben
  erfolgreich.
