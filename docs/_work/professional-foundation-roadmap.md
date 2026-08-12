# Professionelles Fundament – Bestandsanalyse und Roadmap

## Zweck und Status

Dieses Dokument ist das verbindliche Projektgedächtnis für die am 12.08.2026
erstellte Fundamentanalyse. Es trennt den langfristigen Professionalisierungsbedarf
vom nächsten, bewusst begrenzten Arbeitspaket bis zu einer erneut testbaren APK.

Geprüfter Ausgangspunkt:

- Remote-Branch `main`, Merge-Commit `baee3bf` aus PR #23
- vollständig grüner Reparatur-Build #205 mit signierter Release-APK und AAB
- lokale Verarbeitung; Android-Cloud-Backup und Gerätetransfer vollständig deaktiviert
- atomare Sicherung und Wiederherstellung des aktuellen Transkripts
- konservative Silero-VAD-Automatik mit Rückfall ohne VAD
- R8- und JNI-Absicherung für `onProgress(int)`
- rund 90 JVM-Tests, aber noch keine ausgeführten Android-Geräte-/Instrumentierungstests

Die Release-APK aus Build #205 ist der bekannte Vergleichsstand. Ein neuer Build
gilt erst dann als Fortschritt, wenn seine Prüfungen vollständig grün sind und das
Artefakt auf dem Xiaomi 13T Pro installiert und praktisch getestet werden kann.

## Langfristiger Professionalisierungsbedarf

Die App besitzt ein gutes technisches Grundgerüst, ist aber noch nicht vollständig
release- oder Store-reif. Die weitere Fundamentarbeit umfasst:

1. Decoder und Audio-Vorbereitung gegen Stillstand, fehlerhafte Codecs und
   endlose `MediaCodec`-Schleifen absichern.
2. Die VAD-Automatik ohne vollständige Doppeldekodierung der Audiodatei
   gestalten und alle Rückfallzustände eindeutig melden.
3. Release-, R8-, JNI- und echte Gerätetests mit einem festen Audio-Testkorpus
   aufbauen.
4. GitHub Actions in nachvollziehbare Qualitätsstufen teilen, Lint ergänzen,
   Laufzeiten begrenzen und Release-Artefakte dauerhaft versionieren.
5. `MainScreenViewModel`, große Compose-Screens, Services und native JNI-Dateien
   schrittweise in fachliche Komponenten zerlegen.
6. Aus dem einzelnen aktuellen Transkript später eine verwaltete lokale
   Transkriptbibliothek mit IDs, Metadaten, Versionen, Migration und Löschregeln
   entwickeln.
7. KI-Ausgaben vor der Übernahme anhand von Länge, Ähnlichkeit, Zahlen,
   Eigennamen und möglichen Auslassungen oder Erfindungen prüfen; Vergleich,
   Änderungsprotokoll und Rückgängig-Funktion ergänzen.
8. Aktives KI-Modell und lediglich bearbeitetes Leistungsprofil in der Oberfläche
   eindeutig voneinander trennen.
9. ARM64-Test-APK, getrennte Debug-App-ID, vollständige Ressourcenübersetzung,
   Barrierefreiheit und Displaytests ergänzen.
10. Eigene Lizenz, `SECURITY.md`, Changelog, Tags, GitHub Releases, SBOM und
    Abhängigkeitsprüfung einführen.

Diese Punkte bleiben erhalten, gehören aber ausdrücklich nicht alle in den
nächsten APK-Bau.

## Nächstes Arbeitspaket

### Name

**Decoder-, VAD- und Release-Qualitätsgate**

### Ziel

Die nächste APK soll den bekannten Release-Callback-Fix enthalten und bei der
Audio-Vorbereitung weder unendlich hängen noch einen unklaren Zustand anzeigen.
VAD darf den Lauf nicht blockieren oder unnötig die komplette Datei ein zweites
Mal dekodieren. Die Build-Pipeline muss den tatsächlich auszuliefernden
Release-Stand nachvollziehbar prüfen.

### Verbindlicher Umfang

#### A – Ausgangsstand sichern

- Aktuellen Remote-Stand von `main` einschließlich PR #23 synchronisieren.
- Den bekannten grünen Build #205 und seine Release-APK als Vergleichspunkt
  festhalten.
- Keine unabhängigen lokalen oder fremden Änderungen überschreiben.

#### B – Decoder-Watchdog und kontrollierte Wiederholung

- Fortschritt des `MediaCodec`-Decoders mit monotoner Zeit erfassen.
- Erfolgreiches Zuführen eines Eingabepuffers, Ausgabeformatwechsel und
  verarbeitete Ausgabepuffer gelten als echter Fortschritt.
- Aufeinanderfolgende Leerlaufzyklen und die Zeit seit dem letzten Fortschritt
  begrenzen.
- Bei Stillstand eine eigene, diagnostisch aussagekräftige Exception werfen,
  statt die Schleife unbegrenzt fortzusetzen.
- Codec und Extractor im Fehlerpfad sicher stoppen und freigeben.
- Den betroffenen Audioabschnitt genau einmal mit einem vollständig neuen
  Decoder versuchen. Ein zweiter Stillstand wird nicht endlos wiederholt.
- Erst danach greift, soweit sinnvoll, die vorhandene kleinere
  2,5-Minuten-Sicherheitsaufteilung. Jede Wiederholung besitzt ein festes Limit.
- Diagnose enthält Dateiformat, Abschnittsgrenzen, letzten Zeitstempel,
  Leerlaufdauer sowie Ein-/Ausgabeaktivität, jedoch keine Audioinhalte.

#### C – VAD-Voranalyse begrenzen und absichern

- Der Decoder-Watchdog gilt auch während der VAD-Automatik.
- Im Modus **Automatisch** wird nicht mehr grundsätzlich die komplette Audiospur
  vor der Transkription vollständig ein zweites Mal dekodiert.
- Stattdessen werden begrenzte, repräsentative Zeitfenster geplant. Nicht
  zusammenhängende Fenster dürfen bei der Pausenberechnung nicht fälschlich zu
  einer einzigen langen Pause verbunden werden.
- Die Entscheidung bleibt konservativ: Ein unvollständiges, widersprüchliches
  oder fehlgeschlagenes Ergebnis führt zu Whisper **ohne VAD**.
- Die technischen Zustände werden einheitlich geführt und gemeldet:
  `verwendet`, `übersprungen` oder `fehlgeschlagen – ohne VAD fortgesetzt`.
- Der bestehende Laufzeit-Rückfall eines Whisper-Abschnitts von VAD auf ohne VAD
  bleibt erhalten und wird nicht verdoppelt.

#### D – Status und Diagnose

- Die sichtbare Verarbeitung unterscheidet mindestens:
  Audiospur prüfen, Decoder starten, auf 16 kHz Mono umwandeln,
  VAD analysieren/überspringen, Whisper starten und Whisper-Fortschritt.
- Ein Decoder-Neustart und ein VAD-Rückfall werden ausdrücklich angezeigt.
- Fehlermeldungen nennen die konkrete Stufe und ob ein Zwischenstand fortgesetzt
  werden kann.
- Abbruch durch den Nutzer bleibt in jeder Stufe wirksam.

#### E – Automatisierte Prüfungen

- Reine Logik für Watchdog, Wiederholungslimit und VAD-Fensterplanung wird aus
  Android-Code herauslösbar und per JVM-Test geprüft.
- Tests decken mindestens normalen Fortschritt, Decoderstillstand, genau einen
  Neustart, endgültigen Fehler, Abbruch und konservativen VAD-Rückfall ab.
- Debug- und Release-Unit-Tests sowie `lintRelease` werden ausgeführt.
- Die minifizierte Release-Variante wird gebaut.
- Die Pipeline prüft zusätzlich, dass die JNI-Progressklasse und
  `onProgress(int)` nach R8 weiterhin unter dem erwarteten Namen erreichbar
  sind.
- Buildschritte erhalten klare Bezeichnungen und ein festes Zeitlimit; ein
  hängender Build darf nicht stundenlang ohne Ergebnis offenbleiben.
- Signatur, `debuggable=false`, `allowBackup=false`, native ARM64-Payload und
  APK/AAB-Upload bleiben Pflichtprüfungen.

#### F – APK-Bereitstellung und Gerätetest

- Änderungen in einem eigenen Branch committen und über einen Pull Request in
  `main` integrieren.
- Erst ein vollständig grüner Hauptbranch-Build erzeugt die neue Testfreigabe.
- Bereitgestellt werden die signierte Release-APK, Versionsnummer, Buildnummer
  und SHA-256-Prüfsumme.
- Anschließender Test auf dem Xiaomi 13T Pro:
  1. kurze eigene Aufnahme, die zuvor am Progress-Callback scheiterte,
  2. problematische Datei, die bei der 16-kHz-Vorbereitung hing,
  3. je ein Lauf mit VAD **Aus** und **Automatisch**,
  4. Abbruch während Decoder- und Whisper-Phase,
  5. Wiederaufnahme eines echten Zwischenstands,
  6. erfolgreicher TXT-, SRT- und JSON-Export.

### Nicht Bestandteil dieses APK-Pakets

- großes ViewModel-/UI-Refactoring
- Transkriptbibliothek für mehrere Projekte
- neue Whisper- oder Qwen-Modelle
- Erweiterung der KI-Nachbearbeitung
- vollständige deutsche/englische Lokalisierung
- Store-Veröffentlichung, Lizenzentscheidung oder GitHub-Release-Automatik
- allgemeine optische Überarbeitung

Diese Abgrenzung verhindert, dass die Stabilitätsprüfung durch neue Funktionen
verwässert wird.

## Abnahmekriterien bis zur nächsten APK

Das Arbeitspaket ist erst abgeschlossen, wenn:

- Decoderstillstand begrenzt erkannt und verständlich behandelt wird,
- kein Wiederholungs- oder VAD-Pfad unbegrenzt laufen kann,
- die automatische VAD-Voranalyse nicht mehr die komplette Datei zwingend
  doppelt dekodiert,
- die Release-Callback-Bridge weiterhin R8-sicher ist,
- alle neuen und vorhandenen Tests, `lintRelease`, Release-Build, Signatur- und
  Datenschutzprüfungen grün sind,
- der entsprechende Stand in `main` liegt,
- eine signierte Release-APK mit Prüfsumme herunterladbar ist.

Der Xiaomi-Test ist anschließend der verbindliche Praxistest. Erst seine
Ergebnisse entscheiden, ob das Qualitätsgate bestanden ist oder eine gezielte
Korrekturrunde vor dem nächsten Fundamentpaket nötig wird.

## Danach vorgesehene Reihenfolge

1. Architektur-Refactoring in kleinen, vorher dokumentierten Patches.
2. Daten- und KI-Sicherheit einschließlich Transkriptbibliothek und Undo.
3. Produktions-/Store-Vorbereitung mit ARM64-Artefakt, Versionierung,
   Lokalisierung, Barrierefreiheit und Repository-Standards.
