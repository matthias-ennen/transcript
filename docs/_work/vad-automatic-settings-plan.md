# Arbeitsplan: VAD-Automatik und Einstellungsnavigation

## Umfang

1. Den Modus **Automatisch** pro Audiodatei konservativ entscheiden lassen.
   Die Analyse dekodiert die Datei abschnittsweise, hält nur Kennzahlen im RAM
   und verwendet VAD ausschließlich bei klaren längeren Ruhephasen ohne starke
   Zerstückelung.
2. **Aus** verwendet VAD nie; **Ein** verwendet es bei installiertem Modell
   immer. Fehlendes Modell und Laufzeitfehler fallen sicher auf Whisper ohne VAD
   zurück.
3. Statusmeldungen für Analyse, Entscheidung, VAD-Start und Rückfall ergänzen.
4. Die VAD-Kachel aus den Whisper-Einstellungen auf die neue Seite
   **VAD-Einstellungen** verschieben.
5. Den unverändert formatierten Titel der vier erweiterten Seiten als Dropdown-
   Navigation mit dezentem Auf-/Ab-Symbol verwenden.
6. Links exakt **VAD-Einstellungen** und **KI-Diagnose** nennen.
7. Installierte Whisper-Modelle in der Modellverwaltung über **Auswählen**
   dauerhaft und synchron zur Hauptseite auswählbar machen.

## Prüfpunkte

- Bestehende VAD-Werte bleiben durch unveränderte Preference-Schlüssel erhalten.
- Automatik entscheidet im Zweifel gegen VAD und lädt nie die ganze Datei in den RAM.
- Native VAD-Zeitstempel werden als Zentisekunden benannt, mit Faktor 10 in
  Millisekunden umgerechnet und vor der Statistik plausibilisiert.
- Ein hoher Pausenanteil darf stabile Sprachbereiche nicht allein verwerfen;
  sehr kurze oder stark fragmentierte Erkennungen bleiben ohne VAD.
- Diagnosewerte weisen analysierte Samples und erkannte Sprach-Samples aus.
- Auswahlfelder des VAD-Modus enthalten nur Aus, Automatisch und Ein.
- Kotlin-/Compose-, Unit-, JNI-/NDK- und signierter APK-Build bleiben grün.
