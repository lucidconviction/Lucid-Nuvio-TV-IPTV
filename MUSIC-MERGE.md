# Merging the Nuvio-One music section

Reference: `zoga2o3-ops/Nuvio-One` → `MUSIC-PORTING.md`.

## What the reference actually is

The document is a **full section port**, not a patch. It copies:

- `app/src/main/java/com/music/bitchord/` (catalogue client, parsers, models, lyrics)
- about 65 files under `app/src/main/java/com/nuvio/tv/music/` (UI, player, Party Mode,
  WebDAV, Navidrome, Last.fm/Spotify, downloads, equalizer, services)
- Party/Navidrome/phone-setup assets, music fonts, notification icon
- `MusicPlaybackService`, `MusicDownloadService`, `MusicPartyService`
- Gradle Ktor content-negotiation/serialization dependencies

It then adds the Music route/drawer and pauses playback in `onStop`.

## Why it is not copied unchanged here

Our TV music section is a different, working implementation:

- Deezer catalogue + YouTube stream resolver (`MusicRepository`, `DeezerClient`,
  `YouTubeMusicService`).
- App-wide `MusicPlaybackController` with crossfade, a global mini player and background
  session.
- Downloads, local playlists, lyrics, queue and the CloudStream/Lucid integrations.
- Music route/drawer/start-page already wired.

Copying the Nuvio-One package would replace `MusicScreen`/`MusicPlayer`/`MusicViewModel`
and drop those integrations. Its services and BitChord client also bring new runtime
dependencies. That would be a replacement, not a merge.

## Merge plan

Phase 1 — integration edges (done)

- Keep our route/drawer/root wiring.
- Pause the session in `onStop` (the reference behaviour), while keeping it alive across
  in-app navigation.
- Keep the existing mini-player visibility rule.

Phase 2 — catalogue sources

- Add BitChord as an optional catalogue adapter beside Deezer, with a source selector.
- Keep our player/queue model; map BitChord tracks to `MusicTrack` and resolve playback
  through the existing YouTube resolver.

Phase 3 — services and library sources

- Port Party Mode server/UI and its service.
- Port WebDAV and Navidrome libraries as additional local sources (not replacements).
- Add the phone-link panels and assets.

Phase 4 — personalisation

- Port Last.fm scrobbling and Spotify import behind their settings.
- Port equalizer/lossless output where the TV audio stack supports it.

## Conflict points to resolve in Phases 2–4

- Duplicate `MusicViewModel`, `MusicScreen`, `MusicPlayer`, `MusicLibrary` names.
- Manifest services/permissions and notification channels.
- Gradle Ktor serialization dependencies and music fonts/assets.
- Party/Navidrome/Spotify settings and secure preference storage.
