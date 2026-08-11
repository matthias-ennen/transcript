# Arbeitsplan: Whisper-Einstellungen

## Ziel

Die vorhandenen fünf Whisper-Modelle erhalten gemeinsame, dauerhaft gespeicherte
Transkriptionsparameter. Hauptseite und Einstellungsseite verwenden dieselbe
Sprachauswahl. Die bestehende Abschnittsverarbeitung bleibt speicherschonend und
wird konfigurierbar, ohne parallele Modellinstanzen zu erzeugen.

## Umfang

1. Speicherbelegung der lokalen KI-Modelle in den Einstellungen anzeigen.
2. Link und neue Seite **Whisper-Einstellungen** mit gemeinsamer Statuszeile.
3. Sichere Whisper-Parameter persistent speichern und bis in die JNI-Schicht
   durchreichen.
4. Jede Einstellungskachel einzeln auf ihre Standardwerte zurücksetzen.
5. Dasselbe kachelweise Zurücksetzen auf **KI-Leistung und Hardware** ergänzen.
6. VAD und Parallelisierung ehrlich als derzeit technisch begrenzte Bereiche
   dokumentieren: VAD benötigt ein separates Modell; mehrere parallele Kontexte
   würden den Speicherbedarf vervielfachen.
7. Tests, Dokumentation, Android-/Native-Build und signierte APK.

## Prüfpunkte

- Sprache synchron auf Haupt- und Einstellungsseite
- Werte über App-Neustart erhalten
- Rücksetzen verändert nur die jeweilige Kachel
- Native Parameter werden validiert und angewendet
- Abschnittslänge verändert die vorhandene Chunkplanung
- bestehende JSON-, TXT- und SRT-Zeitstempel bleiben zuverlässig
