# Background edition note

This copy enables background IFrame playback and shows artwork instead of the player in Song mode. Those features conflict with the YouTube policies cited below; the upstream compliance checklist is not a description of this edition. Existing API key setup is unchanged. See [BACKGROUND_PLAYBACK.md](BACKGROUND_PLAYBACK.md).

# YouTube Data API setup (free)

Arnav Music uses **only public data** from the YouTube Data API v3 with an API key, and the **official embedded player** for playback. No OAuth scopes are requested — there is no YouTube account access at all, which keeps it simple and private.

## 1. Google Cloud project
1. <https://console.cloud.google.com> → select the project Firebase created (or create one). No billing account is needed for the YouTube Data API.
2. **APIs & Services → Library** → search **YouTube Data API v3** → **Enable**.

## 2. Create a restricted API key
1. **APIs & Services → Credentials → Create credentials → API key**.
2. **Edit key**:
   - *Application restrictions* → **Android apps** → add package `com.arnav.music` with your SHA-1 (see [FIREBASE_SETUP.md](FIREBASE_SETUP.md#2-register-the-android-app) for the public community key's SHA-1).
     The app sends `X-Android-Package` and `X-Android-Cert` headers so this restriction works.
   - *API restrictions* → **Restrict key** → only **YouTube Data API v3**.
3. Save. Copy the key.

## 3. Give the key to the app
Pick one:
- **In the app**: Settings → Sources → paste the key → Save. It's encrypted on-device (Android Keystore) and never synced.
- **Local build**: `local.properties` → `YOUTUBE_API_KEY=...`
- **CI build**: repository secret `YOUTUBE_API_KEY`.

## 4. Quota
The default quota is **10,000 units/day** per project (check *APIs & Services → YouTube Data API v3 → Quotas*).

| Call | Cost | Used for |
|---|---|---|
| `search.list` | 100 | Search (one call for songs+artists+playlists) |
| `videos.list` | 1 | Durations, embeddability, trending chart |
| `playlistItems.list` | 1 | Opening YouTube playlists |

How Arnav Music protects it: normalised cache keys (`"Daft PUNK!!"` = `"daft punk"`), 650 ms debounce, minimum query length, in-flight de-duplication, day-level reuse of cached pages, stale-while-revalidate, conserve mode at 80 %, exponential backoff on 5xx, no retries on quota errors, and an on-screen usage dashboard (Settings → Usage & quotas). Set **Settings → Sources → Daily unit budget** to match your console quota.

## 5. OAuth consent screen (Google sign-in + playlist import)
1. **APIs & Services → OAuth consent screen** (Google Auth Platform) → External → app name *Arnav Music*, support email, developer email.
2. **Data access → Add scopes**: the defaults (`openid`, `email`, `profile`), plus `https://www.googleapis.com/auth/youtube.readonly` for **Library → Import from YouTube**.
3. **Audience**: keep *Testing* and add every Google account that will import under **Test users**. `youtube.readonly` is a *sensitive* scope. Unverified apps show a "Google hasn't verified this app" screen (tap *Continue*), and in Testing mode only listed test users can grant it. Verification is free if you ever publish.
4. **Clients**: Firebase already created an *Android* OAuth client for `com.arnav.music` with the release SHA-1 `79:53:40:C7:3D:76:A0:2A:0F:DC:22:6A:C3:2E:E7:22:A5:06:0F:B8`. If you sign with another key, add another Android client with that SHA-1 (or the import shows "signing key isn't registered").
5. Make sure **YouTube Data API v3** is enabled in the *same* project as the OAuth client (Firebase project `arnav-music-c8ca5`). Import calls are billed to that project's free 10,000 units/day, at about 8 units for a 200-song playlist.

The import uses Google Play services' `AuthorizationClient`. It gets a short-lived access token that stays in memory only: nothing is stored, logged or committed, and there is no client secret anywhere. It reads only playlist metadata. Songs still play through the visible official player.

## Policy reminders
- Keep the player visible (≥ 200×200 px) with YouTube's controls; never overlay or hide it.
- No background playback, downloads, audio extraction or ad blocking.
- Show YouTube attribution where YouTube data appears (the app does this with a badge).
- Read the [YouTube API Services Terms](https://developers.google.com/youtube/terms/api-services-terms-of-service) and [Developer Policies](https://developers.google.com/youtube/terms/developer-policies).
