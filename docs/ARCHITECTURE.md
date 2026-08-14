# Architektur von Simple Transcript

## Zielbild

Simple Transcript ist eine lokale Android-App. Audio- und Videodateien werden
auf dem Gerät dekodiert und mit `whisper.cpp` transkribiert. Optional glättet
Qwen3.5 das Ergebnis lokal über `llama.cpp`. Nur Modelldownloads benötigen eine
Internetverbindung.

## Module

- `app`: Oberfläche, Medienauswahl, Aufnahme, Wiedergabe, Modelldownload,
  Statussteuerung und Export
- `lib`: Kotlin-/JNI-Brücke zu `whisper.cpp` und nativer CMake-Build
- `llm`: kleine Kotlin-/JNI-Brücke zu `llama.cpp` für lokale GGUF-Inferenz
- `third_party/whisper.cpp`: als Git-Submodul eingebundene Inferenzbibliothek
- `third_party/llama.cpp`: fest gepinnte lokale LLM-Inferenzbibliothek

## Datenfluss

1. `MainScreenViewModel` hält den zentralen `TranscriptUiState`.
   `RecordingService` besitzt die laufende Mikrofonaufnahme unabhängig von der
   Activity, hält dafür einen begrenzten Partial-Wakelock und veröffentlicht
   Pegel, Laufzeit, Abschluss oder Fehler über `RecordingCoordinator`.
2. Die Transkriptionsvorbereitung verdichtet jeden dekodierten PCM-Abschnitt
   unmittelbar auf 180 Spitzenwerte und schreibt beides atomar. Beim bloßen
   Wiederöffnen werden zuerst diese Cachewerte gelesen; ein aktiver Worker wird
   niemals durch einen zweiten UI-Decoder konkurriert.
3. `TranscriptionService` läuft im privaten Android-Prozess `:transcription`,
   plant Ein- bis Fünf-Minuten-Hauptabschnitte mit je zwei Sekunden Kontextüberlappung
   und arbeitet unabhängig von Activity und UI-HWUI.
4. `AndroidAudioDecoder` dekodiert ausschließlich den aktuellen Bereich und
   resampelt MediaCodec-Ausgabepuffer unmittelbar auf 16-kHz-Mono-PCM. PCM in
   der ursprünglichen Abtastrate wird nicht gesammelt. Ein fester
   Fünf-Sekunden-Sicherheitsspielraum fängt Codec-Vorlauf, Padding und
   Zeitstempelrundungen auf; vor Whisper wird der Abschnitt wieder exakt auf
   seine Sollgröße begrenzt. Ein monotoner Stillstandswächter begrenzt sowohl
   Leerlaufzeit als auch Leerlaufzyklen. Bei Stillstand werden Codec und
   Extractor vollständig freigegeben und genau einmal neu erzeugt.
5. Decoder und Whisper-Modell werden strikt zweiphasig verwendet: Alle Abschnitte
   werden zuerst einzeln dekodiert, als PCM16LE gesichert und aus dem RAM
   freigegeben. Danach wird `WhisperContext` genau einmal geladen und über alle
   vorbereiteten Abschnitte wiederverwendet. Bei automatischer Auswahl wird eine anhand
   eines brauchbaren Textabschnitts erkannte Sprache für die folgenden
   Abschnitte festgehalten.
6. `TranscriptionChunking` verschiebt lokale Segmentzeiten auf die absolute
   Audioposition und ordnet Überlappungssegmente über ihren Mittelpunkt genau
   einem Hauptbereich zu.
7. `TranscriptionCheckpointStore` schreibt Segmente, Sprache und nächste
   Position nach jedem fertigen Abschnitt atomar in den privaten App-Speicher.
8. `TranscriptResultStore` hält nach Abschluss das unveränderte Whisper-Original
   und den zuletzt übernommenen Anzeige-/Exportstand getrennt in einer atomar
   ersetzten Datei. `TranscriptResultPersistence` serialisiert Schreibvorgänge
   außerhalb des Compose-Hauptthreads.
9. `TranscriptionCoordinator` übergibt Fortschritt, Diagnose und Teilergebnisse
   prozessübergreifend über eine atomare Statusdatei und ein paketinternes
   Wecksignal an jedes aktive `MainScreenViewModel`. Große Audio- oder
   Modellobjekte werden nie über Binder oder Intent transportiert. Ab Android 11
   erkennt die UI einen nativen Worker-Absturz über `ApplicationExitInfo`, bleibt
   selbst geöffnet und bietet einen gesicherten Zwischenstand zur Fortsetzung an.
   Ein Fortschritts-Watchdog fordert nach drei Minuten Stillstand über den
   separaten Prozess `:control` genau einen CPU-Neustart an. Der gleiche Prozess
   setzt einen Benutzerabbruch notfalls per Prozessende durch, ohne einen bereits
   beendeten Worker durch den Benachrichtigungs-Knopf neu zu starten.
10. `TranscriptTimeline` ergänzt aus Whisper-Original und Audiodauer einmalig
   Anfangs-, Zwischen- und Endpausen. Lücken ab einer Sekunde erhalten leere,
   editierbare Pausensegmente; kürzere technische Abstände werden nur für die
   Anzeige an Nachbarsegmente angelegt. Die separaten Whisper-Rohzeitstempel
   bleiben unverändert.
11. Die Ergebnisansicht stellt jedes Whisper-Segment mit Zeitstempel und
   GUI-Nummer dar. Pausenbereiche besitzen keine Whisper-Nummer.
   Eine 52 × 32 dp große, abgerundete Nummernkapsel hält auch drei- und
   vierstellige Nummern vollständig sichtbar.
12. Der Korrekturmodus hält Änderungen zunächst in `draftSegments`. Erst
   **Änderungen übernehmen** ersetzt die Ergebnis-Segmente; Zeitstempel und
   Reihenfolge bleiben erhalten.
13. `AiPostProcessingService` lädt nach vollständiger Freigabe des Whisper-
    Kontexts genau ein ausgewähltes Qwen3.5-GGUF. Stabile Segmentmarker sichern
    Anzahl, Reihenfolge und Zeitstempel. Automatische Läufe übernehmen validierte
    Gruppen direkt; manuelle Läufe schreiben nur in `draftSegments`.
14. `TranscriptExport` schreibt die vollständige Zeitleiste einschließlich
    Herkunft in JSON. Leere Pausen werden aus TXT und SRT herausgefiltert;
    manuell befüllte Pausen erscheinen regulär in allen Formaten.
15. `TranscriptShare` schreibt die ausgewählten Formate in einen privaten
    Cache-Unterordner. Ein nicht exportierter `FileProvider` gibt ausschließlich
    diese Dateien mit zeitlich begrenztem Leserecht an das Android-Teilen-Menü
    weiter. Ein Format verwendet `ACTION_SEND`, mehrere Formate verwenden
    `ACTION_SEND_MULTIPLE`.
16. Lange Transkripte blenden abhängig von Segmentanzahl und Scrollposition eine
    schwebende Navigationskapsel ein. Sie verwendet denselben Scrollzustand wie
    die gesamte Hauptansicht und führt deshalb bis an den Anfang der App zurück.

## GUI-Sprache

`AppLanguage` hält die von der Whisper-Transkriptionssprache unabhängige
GUI-Sprachauswahl. Der Umschalter in der Kopfleiste bietet Deutsch und Englisch
an; `AppLanguagePreference` speichert die Auswahl dauerhaft. Die vollständige
Umstellung aller sichtbaren Texte auf Android-Stringressourcen erfolgt in einem
separaten Lokalisierungsschritt.

Die von Whisper erkannte Transkriptionssprache bleibt davon unabhängig. Die
Ergebnisansicht löst sämtliche Whisper-Sprachcodes über
`WhisperLanguageNames` in vollständige deutsche Bezeichnungen auf. Unbekannte
Codes werden weiterhin sichtbar mit ihrem Code ausgegeben.

## Status- und Animationssteuerung

Die sichtbare Statuszeile verbindet Text und CannaBot. Dauerzustände sind
`IDLE`, `WAITING`, `REVIEW` und `RUNNING`. Kurze Ereignisse verwenden
`RUNNING_RIGHT`, `RUNNING_LEFT`, `JUMPING`, `WAVING` und `FAILED`. Eine
Erfolgssequenz spielt Springen und Winken nacheinander ab und kehrt anschließend
zum Grundzustand zurück. Fortschrittsereignisse werden nur an festgelegten
Meilensteinen ausgelöst.

`LiveStatusLine` reserviert unabhängig vom aktuellen Text mindestens zwei
Textzeilen und richtet Sprite sowie Text oben aus. Ein optionaler Seitenstatus
wird in derselben pulsierenden Wechselanzeige dargestellt; dadurch benötigt die
Leistungsseite keine zweite Statusausgabe.

Die Seite `WhisperSettingsScreen` bearbeitet eine gemeinsame, in
`WhisperSettingsPreferences` gespeicherte Konfiguration. Die Sprachauswahl ist
identisch mit der Hauptseite. Threads, CPU-/GPU-Wahl, Vorgabetext,
Dekodierungsverfahren, Suchbreite, Temperatur, Kontext, Segmentierung,
Zeitstempelberechnung und Halluzinationsschwellen werden validiert und über
`WhisperConfiguration` bis in `whisper_full_params` durchgereicht. Die vorhandene
sequenzielle Abschnittsplanung verwendet ausschließlich ganze Dauern von einer bis
fünf Minuten, standardmäßig drei. VAD besitzt eine eigene Einstellungsseite; die vier erweiterten
Einstellungsseiten sind zusätzlich über den anklickbaren Seitentitel erreichbar.
Das optionale Silero-VAD-Modell wird getrennt unter `vad-models/` verwaltet. Nur
ein vollständig installiertes Modell wird zusammen mit den persistierten
VAD-Parametern bis in `whisper_full_params` durchgereicht. **Aus** deaktiviert
VAD, **Ein** aktiviert es und **Automatisch** führt vor dem Laden von Whisper
eine abschnittsweise Analyse der bereits vorbereiteten PCM-Dateien mit dem echten Silero-Kontext durch.
Die vollständige Audiospur bleibt dabei bewusst die Entscheidungsgrundlage;
eine Stichprobenanalyse wird wegen möglicher Fehlgewichtung nicht verwendet.
Eine zweite Dekodierung für VAD findet nicht statt.
Sie wandelt die von `whisper.cpp` gelieferten VAD-Zeitstempel an der JNI-Grenze
explizit von Zentisekunden in Millisekunden um und verwirft ungültige Paare.
Anschließend aggregiert sie erkannte Sprachbereiche, Sprach-/Pausenanteil, die
längste Pause und Zerstückelung über Abschnittsgrenzen. VAD wird nur bei
eindeutigen längeren Ruhephasen und stabilen Sprachbereichen verwendet. Ein hoher
Pausenanteil allein ist kein Ablehnungsgrund; Grenzfälle mit zu wenig oder stark
fragmentierter Sprache bleiben vollständig bei Whisper. Die Diagnose nennt auch
die analysierte Samplezahl und die davon erkannten Sprach-Samples. Die
integrierte `whisper.cpp`-Pipeline entfernt Nicht-Sprachbereiche für die
Berechnung und bildet Segmentzeitstempel anschließend wieder auf die
Originalaudiodatei ab. Bei fehlendem Modell oder VAD-Laufzeitfehler bleibt
beziehungsweise wechselt die Verarbeitung auf Whisper ohne VAD; parallele
Modellkontexte werden zum Schutz des Arbeitsspeichers nicht erzeugt.

Der Teilen-Dialog besitzt eine eigene, nur beim Öffnen gestartete Sequenz aus
Rechtslauf, Sprung und Winken. Kurze Idle-Pausen trennen die Gesten; anschließend
bleibt CannaBot ruhig im Idle-Zustand.

Im Zustand `REVIEW` ergänzt `TranscriptionTimeEstimate` die unveränderte
Bereitschaftsmeldung um eine kalibrierte Laufzeitschätzung. Die Statuszeile
wechselt am 20-Prozent-Punkt der Pulsation zwischen beiden Texten. Pro
Whisper-Modell liegt ein zentraler, anhand der Messreihen auf dem Zielgerät
festgelegter Echtzeitfaktor vor.

Während einer Transkription liefert `TranscriptionService` die verbindliche
Startzeit. `MainScreenViewModel` berechnet daraus in einem eigenen Sekundentakt
die sichtbare Laufzeit. Dieser Takt ist unabhängig von Decoder- und
Whisper-Fortschrittsmeldungen und wird nach einem erneuten Öffnen anhand der
Startzeit wieder aufgenommen. Neben der echten Laufzeit bleibt die vor dem Lauf
berechnete Gesamtschätzung sichtbar.

Native Fortschrittscallbacks werden für Compose auf höchstens zwei Aktualisierungen
pro Sekunde und für Android-Benachrichtigungen auf höchstens eine Aktualisierung
in zwei Sekunden verdichtet. Identische Zustände werden nicht erneut publiziert.
Während Whisper- oder Qwen-Inferenz bleibt das CannaBot-Sprite auf einem ruhenden
Frame, damit UI-HWUI und Compute-Vulkan nicht fortlaufend um die Mali-GPU konkurrieren.

## Modelle und Speicherung

Der zentrale `WhisperModel`-Katalog enthält fünf mehrsprachige Qualitätsstufen
von **Sehr schnell** (`ggml-tiny.bin`) bis **Maximale Qualität**. Sämtliche
Modelle durchlaufen denselben Auswahl-, Download-, Prüfsummen-, Speicher-,
Lösch- und Transkriptionspfad; Tiny benötigt keine Sonderbehandlung.

Modelle, Aufnahmen und Transkriptionszwischenstände liegen im privaten
App-Speicher. Modelldownload, Mikrofonaufnahme und Transkription besitzen getrennte
Foreground-Services, getrennte Zustandskoordinatoren und getrennte
Fehlerbehandlung. Downloads können über `.part`-Dateien fortgesetzt werden und
werden vor der Aktivierung per SHA-256 geprüft. Modelle werden nicht in die APK
aufgenommen. Ein bewusster Transkriptionsabbruch und eine Prozessunterbrechung
lassen den kompatiblen Zwischenstand für die Wiederaufnahme bestehen.

Der getrennte `AiModel`-Katalog enthält Qwen3.5 mit 0,8B, 2B und 4B Parametern.
Auswahl, Download, SHA-256-Prüfung und Löschen liegen ausschließlich in den
Einstellungen. `AiPostProcessingService` speichert seinen Gruppenfortschritt
atomar und verwendet nie gleichzeitig Speicher mit einem aktiven Whisper-Kontext.
`android:allowBackup=false` und vollständige Ausschlussregeln deaktivieren
Cloud-Backup sowie Gerätetransfer für alle App-Daten. Modelle, Aufnahmen,
Zwischenstände, fertige Transkripte und Einstellungen verlassen diesen Speicher
nicht über Android Backup.

Eine KI-Korrektursitzung dekodiert die vollständige aktive Fünf-Minuten-Gruppe
einmal als schreibgeschützten Whisper-Rohkontext. Für jedes Segment wird der native
KV-Kontext auf genau diesen gemeinsamen Ausgangszustand zurückgesetzt und nur die
kleine Zielaufgabe ergänzt. So werden Kontextkosten nicht wiederholt und frühere
Modellantworten können die nächste Prüfung nicht beeinflussen.

Der `llama.cpp`-Grammatik-Sampler erzwingt für Korrekturen genau ein JSON-Feld
`result`; Segmentnummern und Zeitstempel bleiben vollständig in der App. Die erste
Erprobungsstufe verwirft inhaltlich nur leere beziehungsweise nicht auslesbare
Ergebnisse und behält dann das Original. Längen-, Ähnlichkeits- und
Fremdkontextprüfungen sind bewusst noch nicht aktiviert. Der freie KI-Testbereich
verwendet einen getrennten, unbeschränkten Antwortpfad und übernimmt keine
Korrekturregeln.

`AiDiagnosticsScreen` ist eine dauerhafte Unterseite, die ausschließlich aus dem
KI-Bereich der Einstellungen geöffnet wird. Sie verwendet dieselbe
`LiveStatusLine`-Komponente wie die Hauptseite, enthält den freien KI-Testbereich
und zeigt das allgemeine Diagnoseprotokoll am Seitenende. Testbereich und
Protokoll sind dadurch nicht mehr Teil der Hauptseite. Der kapselförmige
**Verlassen**-Button führt zurück zu den Einstellungen.
Die schreibgeschützte Antwortbox ist dauerhaft oberhalb der Eingabebox sichtbar
und begrenzt lange Antworten auf einen intern scrollbar dargestellten Bereich.

`AiPerformanceScreen` ist die zweite dauerhafte KI-Unterseite. Sie verwendet
ebenfalls `LiveStatusLine` und gliedert Modellprofil, Kontext/Speicher,
CPU-Threadpool, KleidiAI, Vulkan, Wärmeschutz, Benchmark und Datenaustausch in
aufklappbare Karten. `AiPerformancePreferences` persistiert genau ein
versioniertes `LocalAiConfiguration` je `AiModel`; ältere zusätzliche
Arbeitsprofile werden bei der Migration entfernt. Der Laufzeitschlüssel dieser Konfiguration
ist Bestandteil des Sitzungsschlüssels. Eine Änderung an nativen Parametern
schließt deshalb eine unpassende bestehende Sitzung, bevor sie erneut geladen
wird.
`AiPerformanceUiPreferences` speichert davon getrennt den Auf-/Zu-Zustand jeder
der sechs Einstellungskarten. Die Profil-/Hardwarekarte bleibt dauerhaft offen.

Alle nativen Textausgaben durchlaufen vor `JNIEnv::NewString` die zentrale,
fehlertolerante UTF-8-zu-UTF-16-Konvertierung. Gültige Mehrbytezeichen werden über
zusammengefügte Tokenstücke hinweg dekodiert; ungültige oder unvollständige
Sequenzen werden durch U+FFFD ersetzt und können den Android-Prozess nicht mehr
über `NewStringUTF` abbrechen.

`AiHardwareProbe` verbindet Android-Speicher-, Akku-, Lade- und Wärmedaten mit
den von JNI gemeldeten nativen Fähigkeiten und Vulkan-Geräten. RAM- und
Wärmegrenzen werden vor dem Modellstart im App-Prozess geprüft und während
längerer Korrekturläufe erneut kontrolliert. `AiPerformanceBenchmark` hält die
Messläufe getrennt vom normalen Korrekturpfad; Aufwärmläufe fließen nicht in die
Mittelwerte ein.

Für ARM64 baut das `llm`-Modul mehrere dynamisch geladene Android-CPU-Varianten:
portables ARMv8, Dot Product, FP16/INT8 und SVE2. `llama.cpp` bewertet sie anhand
der realen HWCAP/HWCAP2-Fähigkeiten und lädt nur die beste kompatible Variante
aus dem Native-Verzeichnis der App. Jede beschleunigte Variante enthält die
zugehörigen KleidiAI-Kernel; der portable Rückfallpfad bleibt erhalten. Erst
innerhalb der ausgewählten Variante schaltet `use_extra_bufts` zwischen
Standard-CPU und KleidiAI-Weight-Packing um. Das Vulkan-Backend wird aus
demselben kontrollierten Suchpfad geladen. Threadzahl, Affinitätsmaske,
Priorität und Polling werden über die Funktionsschnittstelle des tatsächlich
geladenen CPU-Backends gesetzt; Kontext, Batchgrößen, Flash Attention, KQV- und
Operationsauslagerung gehen direkt in die llama.cpp-Kontextparameter ein.
x86/x86_64 bleiben portable statische CPU-Builds.

`CPU` und `AUTO` werden sowohl an der Kotlin-Grenze als auch erneut in JNI
vollständig Vulkan-frei normalisiert: keine GPU-Schichten, kein GPU-Gerät und
keine KQV-/Operationsauslagerung. Vulkan/Hybrid sind ausdrückliche Profile.
Native Inferenz-Exceptions werden an den JNI-Einstiegspunkten in typisierte
Fehler übersetzt. Bei `VK_ERROR_DEVICE_LOST` verwirft der Sitzungsmanager die
Vulkan-Sitzung und wiederholt den Auftrag höchstens einmal mit einer vollständig
CPU-basierten Konfiguration.

Die Diagnose fragt Merkmale, Variantennamen und KleidiAI-Verfügbarkeit direkt
über die geladene Backend-Registry ab. Sie unterscheidet verpacktes Backend,
Gerätenutzbarkeit und Sitzungsaktivierung. Native Lade- oder JNI-Fehler bleiben
als Fehlertext sichtbar. Da der gepinnte KleidiAI-Pfad Gewichtstypen Q4_0 und
Q8_0 unterstützt, aktiviert die Laufzeit KleidiAI nur für entsprechend
quantisierte Modelle; Q4_K_M fällt kontrolliert auf Standard-CPU zurück.

`AiEngineSessionManager` hält genau eine `LocalAiEngine` im App-Prozess. Der erste
Auftrag lädt das in den Einstellungen ausgewählte Modell; weitere freie Tests und
Korrekturläufe verwenden dieselbe Modellabbildung. Die KI-Diagnose hält die nicht
sichtbaren Chatnachrichten ausschließlich im Arbeitsspeicher. Für jede neue Frage
rendert die eingebettete Chatvorlage die vollständige Unterhaltung und die native
Schicht baut daraus einen frischen `llama_context` auf. Nach der Antwort wird dieser
Rechenkontext freigegeben; Modell und Nachrichten bleiben erhalten. Dadurch kann
die Antwort frühere Aussagen berücksichtigen, ohne einen KV-Cache samt
Tokenpositionen über mehrere Aufrufe synchronisieren zu müssen. Es wird kein
Verlauf persistiert. Ein Modellwechsel, das Löschen des geladenen Modells,
**Unterhaltung zurücksetzen** oder das Ende des App-Prozesses schließt die flüchtige
Gesprächssitzung.

Reicht das feste Kontextfenster nicht für eine weitere Anfrage, bleibt die bisherige
Unterhaltung unverändert und die Oberfläche fordert zu einem bewussten Zurücksetzen
auf. Ein unbemerkter automatischer Kontextverlust findet nicht statt. Die
automatisierte Transkriptkorrektur behält vorerst ihren getrennten Gruppenpfad; die
spätere abschnittsweise Unterhaltung ist in
`docs/_work/ai-diagnostics-conversation-plan.md` festgehalten.

Die JNI-Schicht rendert die im GGUF eingebettete Qwen-Chatvorlage über die offizielle
`llama.cpp`-Jinja-Anbindung mit `enable_thinking=false`. `/no_think` wird nicht in
Benutzer- oder Korrekturprompts geschrieben. Freie Testläufe liefern zusätzlich
native Messwerte für Prompt-Tokens, Promptverarbeitung, erstes Antwort-Token,
Antworterzeugung, Ausgabetokens und Beendigungsgrund. Strukturierte Korrekturen
enden unmittelbar nach dem geschlossenen JSON-Objekt.

Das `lib`-Modul baut `whisper.cpp` mit `GGML_VULKAN=ON`. Vor der Kontextanlage
fragt JNI die registrierten GGML-Geräte ab. Nur ein tatsächlich vorhandenes GPU-
oder iGPU-Gerät aktiviert den GPU-Pfad; ein fehlendes Gerät oder eine gescheiterte
GPU-Initialisierung führt zu genau einem sichtbaren CPU-Rückfall. Jeder Whisper-
und Silero-Kontext besitzt einen seriellen Executor, der beim Freigeben zusammen
mit dem nativen Kontext idempotent geschlossen wird.

## Build und Veröffentlichung

`.github/workflows/build-apk.yml` führt die JVM-Unit-Tests aus und baut getrennt
eine dauerhaft signierte Debug-APK, eine signierte Release-APK und ein signiertes
Release-AAB. Der Workflow prüft Signaturen, nicht debuggable Release-Metadaten,
deaktiviertes Backup sowie die KleidiAI- und Whisper-Vulkan-Native-Payloads und
lädt Debug- und Release-Artefakte getrennt hoch. Die Signierdaten werden
ausschließlich aus geschützten Repository-Secrets gelesen.
