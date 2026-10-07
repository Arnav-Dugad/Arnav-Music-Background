# Architecture

## Layers
```
presentation  (Compose screens, ViewModels, design system)
     ↓
data          (repositories, PlaybackController, AiGateway, CloudSync)
     ↓
domain        (:core:domain — pure Kotlin models, algorithms, contracts)
     ↓
providers     (YouTubeRepository, LocalMediaSource, Room, Firebase)
```

## Key flows

### Search (quota-aware)
`SearchScreen` → `ExploreViewModel` (debounce 650 ms, `distinctUntilChanged` on the normalised cache key, `flatMapLatest` cancels stale requests) → `SearchRepository.search()` emits: instant local matches → cached page (if any) → remote page only when needed → `YouTubeRepository` (cache freshness by quota state, in-flight de-dup, backoff, usage metering).

### Arnav AI session
`ArnavAiViewModel.build()` → `IntelligenceRepository.buildSession()`:
1. `LocalIntentEngine.interpret()` always runs (offline baseline).
2. If allowed, `AiGateway.generate()` (consent, daily cap, throttle, cache by prompt version, timeout) asks Gemini for **constraints only** (JSON). `AiJson` parses tolerantly; any failure keeps the local constraints.
3. Candidates = known tracks (Room + device) + YouTube search results for the constraint queries (cache-first, ≤2 remote searches, 0 when quota is exhausted).
4. `SessionBuilder` (deterministic) orders tracks to fill the duration, follow the energy curve, honour discovery ratio and artist diversity.

AI never invents tracks: only resolved, playable `Track`s can enter a session.

### Playback
`PlaybackController` owns an immutable `QueueState`. Local runs of consecutive on-device tracks are loaded into ExoPlayer (gapless, lock-screen next/prev) via a `MediaController` bound to `PlaybackService`; YouTube tracks are loaded into the single persistent IFrame player. Listening events (≥5 s) are recorded locally as `PlayEvent`s.

### Sync
Room rows carry `updatedAt`, `deleted`, `dirty`. Edits mark dirty and call `requestSync()` (4 s debounce). `syncNow()` pulls `updatedAt > lastPull`, runs `SyncMerge.plan()` and batch-writes winners. WorkManager runs a periodic job (12 h, network + battery-not-low).

## Performance strategy
- `PerformanceManager` computes an `EffectsBudget` from device tier, power saver, thermal status, system animator scale and the user's mode; visual layers read it (blur, particles, living artwork, glass level, motion).
- Images decoded at display size (`Artwork(decodeSize = …)`), 256 MB disk cache, shared OkHttp.
- Stable/immutable models (`compose-stability.conf`), keys on every lazy item, `graphicsLayer` for animated transforms (no relayout), deferred state reads in draw/layer lambdas, progress in a separate `StateFlow` so ticks don't recompose the whole player.
- Startup: Firebase, Remote Config and WorkManager scheduling happen off the main thread after first frame.
- R8 full optimisation and resource shrinking in release; baseline profile in `app/src/main/baseline-prof.txt`.

## Testing
- `:core:domain` unit tests: recommender, taste profile, intent parsing, session builder, smart playlists, recaps, constellation determinism, query normalisation, artist keys, sync merge, backoff, palette contrast, HSL, queue ops, quota policy, AI JSON parsing, formatters.
- Firestore rules: emulator tests in `firebase/tests`.
