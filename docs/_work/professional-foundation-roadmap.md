# Professionelles Fundament und Roadmap bis Version 1.0

## Zweck und Status

Dieses Dokument ist das verbindliche Projektgedächtnis für die weitere Entwicklung
von Transcript. Die operative Gesamtübersicht liegt in
[Issue #26](https://github.com/matthias-ennen/transcript/issues/26).
Jedes Arbeitspaket besitzt ein eigenes GitHub-Issue und wird vor der Umsetzung
gemeinsam geprüft. Ein Issue ist eine Planungsgrundlage, keine automatische
Freigabe zur Programmierung.

Geprüfter Ausgangspunkt am 12.08.2026:

- `main`, Merge-Commit `d41542a` aus PR #24
- signierte Release-APK `0.5.217-signed`, VersionCode `1217`
- Hauptbranch-Build #217 vollständig grün
- Decoder-Watchdog, genau ein kontrollierter Decoder-Neustart und begrenzte
  Rückfallpfade
- vollständige Silero-VAD-Voranalyse bleibt bewusst erhalten
- Debug- und Release-Unit-Tests, `lintRelease`, R8-/JNI-Callback-, Native-
  Payload-, Signatur- und Datenschutzprüfungen
- 96 JVM-Tests; noch keine Android-Instrumentierungstests
- Android-Cloud-Backup und Geräteübertragung vollständig deaktiviert

## Produktgrenze

Transcript bleibt bewusst ein Werkzeug für **einen aktuellen
Transkriptionsvorgang**:

1. Audio- oder Videodatei auswählen oder aufnehmen.
2. Lokal mit Whisper transkribieren.
3. Ergebnis prüfen und optional manuell oder per KI nachbearbeiten.
4. Gewünschte Fassung exportieren oder teilen.
5. Ein neuer Vorgang darf den bisherigen aktuellen Vorgang ersetzen.

Eine Bibliothek, Historie oder dauerhafte Verwaltung früherer Transkripte ist
nicht vorgesehen.

Für den aktuellen Vorgang werden zwei getrennte Fassungen erhalten:

- das unveränderliche Whisper-Original
- die nachbearbeitete Fassung aus übernommenen manuellen und/oder KI-Änderungen

## Verbindliche Begriffe zur KI-Nachbearbeitung

**Manuelle KI-Nachbearbeitung** bedeutet: Der Benutzer startet die
KI-Nachbearbeitung über den Button eines einzelnen Abschnitts. Nur dieser
ausgewählte Abschnitt wird gezielt bearbeitet.

**Automatische KI-Nachbearbeitung** bedeutet: Nach Fertigstellung der gesamten
Whisper-Transkription wird automatisch das vollständige Transkript
abschnittsweise nachbearbeitet.

Beide Abläufe werden in Issue #30 zuverlässig abgesichert. Die sichtbare
Umschaltung und Herkunftskennzeichnung der beiden Transkriptfassungen folgt
nachgelagert in Issue #31.

## Bereits abgeschlossene Fundamentarbeit

Die frühere Arbeitseinheit „Decoder-, VAD- und Release-Qualitätsgate“ wurde mit
PR #24 und Build #217 abgeschlossen:

- Decoderstillstand wird zeitlich und über Leerlaufzyklen begrenzt erkannt.
- Codec und Extractor werden im Fehlerfall sicher freigegeben.
- Ein festgefahrener Abschnitt erhält genau einen vollständigen Decoder-Neustart.
- Weitere 2,5-Minuten-Rückfälle bleiben ebenfalls begrenzt.
- Derselbe Watchdog schützt die vollständige VAD-Voranalyse.
- Grenzfälle und VAD-Fehler führen kontrolliert zu Whisper ohne VAD.
- Die minifizierte Release-APK wird direkt auf die JNI-signifikante
  `onProgress(I)V`-Methode geprüft.
- Signatur, `debuggable=false`, `allowBackup=false` und native Backends
  bleiben verbindliche Release-Prüfungen.

Diese Punkte sind kein offenes Arbeitspaket mehr.

Die Hintergrunddienste und die Download-Infrastruktur wurden mit PR #84 nach
erfolgreichem GitHub-Actions-Build #400 und manueller Abnahme am 18.08.2026
abgeschlossen. Downloadvorgänge besitzen nun einen gemeinsamen, prüfsummen-
gesicherten Ablauf, eindeutige Benachrichtigungen und einen sicheren Umgang mit
unzureichendem Speicherplatz.

## Roadmap und Reihenfolge

### 1. Statusdarstellung und Benachrichtigung

[Issue #25](https://github.com/matthias-ennen/transcript/issues/25)

Korrektes Transkript-Symbol, Laufzeit in der KanaBot-Statuszeile, ruhiger Wechsel
zwischen Status und Laufzeit. Nur der Text pulsiert; KanaBots bestehende
Sprite-Sheet-Animation bleibt unverändert erhalten.

Ergebnis: signierte Test-APK und Xiaomi-Prüfung.

### 2. Einstellungsseite: Modellverwaltungen vereinheitlichen

[Issue #37](https://github.com/matthias-ennen/transcript/issues/37)

Whisper-, Silero-VAD- und lokale KI-Modellkarten erhalten dieselbe
Informationshierarchie und Auswahlhervorhebung. Whisper zeigt kurze
Modellbeschreibungen und einen Empfehlungstext; Silero VAD erhält eine
vollständige Modellkarte; alle KI-Modelle können gemeinsam gelöscht werden.

Ergebnis: einheitliche Modellverwaltung in der signierten Test-APK.

### 3. Testkorpus und Android-Geräte-Qualitätsgate

[Issue #27](https://github.com/matthias-ennen/transcript/issues/27)

Reproduzierbarer Audio-/Video-Testkorpus, Release-Smoke- und erste
Instrumentierungstests sowie ein Xiaomi-Protokoll für Laufzeit, Speicher, Wärme,
Auslassungen, Zeitstempel, VAD, Abbruch und Wiederaufnahme.

Ergebnis: belastbarer Referenzstand für alle folgenden Änderungen.

### 4. Architektur-Refactoring ohne Funktionsänderung

[Issue #28](https://github.com/matthias-ennen/transcript/issues/28)

`MainScreenViewModel`, große Compose-Screens und Services werden schrittweise
nach fachlichen Verantwortlichkeiten aufgeteilt. Oberfläche, Einstellungen,
Transkriptions- und Exportverhalten bleiben unverändert.

Ergebnis: wartbarer Unterbau in kleinen, einzeln prüfbaren Pull Requests.

### 5. Hintergrunddienste und Download-Infrastruktur — abgeschlossen am 18.08.2026

[Issue #29](https://github.com/matthias-ennen/transcript/issues/29)

Eindeutige Notification-IDs, gemeinsame Symbol- und Benachrichtigungsverwaltung,
einheitliche Downloadbasis sowie robuste Fortsetzungs-, Fehler- und
Bereinigungspfade für Whisper-, VAD- und Qwen-Modelle.

Ergebnis: konfliktfreie und nachvollziehbare Hintergrundvorgänge; bei
unzureichendem Speicher wird kein Download gestartet, keine Datei gelöscht und
eine CannaBot-Sprechblase mit der Schaltfläche **Okay** angezeigt.

### 6. Manuelle und automatische KI-Nachbearbeitung

[Issue #30](https://github.com/matthias-ennen/transcript/issues/30)

Genau ein Zielsegment pro Auftrag; zwei vorherige und zwei nachfolgende Segmente
nur als Lesekontext. Längen-, Ähnlichkeits-, Zahlen-, Namen-, Auslassungs- und
Erfindungsprüfungen schützen vor unsicheren Übernahmen. Manuelle Einzelabschnitt-
und automatische Gesamttranskript-Nachbearbeitung werden eindeutig getrennt.

Ergebnis: zuverlässige, abbrechbare und nachvollziehbare lokale KI-Korrektur.

### 7. Whisper-Original und nachbearbeitete Fassung vergleichen

[Issue #31](https://github.com/matthias-ennen/transcript/issues/31)

Umschaltung zwischen Whisper-Original und nachbearbeiteter Fassung,
Herkunftskennzeichnung bearbeiteter Segmente und bewusste Auswahl der Fassung
für Export und Teilen. Die genaue Bedienung und Symbolgestaltung werden vor der
Implementierung gemeinsam festgelegt. Vorgesehene Gestaltungsrichtung:
Textdatei für Whisper, Textdatei mit Zauberstab und Sternen für KI.

Ergebnis: transparente Kontrolle ohne Transkriptbibliothek.

### 8. Android-Import über „Teilen mit Transcript“

[Issue #32](https://github.com/matthias-ennen/transcript/issues/32)

Unterstützte Audio- und Videodateien können aus Dateimanager, Galerie oder einer
anderen App direkt an Transcript übergeben werden. MIME-Filter,
URI-Berechtigungen und der Schutz des aktuellen Vorgangs werden abgesichert.

Ergebnis: vollständiger Android-Dateiarbeitsablauf für eine einzelne Datei.

### 9. Deutsche und englische Oberfläche, Barrierefreiheit und Displaytests

[Issue #33](https://github.com/matthias-ennen/transcript/issues/33)

Alle sichtbaren Texte werden in Ressourcen überführt und vollständig auf Deutsch
und Englisch angeboten. TalkBack, große Schrift, Touch-Ziele, Kontrast und
verschiedene Displaygrößen werden geprüft. Die GUI-Sprache bleibt unabhängig von
der Whisper-Sprache.

Ergebnis: vollständige, verständliche Produktoberfläche.

### 10. Android-Plattform und Release-Pipeline modernisieren

[Issue #34](https://github.com/matthias-ennen/transcript/issues/34)

Umstellung auf targetSdk/API 36, kontrollierte Werkzeugaktualisierung, getrennte
Debug-App-ID, ARM64-Test-APK, Release-AAB, Instrumentierungstests in CI,
versionierte Native-Patches und archivierte Mapping-/Symbols-Dateien.

Ergebnis: aktueller und reproduzierbarer Release-Kandidat.

### 11. Repository-, Lizenz- und Store-Abschluss

[Issue #35](https://github.com/matthias-ennen/transcript/issues/35)

Lizenz, Sicherheitsdokument, Changelog, SBOM, GitHub Release,
Datenschutzerklärung, Play-Store-Angaben, Screenshots sowie interner und
geschlossener Test. Eine Donate-/Unterstützungsfunktion wird gesondert auf
Store-Konformität geprüft und nicht ungeprüft aktiviert.

Ergebnis: menschlich freigegebener Version-1.0-Release-Kandidat.

## Abhängigkeiten

```mermaid
flowchart TD
    I25["#25 Status"] --> I37["#37 Einstellungen"]\n    I37 --> I27["#27 Qualitätsgate"]
    I27 --> I28["#28 Architektur"]
    I28 --> I29["#29 Hintergrunddienste"]
    I28 --> I30["#30 KI-Zuverlässigkeit"]
    I30 --> I31["#31 Original/KI-Ansicht"]
    I28 --> I32["#32 Android-Import"]
    I31 --> I33["#33 Lokalisierung"]
    I32 --> I33
    I29 --> I34["#34 Plattform/CI"]
    I33 --> I34
    I34 --> I35["#35 Version 1.0"]
```

Die Reihenfolge ist eine belastbare Planung, kein starres Verbot kleiner
Korrekturen. Eine Abweichung wird im Tracking-Issue #26 begründet und darf keine
ungetestete Vermischung unabhängiger Arbeitspakete verursachen.

## Einheitliche Issue-Struktur

Jedes Arbeitspaket dokumentiert:

1. Ausgangslage
2. Ziel
3. verbindlichen Umfang
4. ausdrücklich nicht enthaltene Punkte
5. Abhängigkeiten
6. geplante Prüfungen
7. Abnahmekriterien
8. erforderlichen Gerätetest
9. offene Produktentscheidungen

Vor Beginn werden Ergänzungen und Streichungen direkt im Issue festgehalten.
Branch und Pull Request verweisen auf das Issue. Geschlossen wird ein Issue erst
nach erfüllten Abnahmekriterien; ein benötigter Xiaomi-Praxistest wird dabei
ausdrücklich ausgewiesen.

## Definition von Version 1.0

Version 1.0 ist erreicht, wenn:

- alle für 1.0 freigegebenen Issues abgeschlossen sind,
- Whisper-Original und Nachbearbeitung sicher getrennt bleiben,
- der Testkorpus ohne blockierende Fehler bestanden ist,
- Release-Tests, Lint, R8/JNI, Signatur und Datenschutz grün sind,
- die App targetSdk 36 erfüllt,
- der signierte Release-Kandidat auf dem Xiaomi 13T Pro und mindestens einer
  weiteren Android-Konfiguration praktisch geprüft ist,
- Lizenz-, Sicherheits-, Datenschutz- und Release-Dokumentation vollständig ist,
- die Veröffentlichung ausdrücklich menschlich freigegeben wurde.
