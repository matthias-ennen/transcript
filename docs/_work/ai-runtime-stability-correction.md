# Arbeitsplan: KI-Leistung und Hardware stabilisieren

## Bestätigte Ausgangslage

- Der Qwen3.5-Modellkatalog enthält inzwischen sechs Varianten.
- CPU und Automatik werden bereits Vulkan-frei normalisiert.
- Auf dem Xiaomi 13T Pro endete ein früherer Vulkan-Lauf während `llama_decode()` mit
  `VK_ERROR_DEVICE_LOST`; der aktuelle #61-Gerätetest mit **Qwen3.5 2B Q4_0** und
  vollständigem Vulkan-Offload ließ die App erneut vollständig abstürzen.
- Ein nativer Vulkan-`DEVICE_LOST` kann den Android-Prozess beenden, bevor Kotlin
  einen kontrollierbaren Fehler erhält. Ein nachträglicher CPU-Fallback allein ist
  deshalb kein ausreichender Schutz.
- Q4_0/Q8_0 können den KleidiAI-Pfad nutzen; Q4_K_M darf nicht fälschlich als
  KleidiAI-beschleunigt ausgewiesen werden.

## Bereits umgesetzte Stabilisierung

1. CPU und Automatik in Kotlin und JNI vollständig Vulkan-frei erzwingen.
2. Sichere GPU-freie Standardwerte und Migration alter CPU-Profile verwenden.
3. Genau ein gespeichertes Leistungsprofil je Modell beibehalten.
4. Vulkan-Einstellungen in der Oberfläche nur für Vulkan/Hybrid wirksam machen.
5. Native Exceptions an den Inferenz-Einstiegspunkten soweit möglich in
   kontrollierte Fehler umwandeln und den aktiven Laufzeitpfad ausweisen.

## Zusätzliche #61-Sicherheitsstufe nach dem erneuten Voll-Vulkan-Absturz

Für die nächste Geräte-APK gilt bewusst ein konservativer Testkorridor:

1. **Vollständiger Vulkan-Offload wird vor dem JNI-Einstieg blockiert.** Damit kann
   die bestätigte riskante Konfiguration den Prozess nicht erneut erreichen.
2. **CPU/Vulkan-Hybrid wird zunächst auf höchstens vier GPU-Schichten begrenzt.**
   Höhere Werte werden vor dem JNI-Einstieg mit einer verständlichen Fehlermeldung
   abgelehnt.
3. **Explizite GPU-Pfade verwenden zunächst `n_batch = 32` und `n_ubatch = 32`.**
   Android-Vulkan-Treiber haben bei größeren Prefill-Batches wiederholt
   `DEVICE_LOST` gezeigt; die erste Stabilitätsprüfung beginnt daher bewusst beim
   kleinsten sinnvollen Batch.
4. KQV-/KV-Cache-Offload und Operations-Offload bleiben für den ersten Hybridlauf
   ausgeschaltet.
5. Erst nach einem stabilen realen Hybridlauf werden GPU-Schichten beziehungsweise
   Batchgrößen schrittweise erhöht. Keine zwei GPU-Parameter gleichzeitig ändern.

## Prüfpunkte für die nächste APK

- CPU/Automatik bleiben bei den bisherigen Referenzeinstellungen unverändert.
- Voll-Vulkan führt zu einem kontrollierten App-Fehler **vor** nativer
  Modellinitialisierung und nicht zu einem Prozessabsturz.
- Hybrid mit mehr als vier GPU-Schichten wird kontrolliert abgelehnt.
- Hybrid mit 1–4 GPU-Schichten normalisiert Batch und Micro-Batch auf 32.
- Ein erster Hybridlauf mit vier GPU-Schichten, KQV aus und Operations-Offload aus
  kann auf dem Xiaomi 13T Pro durchgeführt werden.
- Diagnose meldet angeforderten/aktiven Backendpfad, GPU-Gerät, GPU-Schichten und
  CPU-Fallback wahrheitsgetreu.
- Unit-, Native-, APK- und CI-Prüfungen bleiben erfolgreich.
