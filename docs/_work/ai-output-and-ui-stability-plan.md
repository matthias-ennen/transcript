# Arbeitsplan: KI-Ausgabe und Diagnoseoberflächen stabilisieren

Status: Implementiert; lokale Native-, Quellen- und Diffprüfung abgeschlossen.

## Ziel

Der nachgewiesene native Absturz bei ungültigen UTF-8-Bytefolgen wird zentral an
der JNI-Grenze behoben. Gleichzeitig werden die gemeinsam verwendete
CannaBot-Statuszeile, die KI-Diagnose und die aufklappbaren Bereiche der Seite
**KI-Leistung und Hardware** gemäß dem festgelegten Arbeitspaket beruhigt und
vereinheitlicht.

## Umsetzung

1. UTF-8-Ausgaben vor der Android-String-Erzeugung robust nach UTF-16
   konvertieren; ungültige oder unvollständige Sequenzen durch U+FFFD ersetzen.
2. Native Tests für ASCII, Umlaute, Emojis, Chinesisch, Arabisch sowie geteilte
   und fehlerhafte UTF-8-Sequenzen ergänzen.
3. Die gemeinsame CannaBot-Statuszeile für zwei Textzeilen reservieren und
   CannaBot sowie Text oben ausrichten.
4. Auf der KI-Diagnoseseite eine dauerhaft sichtbare, schreibgeschützte
   Antwortbox oberhalb der unveränderten Eingabebox anzeigen; alte
   Ein-/Ausblendelogik entfernen.
5. Leistungs-/Hardwaremeldungen ausschließlich über die gemeinsame
   CannaBot-Statuszeile ausgeben und keine zusätzliche Statusanzeige am
   Seitenende führen.
6. Die sechs aufklappbaren Leistungskacheln linksbündig beschriften und ihren
   Zustand einzeln dauerhaft speichern; beim ersten Start sind sie geschlossen.
7. Dokumentation aktualisieren, lokale Tests und Builds ausführen, Änderungen
   prüfen und anschließend über Pull Request in `main` integrieren.

## Prüfpunkte

- JNI-Konvertierung kann Android nicht mehr durch `NewStringUTF` mit ungültigen
  Bytes abbrechen lassen.
- Ein- und zweizeilige Statusmeldungen verändern die Höhe der Statuszeile nicht.
- Antwortbox ist immer sichtbar, nicht editierbar und bei langen Antworten
  scrollbar.
- Kachelzustände überleben Seitenwechsel und App-Neustart.
- Unit-Tests, Native-Build, KleidiAI-Payload, Signaturprüfung und APK-Upload sind
  in CI erfolgreich.
