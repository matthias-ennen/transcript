# Fundamenthärtung – Arbeitsplan

## Ausgangspunkt

Der integrierte Stand `c4b88ae` besitzt bereits eine belastbare lokale
Transkriptionspipeline. Dieses Arbeitspaket stabilisiert Persistenz, Datenschutz,
Ressourcenverwaltung, VAD-Entscheidung, Backend-Wahrheit und interne Struktur,
bevor die KI-Nachbearbeitung funktional erweitert wird.

## Verbindlicher Umfang

1. Fertige Transkripte und Bearbeitungen atomar im privaten App-Speicher sichern.
2. Android-Cloud-Backup und Geräteübertragung vollständig deaktivieren.
3. Zuständigkeiten aus `MainScreenViewModel` und `MainScreen` in klar benannte
   Speicher-, Transkriptions- und UI-Komponenten auslagern, ohne das sichtbare
   Bedienkonzept unnötig zu verändern.
4. Automatische VAD-Entscheidung konservativ anhand des tatsächlichen
   Silero-Ergebnisses bewerten und verständlich melden.
5. VAD-Rückfall an genau einer Stelle durchführen; Anzeige und Verarbeitung
   müssen dasselbe Verfahren melden.
6. Whisper-Executor, native Kontexte, Decoder und temporäre Ressourcen in jedem
   Abschluss-, Fehler- und Abbruchpfad freigeben.
7. Whisper-Vulkan nur anbieten und anzeigen, wenn das Backend tatsächlich gebaut
   und zur Laufzeit nutzbar ist; andernfalls kontrolliert und sichtbar CPU nutzen.
8. Unit-, Integrations- und Wiederholungsprüfungen für die kritischen Abläufe
   ergänzen.
9. Debug- und Release-Artefakte sauber trennen; signierte Release-APK und AAB in
   GitHub Actions bauen und prüfen.
10. Original-Whisper-Ergebnis und künftiges KI-Arbeitsergebnis technisch getrennt
    halten.

## Reihenfolge und Kontrollpunkte

- **A – Schutz und Datenhaltbarkeit:** Backup aus, Ergebnis-Repository,
  Wiederherstellung und Bereinigung.
- **B – Ressourcen und Ablaufwahrheit:** zentraler VAD-Rückfall, sichere
  `close()`-Pfade, zutreffende Statusmeldungen.
- **C – Modularität:** bestehende Logik in kleine Verantwortlichkeiten
  verschieben; UI-Verhalten per Tests absichern.
- **D – Backend:** Vulkan-Build, Fähigkeitserkennung, CPU-Rückfall und Diagnose.
- **E – Produktprüfung:** Unit-/Integrationsprüfungen, Release-APK/AAB,
  Signatur- und Artefaktkontrolle.

## Nicht Bestandteil

Die eigentliche manuelle oder automatische KI-Textkorrektur wird nicht
fertiggestellt. Dieses Paket stellt nur ihre sichere Daten- und
Architekturgrundlage her.

## Abnahmekriterien

- Prozessneustart erhält ein fertiges oder bewusst bearbeitetes Transkript.
- Kein App-Inhalt nimmt an Backup oder Device Transfer teil.
- VAD wird im Automatikmodus nur bei belastbarem Nutzen verwendet; ein Fehler
  erzeugt höchstens einen CPU-/Ohne-VAD-Wiederholungspfad.
- Wiederholte Transkriptionen hinterlassen keine wachsende Executor- oder
  Kontextmenge.
- UI und Diagnose nennen das tatsächlich aktive Whisper-Backend.
- Debug und Release sind getrennt; CI erzeugt eine signierte Release-APK und AAB.
- Alle vorhandenen und neuen Tests sowie der vollständige Android-/NDK-Build sind
  grün.
