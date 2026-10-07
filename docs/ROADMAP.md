# Roadmap — ultra-premium upgrades

Ideas ranked by impact. Everything here can stay zero-cost.

## UI & interaction
1. **Shared-element artwork everywhere**: cards on Home/Library → Collection hero → Now Playing, all one continuous element (`SharedTransitionLayout`). ✅ Shipped for playlist/mix covers.
2. **Liquid glass shader (AGSL, Android 13+)**: refraction and chromatic edge on the MorphBar and sheets, with the current material as fallback. ✅ Shipped (MorphBar).
3. **Adaptive tablet "Studio" layout**: three panes (library · queue · Now Playing), with drag-and-drop between them. ✅ Shipped (without drag-and-drop for now).
4. **Swipeable artwork carousel** in Now Playing: previous and next covers peek at the edges, so swiping is a real physical gesture. ✅ Shipped.
5. **Haptic waveform scrubbing**: subtle ticks at song sections while seeking (local files analysed on device).
6. **Mini-player personalities**: compact pill, expanded card, and a floating bubble mode while multitasking. ✅ Floating mode shipped as picture-in-picture.
7. **Glance home-screen widgets**: Now Playing (local), Moments shortcut, "Ask Arnav AI", weekly recap. ✅ Shipped, plus an Up Next widget.
8. **Dynamic app icon accent** (Android 13+ themed icons) and an artwork-tinted quick-settings tile. ✅ Themed icon and Quick Settings tile shipped.
9. **Edge-lighting progress** on OLED: a hairline progress glow around the screen edge while the screen is idle. ✅ Shipped.
10. **Keyboard/D-pad polish** for Chromebooks, DeX and Android TV (focus rings, shortcuts sheet).

## Animation
11. **Spring-physics queue**: items keep inertia when flung; elastic overscroll on reorder. ✅ Springy ends shipped.
12. **Track change "depth swap"**: the old cover sinks with a blur, the new one rises — using RenderEffect where the budget allows. ✅ Shipped.
13. **Beat-reactive Living Artwork** for local files (on-device onset detection, capped at 0.5 Hz pulses — no flashing). ✅ Shipped, driven by on-device tempo analysis.
14. **Theme cross-fade**: a circular reveal from the toggle when switching Light/Dark/OLED. ✅ Shipped (also for glass modes).
15. **Morphing icons** (play → pause, heart → filled, queue → check) via animated vectors. ✅ Play/pause and heart shipped.
16. **Predictive-back previews** for the player sheet and every detail screen. ✅ Shipped.

## Arnav AI
17. **Conversation memory per session**: "make it calmer", "fewer vocals", "swap the last three" edits the current session instead of starting over.
18. **AI DJ transitions** (local files): energy-matched ordering plus crossfade points chosen from analysed intros and outros.
19. **"Why this?" deep explanations**: Gemini rewrites the deterministic reasons into one honest sentence, cached per track.
20. **Smart naming and descriptions** for saved sessions and playlists (cached; on-device templates as the fallback).
21. **Mood journal (opt-in)**: tag a day, and Time Machine can recall "your exam weeks" only because you tagged them.
22. **On-device embeddings**: a tiny TFLite text model maps titles/tags to mood vectors, so the local engine gets smarter offline.
23. **Voice requests** via Android SpeechRecognizer (on-device where supported).
24. **Weekly "Arnav Radio"**: an auto-built 60-minute station refreshed every Monday from your taste vector.

## Data & intelligence
25. **Audio feature extraction for local files** (tempo, loudness, key) with a background WorkManager job → real energy values, not estimates. ✅ Shipped.
26. **ReplayGain tag reading** plus a LoudnessEnhancer-based normaliser for local files.
27. **Listening streaks and milestones** (first 1,000 minutes, 100 artists), never gamified with nags. ✅ Shipped.
28. **Year in Music recap** with an animated story player (procedural visuals, shareable cards).
29. **Artist spiral**: chain-discovery through co-listening and shared styles.
30. **Import playlists** from a pasted YouTube playlist URL (1 quota unit per page). ✅ Shipped as account import (OAuth, read-only).
31. **Encrypted local backup/export** (JSON + key) to Drive via the system file picker — no server needed.
32. **Multi-device "continue listening"** using the existing Firestore free tier (one small document, debounced).

## Sources (zero-cost)
33. **Jellyfin / Navidrome (Subsonic API)** provider for self-hosted libraries, with full background playback.
34. **Internet radio** (Radio Browser API, free and open) as a provider.
35. **Local playlist files** (M3U/PLS) read and written.
36. **Licensed lyrics provider hook**: the architecture is ready; plug in any provider that permits display.

## Quality
37. **Generated baseline profile** with a Macrobenchmark module, plus startup and scroll benchmarks in CI.
38. **Screenshot tests** (Roborazzi) for every screen in Pure and Glass, light, dark and OLED, at 200% font size.
39. **Compose UI tests** for login, search, queue reorder and the Glass toggle.
40. **JankStats in debug builds** with a live FPS overlay in the developer panel.
