# Arbeitsplan: KI-Leistung und Hardware

## Ziel

Die App erhält unter **Einstellungen → KI-Nachbearbeitung** direkt unter der
**KI-Diagnose-Seite** den dauerhaften Link **KI-Leistung und Hardware**. Dieser
öffnet eine eigene Seite im Stil der KI-Diagnose: Titel, kapselförmiger
**Verlassen**-Button, gemeinsame CannaBot-Statuszeile und anschließend sämtliche
sinnvoll wirksamen Leistungs- und Hardwareparameter.

## Verbindliche Oberfläche

1. Eigene Seite **KI-Leistung und Hardware**; **Verlassen** führt zurück zu den
   Einstellungen.
2. Die Seite beginnt mit der bestehenden gemeinsamen CannaBot-Statuszeile.
3. Einstellungen werden nach Themen in aufklappbaren Karten gegliedert:
   Modellprofil, CPU/Threadpool, Kontext und Speicher, KleidiAI, Vulkan,
   Wärme/Stabilität, Benchmark sowie Profile/Datenaustausch.
4. Technische Werte bleiben vollständig einstellbar. Unwirksame
   Scheineinstellungen werden nicht angeboten.
5. Jedes KI-Modell besitzt ein getrenntes, dauerhaft gespeichertes Profil.
6. Änderungen, die Modell oder Rechenkontext betreffen, entladen die bestehende
   KI-Sitzung kontrolliert. Laufende Verarbeitung sperrt die Regler.

## Laufzeitparameter

- getrennte Threadzahlen für Token-Ausgabe und Promptverarbeitung
- Kontext-, Batch- und Micro-Batchgröße
- maximales Ausgabelimit für freie KI-Anfragen
- Flash Attention: automatisch, ein oder aus
- Lademethode: automatisch, Memory Mapping, RAM/Lesen, MLock oder Mapping + MLock
- CPU-Kernauswahl, strikte Bindung, Priorität und Polling
- CPU-Pfad: Standard, KleidiAI oder automatisch
- KleidiAI-SME-Steuerung und Chunk-Multiplikator, soweit auf dem Gerät verfügbar
- Backend: CPU, Vulkan, gemischt oder automatisch
- exakte GPU-Schichtzahl, GPU-Anteil als abgeleitete Bedienhilfe,
  KQV-/KV-Auslagerung und Operationsauslagerung
- Speicherreserve und maximale nutzbare Speicherschwelle als echte
  Vorab-Schutzprüfung
- Wärmeverhalten mit Warn-, Reduktions- und Abbruchschwelle
- automatische Rückkehr zur letzten funktionierenden Konfiguration

## Hardwarediagnose

Die Seite zeigt erkannte CPU-Kerne, ARM-Fähigkeiten, KleidiAI-Verfügbarkeit,
Vulkan-Geräte, Backend-Speicher, Android-Speicherstatus, Akkustand, Ladezustand
und thermischen Status. Die Anzeige stammt aus Android und der tatsächlich
gebauten nativen Laufzeit.

## Benchmark

Ein reproduzierbarer Leistungstest verwendet das installierte Modell des aktiven
Profils. Einstellbar sind Aufwärm- und Messdurchläufe, Ausgabetokens,
Promptlänge, Pause, Mindestakku, Ladegerätpflicht und zulässiger thermischer
Status. Gespeichert werden Modellladezeit, Prompt- und Ausgabetempo,
Zeit bis zum ersten Token, Gesamtdauer, Speicher, Temperaturstatus,
tatsächlicher Backendpfad sowie Fehler/Rückfall. Ergebnisse lassen sich als
Profil übernehmen.

## Technische Umsetzung

- neue versionierte Profilmodelle und `SharedPreferences`-Ablage im App-Modul
- Konfigurationsobjekt im `llm`-Modul und erweiterte JNI-Erzeugung
- KleidiAI und Vulkan fest in den ARM64-Build aufnehmen
- KleidiAI zur Laufzeit über `use_extra_bufts` aktivieren/deaktivieren
- Vulkan-Gerät und Auslagerungsparameter über die öffentlichen `llama.cpp`-APIs
  auswählen
- optionalen `ggml`-Threadpool für Affinität, Priorität und Polling verwenden
- Konfiguration in den Schlüssel des bestehenden `AiEngineSessionManager`
  aufnehmen
- Benchmark und Hardwareaufnahme vom normalen Transkriptkorrekturpfad trennen
- README und Architektur parallel aktualisieren

## Prüfpunkte

- Navigation, Titel, Verlassen-Button und CannaBot-Statuszeile entsprechen der
  KI-Diagnose-Seite.
- Profile bleiben pro Modell nach Neustart erhalten und lassen sich kopieren,
  zurücksetzen sowie als JSON importieren/exportieren.
- Standard-CPU, KleidiAI und Vulkan werden tatsächlich an die native Laufzeit
  übergeben; die Diagnose meldet den real verwendeten Pfad.
- Ungültige Kombinationen werden vor dem Laden normalisiert oder verständlich
  abgelehnt.
- Bestehende KI-Unterhaltung, Transkriptkorrektur und Modellauswahl funktionieren
  weiterhin.
- JVM-Tests, Kotlin/Compose, ARM64/x86_64-Native-Build, Signaturprüfung und
  GitHub-Actions-APK bleiben erfolgreich.

