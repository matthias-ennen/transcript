# Issue #25 – Statusdarstellung und Benachrichtigungen

## Ausgangsbasis

- stabiler Hauptstand: `0.5.264-signed`
- alle fünf Whisper-Stufen auf dem Xiaomi-Zielgerät abgenommen
- Worker-Heartbeat und nativer Whisper-Prozentfortschritt bleiben getrennt
- Transkriptionsprozess bleibt in `:transcription` isoliert

## Zielstruktur

1. `StatusMessagePolicy` typisiert Fortschritt, wichtige Ereignisse, Abschluss und Fehler.
2. Die KanaBot-Zeile hält wichtige Ereignisse für einen vollständigen 3,6-Sekunden-Puls.
3. Fortschrittsmeldungen werden nicht gesammelt; der jeweils aktuelle Wert ersetzt den alten.
4. Laufzeit und aktueller Verarbeitungsschritt wechseln sich in derselben Statuszeile ab.
5. `TranscriptionService` verwendet getrennte Kanäle für laufende Arbeit und fertige Transkripte.
6. Die Abschlussbenachrichtigung ist neutral, sperrbildschirmtauglich und pro Auftragsgeneration einmalig.
7. VAD-Automatikmesswerte werden mit dem aktuellen Transkript gespeichert und nur in der App angezeigt.

## Regressionsschutz

- keine Änderung an Watchdog-Abbruchkriterien
- keine erneute Kopplung von UI- und Whisper-Prozess
- keine hochfrequenten identischen Notification-Updates
- kein VAD-Diagnoseinhalt in TXT, SRT oder JSON
- kein neuer Auftrag durch Antippen oder Wegwischen einer Benachrichtigung

## Prüfpunkte

- Unit-Tests für Statuspriorität und Mindestanzeige
- Unit-Tests für einmalige, neutrale Abschlussbenachrichtigung
- Unit-Tests für seitenspezifische Einstellungsbestätigungen
- Roundtrip-Tests für Status- und Ergebnis-Persistenz
- Unit-Tests für VAD-Dauer- und Pausenberechnung
- vollständiger Debug-/Release-Build, Lint und signierter CI-Build
- getrennte manuelle Geräteabnahme gemäß Issue #25
