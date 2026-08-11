# Arbeitsplan: Silero VAD

## Ziel

Silero VAD 6.2.0 wird als optionales lokales Zusatzmodell in die vorhandene
Whisper-Pipeline integriert. Die App verwaltet Download, Prüfsumme, Speicher und
Löschen zentral. Ohne vollständig installiertes Modell bleibt die bisherige
Whisper-Verarbeitung unverändert verfügbar.

## Umsetzung

1. Eigenständiges VAD-Modell- und Downloadmodul mit fortsetzbarem Download,
   SHA-256-Prüfung und verständlichem Status ergänzen.
2. Eine Kachel **Silero VAD** zwischen Whisper-Modellverwaltung und
   KI-Nachbearbeitung einfügen.
3. Persistente VAD-Einstellungen (Modus, Schwelle, Mindestsprachdauer,
   Mindestpause, maximale Sprachdauer, Randabstand und Überlappung) ergänzen.
4. Den installierten Modellpfad über Service, Kotlin-Bibliothek und JNI an die
   native `whisper.cpp`-VAD-Pipeline übergeben.
5. Bei fehlendem/ungültigem VAD-Modell kontrolliert ohne VAD arbeiten.
6. Zeitstempel weiterhin aus `whisper.cpp` beziehen; dort werden VAD-Zeiten
   bereits auf die ursprüngliche Audiodatei zurückgerechnet.
7. Unit-Tests, Android-/NDK-Build, Dokumentation, Lizenzhinweis und signierte APK
   prüfen.

## Prüfpunkte

- Kein Einfluss auf Transkriptionen bei VAD **Aus** oder fehlendem Modell.
- Downloadabbruch bleibt als fortsetzbare `.part`-Datei erhalten.
- Löschen entfernt vollständige und unvollständige VAD-Dateien.
- Einstellungswerte werden begrenzt und kachelweise zurückgesetzt.
- JSON/SRT behalten absolute Originalzeitstempel.
