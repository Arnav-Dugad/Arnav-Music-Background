# Background playback edition

Source: Arnav-Dugad/Arnav-Music, commit 0229f5d9334968fe62ef57ee26138068770a04d9.

## Behavior

- YouTubeEngine owns one application-context YouTubePlayerView with enableBackgroundPlayback(true).
- Activity/Compose disposal no longer releases the view or pauses the song.
- YouTubePlaybackService promotes itself to a mediaPlayback foreground service before obtaining audio focus and authorizing playback.
- A platform MediaSession supplies play/pause, previous/next, seeking and stop controls to the notification, lock screen and headsets.
- Audio-focus loss pauses; transient loss may resume on gain. Unplugging headphones pauses when that setting is enabled.
- Song mode shows artwork over the retained player. Video mode displays the video and supports the existing optional floating player.
- Local audio continues through the existing Media3 PlaybackService. Switching to local audio ends the YouTube service.
- Paused playback releases its wake lock immediately and leaves foreground mode after five minutes. Explicit stop clears the queue, service and notification. Process death does not automatically restart music.

## Firebase and credentials

The application ID remains com.arnav.music and app/google-services.json is retained. This APK updates the existing application instead of installing alongside it. Version codes start at 1001 so the new repo's reset Actions counter does not produce a downgrade. App updates now use this repository.

GitHub imports do not copy Actions secrets. Set YOUTUBE_API_KEY in this repo for a built-in key, or paste your own key in Settings. GOOGLE_WEB_CLIENT_ID is optional when the committed Firebase config provides its web OAuth client. If the original app used a private signing key, configure the same signing secrets here; otherwise the existing public community key is used. A certificate mismatch prevents updating an installed APK and may require registering the new fingerprint for Google sign-in/API restrictions. Do not uninstall just to resolve a mismatch without exporting your data first.

Firebase authentication, sync and AI keep their existing checks. App Check settings and token registration are still required where enforced. No Firebase security settings or rules are weakened by this change.

## Validation

CI runs domain/app unit tests, assembles the optimized release APK and runs lint. These checks do not prove live YouTube background playback on a physical phone. Before relying on this edition, check:

1. Play an embeddable YouTube song; Song mode shows artwork and Video mode shows the video without restarting the track.
2. Press Home, switch apps, lock the screen and let two tracks finish. Check sound, automatic next-track playback and progress when returning.
3. Use notification/lock-screen/Bluetooth play, pause, previous, next and seek. Verify Stop removes the notification.
4. Unplug headphones and interrupt playback with another audio app or a call; confirm pause and appropriate focus recovery.
5. Rotate/reopen the activity; check that position continues rather than resetting.
6. Switch YouTube → local → YouTube; confirm only one source is audible and that local playback still works.
7. Pause for more than five minutes, then resume from the notification. Check airplane mode, reconnection, unavailable videos and a sleep timer.
8. Verify existing Firebase sign-in, cloud sync and AI with the same account/configuration.

## Policy and runtime limits

Hidden/background players are prohibited by YouTube API developer policies. This edition is experimental and not policy-compliant. Firebase working does not imply YouTube policy approval. IFrame, WebView and device battery-management changes can interrupt playback, and force-stopping the app ends playback. No audio extraction, offline caching, downloads or ad-blocking functionality is implemented.
