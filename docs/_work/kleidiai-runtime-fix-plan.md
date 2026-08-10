# Arbeitsplan: KleidiAI-Laufzeitkorrektur

## Befund

Die ARM64-APK enthält zwar KleidiAI-Quellcode und den CPU-KleidiAI-Puffertyp,
aber die bisherige Diagnose prüft ein CMake-Makro, das die JNI-Datei nicht
erreicht. Außerdem wird nur ein allgemeiner ARM64-CPU-Build erzeugt; in diesem
Build fehlen die eigentlichen Dot-Product-/INT8-KleidiAI-Rechenkerne. Fehler beim
Laden oder Auslesen der nativen Laufzeit werden in der Oberfläche derzeit als
lauter negative Fähigkeiten dargestellt.

## Ziel

- ARM64 enthält einen portablen Standard-CPU-Pfad und mehrere zur Laufzeit
  ausgewählte, gerätesichere ARM-Varianten mit echten KleidiAI-Kernen.
- `llama.cpp` wählt anhand der Android-CPU-Fähigkeiten die beste kompatible
  Variante; `use_extra_bufts` schaltet innerhalb dieser Variante zwischen
  Standard-CPU und KleidiAI um.
- Die Diagnose unterscheidet eindeutig zwischen eingebaut, auf dem Gerät
  nutzbar und für das geladene Profil angefordert.
- Ein nativer Lade-/JNI-Fehler wird als Fehlertext angezeigt und nicht in
  scheinbare „Nein“-Werte umgewandelt.

## Umsetzung

1. ARM64 auf die dynamischen Android-CPU-Varianten von `llama.cpp` umstellen und
   nur die benötigten kompatiblen Varianten samt Vulkan in die APK aufnehmen.
2. Backend-Suchpfad aus Android an JNI übergeben und CPU/Vulkan kontrolliert aus
   dem App-eigenen Native-Verzeichnis laden.
3. CPU-Fähigkeiten und KleidiAI-Einbindung über die ausgewählte Backend-Registry
   abfragen statt über nicht weitergereichte Präprozessor-Makros.
4. Native Diagnose um Ladezustand, KleidiAI-Nutzbarkeit, ausgewählte
   CPU-Variante und aussagekräftige Fehler erweitern.
5. UI und Dokumentation an die drei Zustände anpassen.
6. JVM-Tests, vollständigen Android-/Native-Build, APK-Signatur und einen
   Binärtest auf enthaltene KleidiAI-Kernel durchführen.

## Abschlusskriterien

- Auf ARM64 meldet die APK `KleidiAI eingebaut: Ja`.
- Dot Product und INT8 werden auf geeigneter Hardware aus der tatsächlich
  ausgewählten CPU-Variante gemeldet.
- Die APK enthält mindestens Standard-, Dot-Product- und INT8-CPU-Varianten.
- Standard-CPU deaktiviert zusätzliche Puffertypen; KleidiAI/Automatisch gibt
  sie nur frei, wenn ein kompatibler KleidiAI-Puffer vorhanden ist.
- Fehler der nativen Diagnose sind in der Oberfläche sichtbar.

## Checkpoints

- [x] Root Cause gegen die Binärdaten der Build-#149-APK belegt.
- [x] Laufzeitgewählte ARM-CPU-Varianten umgesetzt.
- [x] Diagnose trennt eingebaut, gerätenutzbar, modellkompatibel und aktiv.
- [x] APK-Payload-Prüfung in CI ergänzt.
- [x] NDK auf r27c aktualisiert, nachdem r25/Clang 14 beim ARMv9/SVE2-Kernel abstürzte.
- [ ] Signierte CI-APK gebaut und geprüft.
- [ ] Pull Request nach grünen Checks zusammengeführt.
