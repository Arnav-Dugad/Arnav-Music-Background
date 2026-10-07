# Account data, backup and Firestore controls

This release expands the existing live likes/YouTube playlist merge with **full account snapshots**.
Snapshots are backups, not automatic per-record merging of every table. Restoring is explicit and replaces
the local dataset. No Firebase console changes are made by committing these files.

## Included data

All 16 Room tables: tracks (including local metadata and references), likes, playlists, playlist entries,
play events, search results, recent searches, AI responses, key/value records, lyrics, audio features
(binary envelopes included), pending import matches, import history, tag overrides, recommendation
feedback and skip marks.

AppSettings/DataStore includes appearance, motion, playback, personalization, sources, notifications,
consent, Home layout and AI session controls. Queue state, usage counters, recommender tuning,
lyric/credit/chapter metadata, import preferences, duplicate choices, launcher/widget state, AI prompt
history and saved smart-playlist rules are captured from explicit app-owned preference stores.
Saved lyric translations and vocal activity analysis files are included.

Derived recaps/Taste DNA are reconstructed from their saved records. Artwork/HTTP caches and downloaded
APK files are regeneratable and excluded. Local music **binaries are not uploaded**; a content URI from
another device may not resolve until the same files are imported there. YouTube downloads/audio extraction
are not added. External-provider credentials are not exportable via these backups.

Firebase Auth continues to manage passwords, password hashes and sign-in tokens. These and encrypted
YouTube API credentials are never copied into Firestore backups. Signing in with a different UID blocks
uploading the previous account's data. Restore a backup from the current account or clear this app's
storage first to bind an empty device to that account.

## Firestore layout

| Path under users/{uid} | Meaning |
| --- | --- |
| likes/{trackId} | Existing incremental, last-write-wins live sync |
| playlists/{playlistId} | Existing YouTube entry live sync (up to 500); full snapshots preserve the complete local table |
| vaultChunks/{sha256} | Immutable Base64 GZIP segments, at most 300 KiB each before Base64 |
| backups/{deviceId}_{sha256} | Immutable manifest, counts, checksum, chunk order and app/schema versions |
| devices/{deviceId} | Latest committed backup pointer and friendly device label |

Only the authenticated owner may read or delete any of these documents. Chunk/manifest updates are
denied. Unknown fields, oversized payloads, malformed schemas and cross-account access are denied.
Device pointers use getAfter to check the manifest in the same atomic batch.

Chunks upload before publishing a manifest and device pointer. Interrupted uploads are not offered as
complete backups. Identical chunks are reused. Every downloaded chunk and the complete archive are
SHA-256 checked, then schema/column/store/file paths validated before Room writes. Room restoration is
transactional; preferences/files follow, with a prior recovery checkpoint and attempted rollback on failure.

Automatic requests coalesce edits into a 30-second window. Network-constrained WorkManager runs at a
minimum 15-minute interval; Android may defer it. Sign-in and manual Sync use immediate requests.
The sync dashboard shows errors, last successful sync, snapshots and restore previews. The newest 30
snapshots are displayed. **There is no automatic retention purge yet**: old manifests, chunks and unfinished
upload chunks remain until account cloud deletion. Monitor Firestore storage/reads/writes in the console.

A snapshot must fit 64 MiB of uncompressed JSON; large binary entries have a 16 MiB per-file limit.
Limits cause a visible error, never silent truncation. No free-quota guarantee is made.
Full encrypted audio-file backup in Firebase Storage is a later phase and would require a Storage
bucket/billing configuration; this release doesn't enable billing.

## Deploy updated controls

Run from the repository root, using your Firebase administrator account:

```bash
npm install -g firebase-tools
firebase login
firebase deploy --only firestore:rules,firestore:indexes --project YOUR_FIREBASE_PROJECT_ID
```

Use the project_id in app/google-services.json (or the config supplied by your Actions secret).
The checked-in firebase.json selects firebase/firestore.rules and firebase/firestore.indexes.json.
Deploy **before** using the new sync; otherwise Firestore correctly returns PERMISSION_DENIED.
Do not replace the rules with public read/write rules or disable App Check.

Validate rules independently:

```bash
cd firebase/tests
npm install --no-audit --no-fund
npx firebase emulators:exec --only firestore --project demo-arnav-music --config ../../firebase.json "node --test rules.test.mjs"
```

## User controls

Settings → Data & sync: enable/disable sync, run a backup, see errors, export a GZIP backup via Android's
file picker, refresh snapshots, verify a snapshot, preview counts, confirm restore. After restoring,
restart the app to refresh cached preferences. Playback remains paused.

Settings → Privacy → Delete cloud profile: disables cloud sync first, then deletes all likes, playlists,
manifests, chunks, devices and the root profile. Local data remains. If deletion is interrupted, keep sync
off and retry until completion. Account deletion performs cloud deletion before Firebase Auth deletion.
Clearing local history does not erase history inside old snapshots: delete cloud data to purge those copies.

Recovery checkpoint: private filesDir/restore-checkpoint.gz stores the pre-restore app dataset. It isn't
uploaded or shown as a normal backup. A failed restore attempts to put the prior snapshot back.
Portable export currently has no local import UI; cloud restore is the supported restore path.
