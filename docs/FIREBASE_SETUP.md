# Firebase setup (Spark plan — no billing)

Everything here stays on the free **Spark** plan. Never attach a billing account: Arnav Music doesn't need one, and Spark simply stops (it never charges) at its limits.

> You never paste credentials into source code. `google-services.json` lives in `app/` locally (git-ignored) or in a GitHub secret.

## 1. Create the project
1. Open <https://console.firebase.google.com> → **Add project** → name it e.g. `arnav-music`.
2. Google Analytics: optional. If enabled, choose "Default account". (The app keeps analytics **off** until a user opts in.)
3. When asked about a plan, stay on **Spark**. Do **not** click "Upgrade".

## 2. Register the Android app
1. Project overview → **Add app → Android**.
2. Package name: `com.arnav.music` (debug and release builds share this id).
3. Add SHA-1 and SHA-256 fingerprints:
   - Debug: `./gradlew :app:signingReport` (look for the `debug` variant).
   - Public community release key (`keystore/arnav-public.jks`):
     - SHA-1 `79:53:40:C7:3D:76:A0:2A:0F:DC:22:6A:C3:2E:E7:22:A5:06:0F:B8`
     - SHA-256 `80:0A:B9:C8:F6:8A:C6:2D:1C:8A:0E:AB:15:F5:41:F4:14:E0:7D:0C:B7:B9:9E:3A:48:48:2C:8B:6A:9B:8D:80`
   - Your private release key (if any): `keytool -list -v -keystore your.jks`.
4. Download **google-services.json** → put it at `app/google-services.json`.

## 3. Authentication
1. Build → **Authentication → Get started**.
2. Sign-in method → enable **Email/Password**.
3. Enable **Google**. Set a support email. Save.
4. Copy the **Web client ID** shown under Google → Web SDK configuration. It's also in `google-services.json` (`oauth_client` with `client_type: 3`). The app reads it automatically; optionally set `GOOGLE_WEB_CLIENT_ID` in `local.properties` / CI secret.
5. Settings → **User actions**: keep "Email enumeration protection" on.

## 4. Cloud Firestore
1. Build → **Firestore Database → Create database**.
2. Location: one close to your users (it can't be changed later). Start in **production mode**.
3. **Rules** tab → paste the contents of [`firebase/firestore.rules`](../firebase/firestore.rules) → **Publish**.
   Or with the CLI: `npm i -g firebase-tools && firebase login && firebase deploy --only firestore:rules --project <your-project-id>`.
4. Test the rules locally (free emulator): `cd firebase/tests && npm install && npm test`.

## 5. Firebase AI Logic (Gemini Developer API — free tier)
1. Build → **AI Logic** → **Get started**.
2. Choose **Gemini Developer API** (not Vertex AI — Vertex requires Blaze).
3. Accept the terms. Firebase creates/links the Gemini API key server-side; it never ships in the app.
4. Model: the app defaults to `gemini-2.5-flash-lite` (free-tier friendly). Override with Remote Config key `ai_model`.
5. Recommended: AI Logic → Settings → per-user rate limit (e.g. 20 requests/minute).

## 6. Remote Config (optional tuning)
Build → **Remote Config** → add any of these parameters (defaults are built in):

| Key | Default | Meaning |
|---|---|---|
| `ai_enabled` | `true` | Global kill-switch for cloud AI |
| `ai_model` | `gemini-2.5-flash-lite` | Gemini model name |
| `ai_temperature` | `0.6` | 0–1.5 |
| `ai_max_output_tokens` | `600` | Token budget per request |
| `ai_min_interval_ms` | `4000` | Client throttle |
| `ai_timeout_ms` | `20000` | Request timeout |
| `youtube_default_budget` | `10000` | Default daily YouTube unit budget |

## 7. App Check (recommended)
1. Build → **App Check** → register the Android app with **Play Integrity** (free).
2. Debug builds: run once, copy the debug token from Logcat (`DebugAppCheckProvider`), add it under App Check → Manage debug tokens.
3. After you confirm traffic is verified, click **Enforce** for Firestore and AI Logic.

> **Sideloaded APKs and Play Integrity.** Play Integrity vouches for apps installed from Google Play. APKs downloaded from GitHub Releases are usually *not recognized*, so their App Check tokens are rejected. If AI Logic (or Firestore) is **enforced**, those installs can't use it — Arnav AI shows "On-device mode · App Check" and keeps working locally.
> While you distribute through GitHub, either leave App Check **unenforced** (rely on Firestore rules + AI Logic per-user rate limits), or keep it enforced and accept on-device AI for sideloaded installs. Enforce everything once the app ships on Google Play.
> Debug builds use the **debug provider**: run the app, copy the secret from Logcat tag `DebugAppCheckProvider`, and add it under App Check → Apps → ⋮ → Manage debug tokens.

## 8. Build with Firebase
- Locally: `app/google-services.json` present → `./gradlew :app:assembleDebug`.
- CI: Repository → Settings → Secrets → Actions → `GOOGLE_SERVICES_JSON` = the entire file contents.

## Never do this
- Don't enable Blaze, Cloud Functions, Storage on Blaze, or Vertex AI.
- Don't commit `google-services.json`, service-account JSON or private keys (`.gitignore` already blocks them).
