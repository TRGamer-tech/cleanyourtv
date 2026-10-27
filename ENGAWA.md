# Engawa

A personal build of the [CleanYourTV](https://github.com/Gathin23/cleanyourtv) launcher (`com.gathin.tvhome`, MIT) for one
TV. Engawa is the veranda that wraps a Japanese temple; the look was tuned around a photo of one. It is a fork, not a new
launcher: everything upstream does still works, and this branch adds a heavier typeface and a row of recent titles under
the focused app's name.

**Tested on:** SHARP 55GP6260E (Sharp Google TV, MediaTek MTK9612, 1 GB), Google TV, Android 11. One unit, in a single long session (a few hours); not lived with for days.

## What it adds

- **Google Sans Flex** (SIL OFL 1.1, trimmed to the weight axis and Latin, 133 KB) instead of Outfit, with a heavier weight
  scale: the clock and app names at 700, the other text a step or two heavier than upstream.
- **Feed rows**: with an app focused on the dock, four cards to the right of its name. Up from the dock enters them, Left and
  Right walk along them, Down returns, OK opens the card.
  - **Jellyfin**: what you are watching, then what you watched last (Quick Connect sign-in, shown as a code on the TV).
  - **SmartTube**: the newest upload of each YouTube subscription (YouTube Data API v3, read-only; it has no watch history).
    OK plays the video in SmartTube.
  - **Spotify**: what you last played from (a playlist, album or artist, DJ included), Liked Songs always second, then your
    playlists (Spotify Web API; recently played, private playlists, library).
- The dock apps and remote-button mappings in `config.xml` are the author's (Jellyfin, SmartTube, Spotify, LocalSend); change them there or with a theme.

## Network and permissions (read this before installing)

Upstream's launcher has no network access. **This one does**: `INTERNET`, and plain-HTTP allowed (`usesCleartextTraffic`) for a
Jellyfin server on the home network. It talks to nothing else than:

| Host | Why |
|---|---|
| your Jellyfin server | the Jellyfin row |
| `api.spotify.com`, `accounts.spotify.com`, album and playlist covers on Spotify's CDN | the Spotify row |
| `www.googleapis.com`, `oauth2.googleapis.com`, `i.ytimg.com` | the SmartTube row |

No analytics, no telemetry, no other servers. Access and refresh tokens are kept in the launcher's private preferences
(`SharedPreferences "feeds"`); clear the app's data to sign out. A row you never link makes no request.

## Setting it up

Spotify and YouTube cannot sign in on a TV, so tokens are made on a computer and pushed once:

1. Spotify: a free app at developer.spotify.com, Web API, redirect URI `http://127.0.0.1:8888/callback`.
   `python3 -I launcher/feeds-auth/spotify_auth.py <client id> spotify.json`, open the link it prints.
2. YouTube: a Google Cloud OAuth client of type "TVs and Limited Input devices", the YouTube Data API enabled, the consent
   screen published (in Testing mode the token expires after 7 days).
   `python3 -I launcher/feeds-auth/youtube_auth.py <client id> <client secret> youtube.json`, enter the code at google.com/device.
3. Put them in one `feeds.json`: `{"spotify": {...spotify.json...}, "youtube": {...youtube.json...}, "jellyfin": {"server": "http://host:8096"}}`
   and copy it to `/sdcard/Android/data/com.gathin.tvhome/files/feeds.json`. The launcher reads it into its private storage
   and deletes it. The scripts print their output files with mode 600; delete them afterwards.
4. Build and install as upstream does (`launcher/build-and-install.sh`), JDK 17 and Android SDK 34. This branch's APK is about
   555 KB, still under upstream's 576 KB size gate.

## Differences from upstream you should know about

- It will not be merged upstream: network access and sign-in tokens are against that project's rules (see its AGENTS.md).
- Upstream changes are not picked up automatically; rebase this branch onto `main` yourself.
- The wallpaper and theme used on the author's TV are not in this branch.

Licence: MIT (CleanYourTV) for the code; `launcher/FONT-LICENSE-GOOGLE-SANS-FLEX-OFL.txt` and
`launcher/FONT-LICENSE-OFL.txt` for the fonts.
