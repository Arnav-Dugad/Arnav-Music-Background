# Premium roadmap — implementation status

The requested feature list is a roadmap, not a claim that all 100 ideas have shipped.
This implementation starts multiple packages with connected UI, persistence and regression coverage.

| Package | Implemented in this release | Remaining work |
| --- | --- | --- |
| Playback reliability | Listening time requires media progress; excludes buffering, stalls and seeks; existing YouTube focus fix retained | Real-device YouTube/network/lock-screen testing; startup timeout diagnostics |
| History and metadata | All history, version/credit fields, edits, feedback and analysis included in private backups | Recording/version graph, richer provenance and edition comparison UI |
| Editable UI | Home section order/visibility, reset layout, Listening Studio entry from Home and Settings | Player-layout editor, theme packs and more accessible personalization |
| AI editing | Persistent requests, real pipeline progress, cancellation, stale-result protection, undo previous preview; duration/energy/familiarity/diversity/curve controls | Per-song replacement previews, queue edit undo, AI journal and richer explanations |
| Local audio | True two-decoder equal-power local crossfade (0–12 s); existing gapless, fades, analysis and Sing retained | Measured loudness normalization, output presets and device calibration |
| Animation | Player release velocity drives interruptible spring settling; safe geometry/cutout alignment; existing reduced-motion support | Full animation audit and gesture/scroll performance profiling |
| Search and smart playlists | Quoted artist/source/year/duration filters, typo matching, preview/save/refresh rule playlists | Automatic rule reevaluation, more rules, indexed large-library search and duplicate-resolution UX |
| Data and devices | Per-record settings/history sync, idle YouTube queue sync, immutable snapshots, account isolation, restore/export controls, complete cloud deletion, strict rules/tests | Device handoff, retention/garbage collection, optional local-audio Storage backup |

## Release acceptance

- Run all domain/app unit tests.
- Compile and shrink the release APK; inspect lint.
- Run the Android emulator suite, including archive round-trip/unsafe-input tests and the prior playback regression.
- Run Firestore security-rule emulator tests.
- Ship rules/indexes/deployment documentation with the code; production Firebase deployment is separate.
- Preserve package ID, signing identity, Firebase configuration and the YouTube IFrame engine.

## Next implementation order

1. Retention/garbage collection and local backup import.
2. Playback diagnostics and actual-device background-playback verification.
3. Measured normalization and output presets.
4. Player customization, motion/gesture audit and accessibility checks.
5. Rich metadata/version graph and automatic smart-rule evaluation.
6. AI song replacements and queue undo, followed by device handoff.

No empty premium switches or simulated audio features are presented as completed.


## Selected feature batch (4, 9, 17, 18, 31)

- Automatic per-record Firebase sync: durable outbox, per-field settings, server revisions,
  listening-event identities and deletion tombstones; shared YouTube queue only when idle.
- True local crossfade: overlapping decoders, equal-power gain envelopes, pause/seek/focus/source
  cancellation; disabled by default for gapless albums. YouTube audio is not processed.
- Artist/album detail panels: actual library listening data, tag provenance, genres/years,
  edition labels inferred from titles, related locally available album editions and credit links.
- Player motion: release velocity continues into the spring; morph geometry stays bounded and
  respects cutouts and landscape insets.
- Read-only library health: permission-aware missing-file references, local artwork checks,
  incomplete tags, duplicates, pending imports and dangling playlist metadata.
- Widgets: compact play button, expanded progress/transport, separate queue header/controls,
  48 dp transport targets, responsive sizes, background YouTube actions, scalable/generated previews.
- YouTube notification: complete state before activation, metadata and bitmap artwork, system
  transport reservations; in-app links to Android/Samsung notification and channel settings.

Device-specific One UI visibility and streamed YouTube playback still require a phone check.

The app compiles/targets Android 17 (API 37), uses Media3 1.11.1, and runs emulator checks on API 34 and 37. Generated widget picker previews are gated to Android 15+; Android 12–14 use scalable XML previews. Artwork is decoded at bounded sizes for RemoteViews memory limits.
