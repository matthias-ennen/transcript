# Lokale Qwen3.5-Modelle

Stand: Produktentscheidung 29.08.2026 · Issues #101, #102 und #61

Transcript behält den lokalen Qwen3.5-/`llama.cpp`-Unterbau. Die fachliche Rolle
der Modelle wird jedoch geändert: Qwen soll das Whisper-Transkript künftig
**nicht mehr korrigieren**, sondern auf ausdrücklichen Benutzerwunsch das fertige,
aktuell akzeptierte Transkript auswerten.

Für Version 1.0 sind vier feste Auswertungsaufgaben vorgesehen:

1. Zusammenfassen
2. Kernaussagen / Stichpunkte
3. Aufgaben & To-dos
4. Entscheidungen / Besprechungsprotokoll

Die KI-Ausgabe ist ein separates Ergebnis und verändert Transkripttext,
Zeitstempel, Segmentreihenfolge und Transkript-Herkunft nicht.

## Aktuelle Vergleichsmatrix

Während #61 bleiben sechs vollwertig integrierte GGUF-Varianten derselben
Qwen3.5-Familie als reproduzierbare Vergleichsmatrix erhalten. Die Modelle werden
nicht mit der APK ausgeliefert, sondern bei Bedarf heruntergeladen und danach per
SHA-256 geprüft.

| Klasse | Modell | Quantisierung | Download | KleidiAI-Pfad | Quelle / gepinnte Revision |
| --- | --- | --- | ---: | --- | --- |
| Schnell | Qwen3.5 0,8B | Q4_0 | 563 MB | ja | `ggml-org/Qwen3.5-0.8B-GGUF` · `8fea620810c4afa23dd6443f999a48574c1611a3` |
| Schnell | Qwen3.5 0,8B | Q8_0 | 834 MB | ja | `ggml-org/Qwen3.5-0.8B-GGUF` · `8fea620810c4afa23dd6443f999a48574c1611a3` |
| Ausgewogen | Qwen3.5 2B | Q4_K_M | 1,28 GB | nein | `unsloth/Qwen3.5-2B-GGUF` · `1c466474d208da1a7c4b8cb87ebcdac78f160e34` |
| Ausgewogen | Qwen3.5 2B | Q4_0 | 1,21 GB | ja | `unsloth/Qwen3.5-2B-GGUF` · `31e04817b38d226cdd13454bcc3982ebaa5a386b` |
| Sehr genau | Qwen3.5 4B | Q4_K_M | 2,74 GB | nein | `unsloth/Qwen3.5-4B-GGUF` · `9b57f22a6a894e8db976ae8cc55f794b3ad18b94` |
| Sehr genau | Qwen3.5 4B | Q4_0 | 2,58 GB | ja | `unsloth/Qwen3.5-4B-GGUF` · `1a02dbed1cdfe73efc3fa519c54126befb4faf68` |

## Prüfsummen

- Qwen3.5 0,8B Q4_0: `57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf`
- Qwen3.5 0,8B Q8_0: `37ae482d336108d23516fa35e8e0c4126688d81018b87178a18d752a1357814f`
- Qwen3.5 2B Q4_K_M: `aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223`
- Qwen3.5 2B Q4_0: `cd70221bebaee0503e0f6717e174250cd7825aa88438b3aabec9ad55731d9bb1`
- Qwen3.5 4B Q4_K_M: `00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4`
- Qwen3.5 4B Q4_0: `298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb`

## Lizenz

Die verwendeten Qwen3.5-Basismodelle stehen unter Apache License 2.0. Die
konkreten GGUF-Quellen sind gepinnt, damit Modellherkunft und heruntergeladene
Binärdatei reproduzierbar bleiben. Der Lizenztext liegt unter
`licenses/Qwen3.5-Apache-2.0.txt`; die Drittanbieterübersicht steht in
`THIRD_PARTY_NOTICES.md`.

Vor Version 1.0 prüft #35 erneut jede tatsächlich ausgelieferte Variante samt
Quantisierungsquelle, Revision, SHA-256 und Attribution.

## Produkt- und Testregel

Die sechs Einträge sind während #61 echte App-Modelle, keine versteckten
Benchmarkdateien. Sie besitzen dieselben Download-, Auswahl-, Lösch-, Diagnose-
und Benchmarkpfade sowie jeweils ein eigenes Leistungsprofil.

Die bisherige Transkript-Korrektur ist **kein Qualitätsmaßstab mehr**. Die Modelle
werden künftig mit identischem Transkriptmaterial an den vier realen
Auswertungsaufgaben verglichen.

Bewertet werden gemeinsam:

- Ende-zu-Ende-Antwortzeit
- Prompt-/Prefill-Zeit
- Zeit bis zum ersten Token
- Generierungszeit und Tokenrate
- RAM- und thermisches Verhalten
- Stabilität und tatsächlicher CPU-/KleidiAI-/Vulkan-/Hybridpfad
- inhaltliche Treue und Abdeckung bei Zusammenfassungen/Kernaussagen
- Erfindungen beziehungsweise Fehlzuordnungen bei Aufgaben und Entscheidungen
- Verhalten bei kurzen, mittleren und langen Transkripten

Bei langen Transkripten muss zusätzlich der in #102 geplante mehrstufige Weg
bewertet werden. Ein Modell ist nicht allein deshalb geeignet, weil ein kurzer
Einzelprompt schnell beantwortet wird.

## Entscheidung nach #61

Die drei Benutzerklassen bleiben während der Untersuchung `Schnell`,
`Ausgewogen` und `Sehr genau`; die Quantisierung wird in Klammern ergänzt.

Nach reproduzierbaren Gerätevergleichen wird ausdrücklich entschieden:

- welche Kombination die **schnellste ausreichend gute** Standardlösung ist,
- ob mehrere Qualitätsstufen für die neue Auswertungsfunktion einen echten
  Produktnutzen haben,
- ob alle sechs sichtbaren Varianten in Version 1.0 bleiben sollen.

Werden Varianten später entfernt, werden Modellkatalog, GUI, Downloads, Profile,
Tests, Lizenzen und Dokumentation vollständig konsistent bereinigt. Eine
Reduzierung erfolgt nicht stillschweigend während der Messung.

## Nicht Teil der aktuellen Matrix

Qwen3.5 2B Q8_0 und Qwen3.5 4B Q8_0 werden in #61 zunächst nicht integriert. Sie
werden nur erneut bewertet, wenn die Sechser-Matrix einen konkreten technischen
Grund dafür liefert.
