# Arbeitsplan: GUI-Sprachauswahl

Stand: 8. August 2026

Status: Auswahl vorbereitet; vollständige Lokalisierung folgt separat.

## Ziel

Links neben Einstellungen und Info erhält die Hauptseite einen kompakten
Sprachumschalter für Deutsch und Englisch. Die Auswahl wird im privaten
App-Speicher dauerhaft gespeichert. In diesem Arbeitsschritt werden die
vorhandenen GUI-Texte noch nicht übersetzt.

## Umsetzung

1. `AppLanguage` definiert die beiden GUI-Sprachen unabhängig von der
   Whisper-Sprache der Transkription.
2. `AppLanguagePreference` lädt und speichert die Auswahl.
3. `AppLanguageSelector` zeigt die deutsche oder britische Flagge und öffnet
   ein Menü mit beiden Sprachen.
4. Eine unbekannte oder noch nicht gespeicherte Auswahl fällt sicher auf
   Deutsch zurück.
5. Sprachwahl, Einstellungen und Info bilden eine leicht verdichtete Gruppe;
   die einzelnen Schaltflächen bleiben klar getrennt und gut antippbar.

## Spätere vollständige Lokalisierung

Alle fest eingebauten Texte werden in Android-Stringressourcen überführt. Dazu
gehören Hauptseite, Einstellungen, Info, Statusmeldungen, Fehlertexte, Dialoge,
Systembenachrichtigungen, Aufnahmesteuerung, Modelldownload, Transkription,
Bearbeitung und Export. Dynamische Diagnosedaten und Transkriptinhalte werden
nicht übersetzt.
