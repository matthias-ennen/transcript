# Issue #31 implementation decisions

- Global transcript view uses two explicit states: **Whisper-Original** and **Nachbearbeitet**.
- The default view remains **Nachbearbeitet**, matching the previously visible accepted transcript.
- Export and sharing follow the globally selected view and display that choice on the transcript screen.
- The existing TXT/SRT/JSON schemas stay stable in #31; segment provenance remains an internal/UI state and is not added to JSON in this work package.
- The three status vector resources approved in #46 are used unchanged at 32 dp left of the segment number capsule.
- `ORIGINAL`, `MANUAL`, and `AI` are persisted per stable segment identity. Exact equality with the immutable Whisper source always resolves to `ORIGINAL`.
- Remaining #73 corrections are included: live `sectionMinutes` grouping and the standard pulsing KannaBot question bubble for destructive transcript changes.
