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

Die operative Reihenfolge wird in [Issue #26](https://github.com/matthias-ennen/transcript/issues/26) gepflegt. Dieses Dokument übernimmt dieselbe Priorisierung.

### Abgeschlossen

- #25 – Statusdarstellung und Benachrichtigung
- #37 – Einstellungsseite: Modellverwaltungen vereinheitlichen
- #32 – Android-Import über „Teilen mit Transcript“
- #28 – Architektur-Refactoring ohne Funktionsänderung
- #29 – Hintergrunddienste und Download-Infrastruktur (PR #84, Build #400, manuell abgenommen)

### Direkt folgende Arbeitspakete

#### 6. Wiederholungsschleifen und Halluzinationen analysieren

[Issue #78](https://github.com/matthias-ennen/transcript/issues/78)

Anhand langer, reproduzierbarer Audio- oder Videodateien wird technisch unterschieden, ob Wiederholungen aus Whisper, der Abschnittsbildung, Überlappungen oder der Ergebniszusammenführung stammen. Eine mögliche Gegenmaßnahme wird danach separat entschieden.

#### 7. Einzel-Wiederholungsmodus für Transkriptsegmente

[Issue #77](https://github.com/matthias-ennen/transcript/issues/77)

Die Wiedergabe erhält einen nicht dauerhaft gespeicherten Umschalter, der das aktuelle Segment nach seinem Ende wiederholt. Beide vorhandenen Schaltflächenleisten zeigen denselben Zustand.

#### 8. Manuelle Nachbearbeitung und Speicherung des Transkripts

[Issue #73](https://github.com/matthias-ennen/transcript/issues/73)

Manuelle Änderungen, Übernehmen, Abbrechen, Rückkehr zum Original, Speicherung und Export des aktuellen Vorgangs werden zuverlässig abgesichert.

#### 9. KI-Antwortzeiten verkürzen

[Issue #61](https://github.com/matthias-ennen/transcript/issues/61)

Messbare Analyse und Beschleunigung der lokalen KI-Laufzeit als Grundlage für die KI-Nachbearbeitung.

#### 10. Lokalen Songmodus integrieren

[Issue #41](https://github.com/matthias-ennen/transcript/issues/41)

Lokale Gesangstrennung und drei Modellstufen für Songs; danach kann die KI-Nachbearbeitung beide Arbeitsarten einheitlich behandeln.

#### 11. Manuelle und automatische KI-Nachbearbeitung absichern

[Issue #30](https://github.com/matthias-ennen/transcript/issues/30)

Genau ein Zielsegment je Auftrag, kontrollierter Lesekontext und Schutzprüfungen für manuelle und automatische lokale KI-Korrektur.

#### 12. Whisper-Original und nachbearbeitete Fassung vergleichen

[Issue #31](https://github.com/matthias-ennen/transcript/issues/31)

Vergleich, Herkunftskennzeichnung und bewusste Auswahl der Fassung für Export und Teilen.

### Folge bis Version 1.0

13. #39 – Freiwillige Unterstützung mit getrennten Play- und Direct-Zahlungswegen  
14. #33 – Deutsche und englische Oberfläche, Barrierefreiheit und Displaytests  
15. #34 – Android-Plattform und Release-Pipeline modernisieren  
16. #51 – Rechtliche Projektseiten über GitHub Pages bereitstellen und in der App verlinken  
17. #27 – Testkorpus und Android-Geräte-Qualitätsgate als finales Qualitätsgate  
18. #35 – Repository-, Lizenz- und Store-Abschluss für Version 1.0

### Parallel oder außerhalb der direkten Folge

- #44 – Produktakte: Name, Positionierung und Store-Auftritt; kein eigenes technisches Arbeitspaket
- #46 – Statussymbole als vorbereitendes Designpaket vor #31
- #40 – Windows-Desktop-Version erst nach dem Android-Version-1.0-Abschluss

## Abhängigkeiten

```mermaid
flowchart TD
    I25["#25 Status"] --> I37["#37 Einstellungen"]\n    I37 --> I27["#27 Qualitätsgate"]
    I27 --> I28["#28 Architektur"]
    I28 --> I29["#29 Hintergrunddienste"]
    I29 --> I78["#78 Wiederholungen analysieren"]
    I78 --> I77["#77 Segment wiederholen"]
    I77 --> I73["#73 Manuelle Nachbearbeitung"]
    I73 --> I61["#61 KI-Antwortzeiten"]
    I61 --> I41["#41 Songmodus"]
    I41 --> I30["#30 KI-Zuverlässigkeit"]
    I30 --> I31["#31 Original/KI-Ansicht"]
    I31 --> I39["#39 Unterstützung"]
    I39 --> I33["#33 Lokalisierung"]
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
