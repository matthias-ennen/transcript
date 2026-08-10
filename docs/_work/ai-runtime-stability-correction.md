# Arbeitsplan: KI-Leistung und Hardware stabilisieren

## Bestätigte Ausgangslage

- Es gibt drei angebotene KI-Modelle und damit genau drei Modellprofile.
- Ein CPU-Profil konnte bislang GPU-Auslagerungen behalten.
- Auf dem Xiaomi 13T Pro endete Vulkan während `llama_decode()` mit
  `VK_ERROR_DEVICE_LOST` und einer ungefangenen C++-Exception.
- Das Q4_0-Modell kann KleidiAI nutzen; die beiden Q4_K_M-Modelle benötigen
  den kontrollierten Standard-CPU-Rückfall.

## Korrekturen

1. CPU und Automatik in Kotlin und JNI vollständig Vulkan-frei erzwingen.
2. Sichere, GPU-freie Standardwerte und Migration alter Profile einführen.
3. Genau ein gespeichertes Profil je Modell beibehalten; die zusätzliche
   „letzte funktionierende Konfiguration“ entfernen.
4. Vulkan-Einstellungen in der Oberfläche nur für Vulkan/Hybrid aktivieren.
5. Native Exceptions an den Inferenz-Einstiegspunkten in kontrollierte Fehler
   umwandeln und den aktiven Laufzeitpfad wahrheitsgetreu ausgeben.
6. Unit-, Native-, APK- und CI-Prüfungen ergänzen.

## Prüfpunkte

- CPU/Automatik ergeben immer 0 GPU-Schichten und beide Offloads `false`.
- Alte CPU-Profile mit GPU-Werten werden beim Laden korrigiert gespeichert.
- Pro Modell existiert nur `profile_<model-id>` als Konfigurationsquelle.
- JNI ignoriert GPU-Werte bei CPU/Automatik unabhängig vom Aufrufer.
- `VK_ERROR_DEVICE_LOST` verlässt JNI als kontrollierter Fehler statt als
  ungefangene C++-Exception.
- Die fertige signierte APK besteht die vorhandene Backend-/Kernelprüfung.
