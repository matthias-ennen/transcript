# Arbeitsplan: isolierte und sequenzielle Transkription

## Ausgangslage

Der Gerätebericht zu `0.5.249-signed` belegt einen nativen `SIGABRT` im
HWUI-Prozess, während Whisper-Vulkan und Android-HWUI gleichzeitig auf
Mali-GPU-Fences warten. Die bisherige UI-Drosselung reduziert Last, trennt die
beiden nativen Laufzeiten aber nicht.

## Zielstruktur

1. `TranscriptionService` läuft in einem privaten Android-Nebenprozess.
2. Der Nebenprozess besitzt keine Oberfläche und initialisiert kein App-HWUI.
3. Zustände werden atomar in einer kleinen Datei gesichert und durch einen
   paketinternen Broadcast an den Hauptprozess signalisiert.
4. Der Hauptprozess liest ausschließlich die gesicherte Zustandskopie; große
   Audio- oder Modelldaten werden niemals per Binder/Broadcast übertragen.
5. Pro Abschnitt gilt eine feste Reihenfolge: Decoder vollständig öffnen,
   Abschnitt dekodieren, Decoder freigeben, Whisper-Modell laden,
   transkribieren, Modell freigeben. Erst danach beginnt der nächste Abschnitt.
6. Ein nativer Absturz des Nebenprozesses wird über `ApplicationExitInfo`
   erkannt und in der weiterhin bedienbaren App als technischer Fehler gemeldet.

## Prüfpunkte

- Zustandsserialisierung für Start, Lauf, Abschluss, Abbruch und Fehler testen.
- Sicherstellen, dass der Decoder beendet ist, bevor Whisper geladen wird.
- Sicherstellen, dass Whisper vor dem nächsten Decoderlauf freigegeben wird.
- Manifest-Prozessgrenze und nicht exportierten Dienst prüfen.
- Keine großen Arrays, Modelle oder vertraulichen Inhalte über IPC übertragen.
- Bestehende Checkpoints, VAD, Abbruch und automatische KI-Nachbearbeitung
  erhalten.
- Debug- und Release-Qualitätsprüfungen ausführen.
- Signierte APK erzeugen und Signatur-/Payload-Prüfungen bestehen lassen.

## Manuell offen

Die Geräteabnahme auf Xiaomi `corot_eea` bleibt getrennt offen. Besonders zu
prüfen sind das zuvor abstürzende große Modell, VAD `Automatisch` und `Aus`, die
Bedienbarkeit der GUI während der Verarbeitung und eine verständliche Meldung
bei einem absichtlich oder tatsächlich beendeten Nebenprozess.
