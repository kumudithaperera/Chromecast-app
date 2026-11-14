# Chromecast App: Project Notes

Status: phone remote built (untested on a TV). TV browser + phone trackpad prototype installed and tested on the Chromecast itself (11/10): Android 14, Widevine L1, 25 of 25 live checks pass. The phone app has not been tried against the real TV yet. Home-screen widget and new browser features (TV player, ad lists, zoom, split view, site helpers) built 11/12, not yet run on a device.

## Device

- Chromecast with Google TV, now on Android 14 (auto-updated, confirmed 11/4; was Android 12). Design and test against Android 14 only. The Android 12 baseline is gone.
- Small storage, little RAM. Any TV-side app must stay light.
- Stack chosen: native Android TV (Kotlin).
- Phone: Android (confirmed 11/4). Phone app can be Kotlin; Quick Settings tile, widgets and share target are available.

## Problems to solve

1. Too few apps available.
2. No usable browser. The Chromecast with Google TV ships without one, and a D-pad is poor for browsing.

## Feature ideas

### Phone as remote
- Remote with app shortcuts, a keyboard that types into TV search boxes, voice-to-text, swipe trackpad.
- Macros ("Movie night": open an app, set volume, dim lights via Home Assistant).
- Send to TV from the phone's share sheet (links, photos, videos).
- Wake and power through HDMI-CEC and the network.
- Uses the Android TV Remote protocol (needs a pairing code on first use).

### Browser (WebView)
- A WebView is Android's embedded Chrome engine with no browser interface, so we build the address bar, tabs, bookmarks and the rest.
- Can do: modern sites, web video, custom user-agent, navigation interception, popup and new-window control, JS injection (for the phone-driven cursor), downloads, uploads.
- Can't or won't: Chrome extensions (ad blocking must be built in), account sync, guaranteed DRM playback (needs a Widevine test on the device), fast performance on heavy pages. Some sites block WebViews.
- Phone becomes trackpad and keyboard. Also: send page from phone, reader mode, video mode, Bluetooth mouse and keyboard, cast-tab shortcut.

### App shortage
- Web apps as home-row tiles (full-screen WebView with a TV user-agent).
- Sideload manager (APK from URL or phone, version tracking; needs "install unknown apps" granted once).
- Phone-app compatibility launcher, using the phone as touch input (hit and miss).
- App availability check (TV app, web fallback, or cast).
- Cast and mirror shortcut.

### TV-side utilities
- Storage and app manager, cache cleaner, unused-app finder.
- Custom screensaver or dashboard (clock, weather, photos, calendar, smart-home).
- Network speed and Wi-Fi checker.
- Unified launcher row.
- Health monitor (temperature, RAM, storage).

### Other
- Unified watchlist and universal search.
- File transfer, local media player (SMB, DLNA, USB, subtitles), clipboard sync.
- Smart-home hooks (Home Assistant).
- Parental controls and screen time (via ADB).
- Backup of settings and app list (useful before the OS update).

## Constraints

- No root: no system-setting changes beyond what ADB allows.
- Sideload with ADB unless published to the Play Store.
- Low RAM: browser plus background work will be slow.
- The browser will not replace Chrome. Aimed at reading, search and light browsing.

## Decisions so far

- Suggested first version: phone remote (app shortcuts, keyboard, trackpad), send-to-TV, storage cleaner.
- Suggested priority: (1) remote with trackpad and keyboard, (2) WebView browser with send-to-TV, (3) web-app tiles, (4) sideload manager and storage cleaner, (5) smart-home and the rest.
- Riskiest piece to prototype first: browser plus phone trackpad (DRM, cursor feel).
- A streaming-site link (flixmomo) was proposed as a test target. Not used: it looks like an unlicensed streaming site, and the project won't be tuned for pirated streams. Those sites also tend to have malicious redirects.
- Test targets instead: YouTube, free legal services (Tubi, Pluto TV, Plex free tier), own pages, local media servers (Jellyfin, Plex).

## Research findings (2026-11-04, five parallel research passes)

- The pending update is very likely Android 14. On this box it makes Wireless debugging off by default, random ports, and a `tcpip 5555` switch that does not survive reboot. Any ADB-based feature must survive that, or the update should wait until the ADB flow is proven on Android 12.
- Android TV Remote v2 protocol covers keys, text, voice, deep links and wake keys with no TV-side RAM cost. No maintained Kotlin library; port from androidtvremote2 (Python).
- TV Bro (open source, WebView or GeckoView, ad blocker) is a candidate base for the browser instead of writing one from scratch. Cursor quality unverified.
- Do not build, install instead: SmartTube (gray area, YouTube ToS), android-airplay-server (AirPlay), TvOverlay (notifications), official Jellyfin client, Moonlight.
- Silent install works only for updating apps the installer installed itself. First installs always prompt.
- Unverified, test on the box first: Widevine level in WebView, Wireless debugging on the current build, `pm trim-caches`, `dumpsys thermalservice`, CEC from app, ADB in standby, whether Google's 2026 sideload verification covers TVs.

## Open-source survey (11/12)

- Sources: GitHub search (~250 Android TV / Google TV / Chromecast repos), the Awesome-Android-TV-FOSS-Apps list, and READMEs of the closest projects. Star counts on some repos look inflated; not used as a quality signal.
- Closest to this project: man15h/tellyscope (GPL-3.0, phone app over ADB: apps, installs, files, debloat with undo, remote), HoangTran0410/tv-remote (MIT, remote v2 plus ADB: TV picture on phone, kids limits, DNS filter, cache clean, scenes, widget, tiles), souravkl11/android-tv-remote (Kotlin remote v2 with QS tile), Seagey/couch-tv (MIT, home screen plus phone remote, "put on X").
- Browser ideas: m-salehi-v/mrowser (MIT) sniffs HLS/DASH/MP4 from the WebView and plays it in ExoPlayer for better A/V sync, with a subtitle timing nudge, filter-list ad blocking and system-browser registration. GeckoView browsers (ShowFox, FluxTV) run uBlock Origin, so extensions are possible with GeckoView, at the cost of a much bigger app and more RAM. Both are unproven prototypes.
- Chromecast warning (from cleanyourtv): disabling the stock Google TV launcher on a Chromecast breaks phone casting, and the setup wizard can take Home and crash-loop. Any custom home screen must not disable the stock one.
- SponsorBlock without touching the TV: iSponsorBlockTV / CastSponsorSkip drive the official YouTube app over YouTube's Lounge protocol from another device. Undocumented API; pairings broke in April 2026.

## Phone remote app (built 11/4, not yet tested on real hardware)

- Code: `phone/` (Kotlin, Jetpack Compose, minSdk 26). Package `app.tvremote.phone`.
- Build: `./gradlew :phone:assembleDebug` → `phone/build/outputs/apk/debug/phone-debug.apk` (~12.7 MB).
- Install (phone plugged in, USB debugging on): `adb install -r phone/build/outputs/apk/debug/phone-debug.apk`.
- Tests: `./gradlew :phone:testDebugUnitTest`, 9 tests, including pairing and the remote session against a fake TV over real TLS. The pairing hash is checked against Python's androidtvremote2 formula.
- Uses the Android TV Remote v2 protocol directly (no ADB). Protobuf is hand-encoded, so there is no protobuf dependency. The client certificate is made with BouncyCastle and stored in app storage; the TV certificate is pinned on first connect.
- Features: TV discovery (mDNS) plus manual IP, pairing with the code on the TV, D-pad, touchpad, power/home/back/settings, media keys, volume (the phone's volume buttons too), live keyboard, voice typing via the phone's speech recognizer, app shortcuts (editable deep links), and "Share → Open on TV".
- Not built yet: Quick Settings tile, macros, voice streamed to the TV assistant. (Home-screen widget built 11/12, see below.)

### Volume keys did nothing (found and fixed 11/10)

- Cause: the Chromecast ("Bedroom TV", sabrina, Android 14) had HDMI-CEC volume control switched off (`volume_control_enabled: 0`, default 1; CEC itself was on and the TV answers it). With it off the Chromecast ignores volume keys that don't come from its own remote, including the phone app's. The TV's Settings ▸ HDMI-CEC page has no volume toggle, so it can't be changed from the TV menu. Likely cause: setting up the remote's IR volume buttons turns CEC volume off (not confirmed).
- Fix applied on the Chromecast over adb: `adb shell cmd hdmi_control cec_setting set volume_control_enabled 1`. Undo with `... 0`. Check with `adb shell dumpsys hdmi_control | grep -i volume_control`. Re-running Google TV's "Set up remote buttons" may switch it off again.
- Verified: with it on, the phone app's volume down, up and mute each send `<User Control Pressed>` (Volume down / up / mute) to the TV and the TV acknowledges; with it off nothing is sent.
- Side effect to check: with it on, the Chromecast remote's own volume buttons could step twice (IR plus CEC). Not tested.
- App changes: the "Vol 0" in the header was a made-up default, since the Chromecast sends no level or maximum, so it's gone. The TV's volume message field 4 was 0 with CEC volume off and 1 with it on; the Remote tab shows a note under the volume keys when it is 0. The Chromecast never reports a volume number, so none is shown.
- Network note: adb to the Chromecast needs the Mac on the same Wi-Fi.


## TV browser + phone trackpad prototype (built 11/10)

- Code: `tv/` (Android TV app, plain Views, ~950 KB APK), `protocol/` (shared JVM module: wire format, auth, server, client, URL rules, blocklist), trackpad screen in `phone/` (menu ▸ TV browser).
- Build: `./gradlew :tv:assembleDebug :phone:assembleDebug`. Install the TV app on the Chromecast with `adb install -r tv/build/outputs/apk/debug/tv-debug.apk`. Launch: `adb shell am start -n app.tvremote.tv/.BrowserActivity`.
- How it works: the TV app runs a WebView with an on-screen pointer and listens on TCP 8765 while it is on screen. The phone sends cursor moves, taps, two-finger scroll, typed text and navigation. The phone app's "Open the browser on the TV" button starts it through the existing remote link (`tvbrowser://home`). Any web link shared to the TV also opens in it, since it registers for http/https.
- Pairing: 6-digit PIN shown top right on the TV (Menu key toggles it while connected). Challenge-response, 5 wrong tries lock for 30 s, doubling. Traffic was not encrypted in this first version; fixed 11/15, see "TLS on the browser link" below.
- Browser policy: popups without a click blocked, automatic cross-site redirects blocked (phone shows "Blocked X, tap to allow"), only http/https load, seed ad-host blocklist (`tv/src/main/assets/blocklist.txt`), desktop user agent on by default, up to 5 tabs.
- TV D-pad also works: arrows move the pointer, OK clicks, Back goes back.
- Tests: `protocol` 22 and `phone` 19 JVM tests pass (gesture feel, wire format, auth, lockout, oversized input, real sockets). Live run on a Samsung SCG22 (Android 16, Widevine L1): 24 of 24 checks pass over the real protocol (hover, click, typing, scroll, popups, redirects, blocked schemes, ad host, desktop toggle, lockout).
- Chromecast results (11/10): reports Widevine L1; all protocol checks pass there, including hover, click, typing, scroll, popups, redirects, blocked schemes, ad host, desktop toggle, lockout and Enter-submits-form. One real bug found only on Google TV: the raw Backspace key emptied the whole field (the Android TV emulator did the same). Backspace is now a scripted one-character delete with the key as fallback.
- Not verified: a real phone-app-to-TV session with finger gestures (the two apps ran separately), cursor feel by hand, actual DRM video playback (only the Widevine level and the start-page capability check), page speed on heavy sites, downloads, file uploads.
- Known limits: no encryption (fixed 11/15), global lockout, seed blocklist only, a click-initiated popup still opens a tab, the layout assumes a landscape TV.

## Phone-side video controls, speed menu, scroll strip (built 11/10)

- Video controls live only on the phone; the TV shows just the video. While a video is full screen on the TV, a floating **Video** button appears over the trackpad (top right). It opens a bottom sheet over the browser screen with: title, scrubber with times, play/pause, back and forward 10 s, TV volume down / mute / up (sent through the remote link), playback speed, Exit full screen. No screen switching.
- Speed: a menu of standard speeds (0.5, 0.75, Normal, 1.25, 1.5, 2) instead of a cycling key. The old key cycled from the last reported rate and the wire format rounded to one decimal (0.75 became 0.8, 1.25 became 1.3), so quick taps skipped speeds. Both fixed; speed now travels with two decimals.
- How it works: `tv/.../VideoBridge.kt` injects a document-start script into every frame (so players inside cross-origin iframes work) and talks to it over a WebView message listener. If a page's player can't be reached the phone still learns the TV is full screen, and play/pause falls back to the media key. Needs WebView with frame-wide scripts (the Chromecast has 156).
- Scroll strip: a separate strip beside the trackpad. Drag up or down with one finger to scroll (dragging down scrolls down, like a scrollbar); tap its arrows to move about a screenful. Two-finger scrolling on the trackpad still works.
- Canvas frames added or updated: "Browser: video controls (sheet over the trackpad)", "Browser trackpad: Video button (full screen on TV)", "Playback speed menu (overlay)", and the scroll strip on both trackpad frames.
- Verified on the Chromecast over the control protocol (44 of 44 checks: find the video before and after full screen, direct and inside an iframe, pause, resume, seek, skip, mute, exact speeds, exit full screen, still controllable after 5 s paused) and on the phone (Video button appears on full screen, sheet opens, speed menu, quick speed changes end on the last choice, scroll strip drags and arrows scroll the TV page the right way).
- Not verified: DRM-protected players (their video element may hide its position), live streams, YouTube's own player UI.
- Speed snapping back (reported 11/10, fixed in `VideoBridge.kt`): on sites whose player keeps its own speed setting (the reported symptom: always back to 1.25), setting `video.playbackRate` was undone by the page. The TV script now calls the player's own `setPlaybackRate` when the video sits inside YouTube's `#movie_player`, sets the element's rate, and for 8 s undoes any other change to that video's rate (at most 20 times). Checked only in a Node simulation of the script (plain page, page that resets once, YouTube-style API): the old script ends at 1.25 on the resetting page, the new one at the chosen speed. Not checked on the TV or a real site. A page that fights every change still wins after 20 tries.

### Volume re-check (11/10, later)

- Chromecast CEC volume control is still on (`volume_control_enabled = 1`, CEC on). From the phone app's keys and the phone's own volume buttons, the Chromecast sends `<User Control Pressed>` Volume up / down / mute to the TV and the TV acknowledges each one at the HDMI link; it never replies with Feature Abort.
- Not verifiable from here: whether the TV then changes its volume (TVs don't report it over CEC). The TV's EDID maker is the generic ID "UTV", it reports no name or vendor over CEC, and the phone has no IR blaster. If the TV doesn't show a volume bar when you press volume, it probably ignores CEC volume from a playback device; options are the TV's own remote, or an IP control path if it is a smart TV (needs the model).

## History (TV) and Watch notes (phone) (built 11/10, not run on a device)

- **History** is on the TV browser start page: newest first, 8 shown, "Show all" for up to 60, remove one, "Clear history" with a second click to confirm. Stored in app-private SharedPreferences on the TV (`history`, up to 200 pages, one entry per address without the #fragment). Code: `tv/.../History.kt`, `HomeContent.kt` (HTML), `tv/src/main/assets/home.html`.
- History is recorded in `Tab.kt` on `doUpdateVisitedHistory` (so single-page sites like YouTube count) and titled from `onReceivedTitle`. The start page, Reader view and error page are never recorded. There is no private mode.
- Start page edits: links to `https://tvbrowser.invalid/x/...` (`hdel`, `hclear`) are intercepted in `shouldOverrideUrlLoading`, only while the tab is showing the start page, then the History section is swapped in place with `Tab.refreshHome`. No JavaScript bridge was added.
- **Notes live on the phone, not on the TV** (the TV start page has no Watching section and the `W` command is gone). The phone's bottom navigation is now Remote, Type, Apps, **Browser** and **Notes**. The Browser tab is the old trackpad screen (its menu no longer has "Back to the remote"; Back goes to the Remote tab, and switching tabs drops and later re-opens the link to the TV browser). The Notes tab is plain text: each show's name, then its notes as small "S2 E4 · 12:34 · 2 days ago" lines with the text under them. Tap a note to edit or delete it (two taps), tap a show's name to add another for it, plus in the header adds one by hand.
- Stored in the phone's SharedPreferences file `watch_notes` (up to 300). Code: `phone/.../data/WatchNotes.kt` (store, `groupByShow`), `ui/NotesScreen.kt`, `ui/QuickNote.kt` (`NoteSheet`, shared by new, edit and quick note), tabs in `ui/RemoteScreen.kt`.
- **Quick note** is only in the video panel (the floating Video button, shown while a video is full screen on the TV), not in the trackpad's key row. The sheet guesses show and episode from the page title (`guessEpisode` in `protocol/.../Watch.kt`, e.g. "Severance Season 2 Episode 4" gives Severance, S2 E4), offers "At 23:10" (video time from when the sheet was opened; not for live streams), and a note. Empty note text just marks the episode watched. The page address from the TV's STATE line is saved with the note.
- Tests: `protocol` WatchTest (10) and `phone` WatchNotesTest (4: grouping, ordering, last episode, resume link) pass; both APKs and the TV APK build. The phone build was installed on the SCG22 with adb.
- Not verified: anything on a device. The device tool refuses the SCG22 ("Android Emulator is missing"), so the Browser and Notes tabs, the sheets, saving from the video panel, and History on the start page have not been seen running.
- Known limits: the show/episode guess is a heuristic (a title like "S02E04 - Episode name | Site" guesses the episode name as the show; edit it in the sheet); renaming a show changes one note at a time; notes are not backed up or synced; the Doop canvas has no frames for these screens yet.

## Review pass (11/10, built, not run on a device)

Read all three modules and fixed what could be fixed without a device. Unit tests pass (protocol 37, phone 28), all APKs build. Nothing here has been seen running; adb showed the SCG22 as "unauthorized", so the builds are not installed.

Phone fixes:
- The tab bar hides while the keyboard is open, and tab content no longer leaves room for the system navigation bar twice (`RemoteScreen.kt`). Before, the Browser and Type tabs lost about a tab bar's height above the keyboard.
- Browser PIN keypad uses 54dp keys (`CodeEntry(keyHeight)`), because with the tab bar under it the 64dp keypad was taller than the SCG22's screen by my arithmetic.
- "Open the browser on the TV" stops spinning after 20 s and is disabled, with an explanation, when the remote isn't connected (it was a silent no-op with an endless spinner).
- A browser link that was working and dropped is retried up to 3 times, and again when the app returns to the foreground (`LinkState.Failed.dropped`).
- "Forget this TV" asks first.
- Notes: tapping a show's name starts a note with the next episode filled in ("+ S2 E5"; `nextEpisode` in `Watch.kt`).

TV fixes:
- Error page keeps the real address in the bar and on the phone, and Reload retries it (`Tab.failedUrl`, `Tab.reload()`). Reload also works from Reader view (back to the page) and the start page.
- Certificate errors show the error page instead of a blank one (`onReceivedSslError`; still never proceeds).
- A link sent to the TV at the 5-tab limit opens in the current tab instead of being dropped.
- TV remote D-pad: pushing the pointer against the bottom 6% of the screen, or the very top, scrolls the page.

Looked at and left alone: no TLS on the browser control channel (open decision), downloads unsupported, the address bar clears on focus instead of offering the current address for editing, video controls only while full screen, switching phone tabs drops and re-opens the browser link.

## Widget and browser additions (built 11/12, not run on a device)

No phone or TV was attached (adb listed none), so none of this has been seen running. Builds, unit tests (protocol 47, phone 28) and Android lint (no errors) pass.

**Home-screen widget (phone).** Quick Settings style tiles in the canvas palette, after the "Quick Settings tile and widget" frame. Three sizes picked by the launcher as it's resized: small (Home, Play/Pause, Volume down/up keys), medium (Power, Play/Pause, Volume down/up tiles), full (adds Mute, Home and the first four Apps-tab shortcuts with logos). Header: TV name, status lamp, app on the TV. A press connects if needed, sends the key, and the link drops 30 s later unless the app is open; it gives up after 8 s with a toast. If the TV wants a new pairing code, the widget cancels the pairing (nobody is there to type it) and says to open the app.
- Code: `phone/.../widget/RemoteWidget.kt` (draws), `WidgetActionReceiver.kt` (presses, not exported), `TvController.whenReady`, layouts `res/layout/widget_remote_{small,medium,full}.xml`, `res/xml/widget_remote_info.xml`. `RemoteApp` redraws the widget when the TV, connection, app on TV, power or shortcuts change.
- Uses the system sans font, not Schibsted Grotesk: not sure every launcher loads app fonts in widgets.
- Not checked: how it looks on the SCG22's launcher, whether the size breakpoints suit its grid, Power on the Chromecast from standby.

**TV browser.**
- **TV player.** The tab notes HLS (.m3u8), DASH (.mpd), plain video files and .vtt/.srt subtitles the page fetches (`protocol/.../Media.kt`). When one is found, a "TV player" pill appears on the TV toolbar and a "Video found on this page" row on the phone. It plays in Media3 ExoPlayer over the browser (`tv/.../NativePlayer.kt`) with the browser's User-Agent, the Referer the page sent, and cookies only if the stream is on the page's own site. The phone's existing video sheet drives it (play/pause, seek, ±10 s, speed, mute, "Back to the page"), plus subtitle timing ±0.5 s when subtitles were found. Subtitles are parsed and drawn by the app (`Subtitles.kt`), English picked if a file name says so. The TV remote works too: left/right skip 10 s, OK shows Media3's controls (speed, audio track), Back closes. Quality is chosen automatically by ExoPlayer; there's no manual quality picker. DRM streams fail and close with a message. Not tested on any real stream yet.
- **Ad lists.** HaGeZi Light (ads, trackers) and Pop-Up Ads, downloaded by the TV once a week from GitHub (`tv/.../AdLists.kt`), kept as 64-bit hashes (`HostBlocklist` in `Urls.kt`). Real lists checked on the Mac: 141,960 domains, 1.1 MB, 0.46 s to parse; none of 23 common sites (YouTube and its video hosts, Netflix, Plex, Tubi, Jellyfin, Google, GitHub…) blocked. The bundled seed list stays in use until the first download. HaGeZi's lists are GPL-3.0; the app downloads them, doesn't ship them.
- **Per-site ads switch.** The ads pill on the TV toolbar ("N ads blocked" / "Ads allowed here") toggles blocking for the site and reloads; the phone menu has "Allow / Block ads on this site". Stored on the TV.
- **Per-site zoom.** Phone menu: Zoom in / out / Reset (50–200 % in steps). CSS zoom set by a document-start script per site (`SiteScripts.kt`), so it sticks for that site's later pages. The TV toolbar shows a "%" pill when not 100 %; clicking it resets.
- **Split view.** Phone menu "Split screen (two tabs)": current tab left, next tab (or a new start page) right. The active pane has a yellow edge; clicking the other pane makes it active first. Typing, Back and the address bar act on the active pane only.
- **Site helpers.** `tv/src/main/assets/site-helpers.json`: press reject on OneTrust, Cookiebot, Didomi, CookieYes, Complianz, Osano, iubenda banners and Google/YouTube consent pages (first 30 s of a page only); press YouTube's "Skip ad", Netflix "Skip intro/recap", Prime Video and Disney+ skip buttons. Each button at most once per 10 s. Selectors are guesses from public markup; none checked on the live sites. Phone menu "Turn off/on site helpers". Logic checked in Node against a fake page.
- Already existed before this round: registers as the TV's browser for links, blocks popups without a click, closes popups that go to ad hosts.
- Not done: the Firefox engine (GeckoView) for extensions; decided against for size and RAM.
- TV debug APK: 2.3 MB → 5.6 MB, almost all Media3 (with Guava).
- Security notes: `security-reports/browser-player-adlists-widget-preflight.md`.

## Phone UX revamp (built 11/12, installed on the SCG22, not seen running)

Implements the "(revamp)" frames on the Doop canvas (placed above each original). Build, 32 phone unit tests and lint (0 errors) pass; installed on the SCG22 with adb. The device tool still refuses the phone ("Android Emulator is missing"), so no screen was looked at or tapped.

- **Remote tab:** header TV name with a chevron opens the TV list; status "Connected · YouTube on screen" (no volume number, ever); Power is its own round key in the header, TV settings moved into the ⋮ menu (menu now has icons and groups: TV settings / Switch TV, Pair again / Forget). Quick-launch row: the app on the TV (outlined, "On now") or the first shortcut, as many shortcuts as fit, then All apps (goes to the Apps tab). Compact D-pad/Touchpad switch. Pad (276dp, shrinks on narrow phones) beside a vertical volume rocker (`VolumeRocker` in `Kit.kt`). Back (wide, labelled), Home and a voice key under the pad; media keys below.
- **Not connected:** a problem card ("Can't reach Living Room TV" + the reason) with Retry now / Pair again and Choose a TV; the quick-launch row and mode switch are hidden, the keys stay greyed. The unreachable message now reads "Nothing answered at <ip>. Is the TV on, and on the same Wi-Fi as this phone?".
- **Type tab:** mic inside the field, Recent searches (chips, saved when Search is pressed, up to 8, "Clear" removes them; stored in the `tv_remote` prefs as `recent_texts`), and a "Pick a result on the TV" row: arrows plus a yellow Search (Enter). The Voice type and Delete pills are gone (the keyboard has backspace).
- **Apps tab:** "On TV now" card with a Type button, shortcuts three across, Edit/Done in the header shows remove badges (long-press still removes), "Add an app" is a grid tile.
- **Choose your TV:** "Paired"/"New" tag per TV (from `Prefs.hasConnected`), "Looking for TVs · N found", a "TV not in the list?" checklist, and the IP field pre-filled with the phone's own network prefix (e.g. "192.168.1."); Connect stays off until a full address is typed.
- **Browser:** tab count box in the header (tap = next tab); Click moved into the key row so the trackpad is taller. Video sheet: one horizontal volume rocker, speed key with an icon, "Note at 14:08" instead of "Quick note" when the time is known.
- Not built from the revamp designs: Macros (not a feature yet), the widget changes (the widget never showed volume; its layouts belong to the 11/12 widget work), the blocked-ads count in the phone's address bar (the TV doesn't send a count), and "search box is active" in the Type header (the protocol doesn't say reliably).
- Backup of `phone/src` before this round: session scratchpad `phone-src-backup` (there is no git repo).

## Design (Doop canvas, 11/9; implemented 11/10)

- Canvas: https://doop.design/c/Hr1SH3K17z. Palette, type and icon rules are in its `design-brief` style guide.
- Pages: "Phone remote (built)" 11 screens, "Phone (planned)" 3 screens, "TV app (planned)" 9 screens.
- Implemented 11/10 (screens for features that exist):
  - Phone: Choose your TV, Pair (full-screen hex keypad, wrong-code state), Remote with D-pad / Touchpad / not-connected banner, menu, Type on TV, Apps grid with real logos and "On TV now", Add an app sheet, Browser trackpad (with the same keypad for the browser PIN). Remote / Type / Apps bottom tabs.
  - TV: the "TV: Browser" tab strip and toolbar (back, reload, address, Reader, ads-blocked count, phone cursor / PIN, bookmark). Toolbar items take the yellow focus ring when the pointer is over them and are clicked with it.
  - New because the designs show them: Reader mode (phone key and toolbar pill), bookmarks saved to the start page, per-page blocked-ad count.
  - Assets: Schibsted Grotesk and DM Mono bundled (OFL), Lucide icons converted from the canvas sprite to vector drawables (ISC), app logos from the canvas. Licences in each app's `assets/licenses/`.
- Deviations: the trackpad screen keeps Forward, Start page, tabs, desktop/mobile sites and pointer speed in its ⋮ menu (not in the design, but they existed); "Browser on TV" was added to the remote menu as the way in; the share sheet is the system one.
- Not implemented (planned pages, new features): Macros, Quick Settings tile and widget, TV Home, Find an app, Install apps, Storage, Health and network, Media, Search and watchlist, Dashboard screensaver. Decided 11/10 to leave these for a separate plan.
- Checked on the SCG22 phone: each built screen compared against its frame; 24/24 browser checks and 9/9 new-feature checks (Reader, ad count 5/5 loads, bookmarks, pointer clicks on the toolbar) pass. Pairing with a real TV and "On TV now" were not checked (no TV on hand).
- Known limit: the TV toolbar is sized for a 960dp-wide TV; on a narrow portrait screen the PIN pill crowds out the address field.

## TLS on the browser link (built and tested 11/15)

- Why: the browser link (TCP 8765) was plain text, so typed passwords and URLs were readable by anyone on the same Wi-Fi. Findings 13 and 14 in the preflight.
- What changed: `protocol/.../Tls.kt` (`PinnedTrust`, fingerprint helper), `Auth.kt` (proofs now cover role, nonce and the certificate fingerprint), `ControlServer.kt` (TLS 1.2/1.3 only, no plaintext mode; `OK <name> <tvproof>`), `ControlClient.kt` (pins the certificate, verifies the TV's proof, new failures `IdentityChanged`, `SecureFailed`, `Untrusted`). TV: `TvIdentity.kt` makes an EC key plus self-signed certificate in the Android Keystore. Phone: `BrowserLink`/`Prefs` remember the fingerprint per TV after the first successful pairing; `BrowserScreen` shows a warning if it changes.
- Decisions: no plaintext fallback (a downgrade path would defeat the point), so an old TV app and a new phone app can't talk; the phone says to update the TV app. A failed TLS handshake does not count towards the PIN lockout. The TV never sends a PIN-derived value before the phone has proved itself, so asking the TV for one is not an offline-guessing route.
- Bug found only on the real Chromecast: the Keystore key rejected every handshake (`INCOMPATIBLE_DIGEST`) because Android's TLS stack signs with the raw "no digest" mode. Fixed by allowing `DIGEST_NONE` on the key. JVM tests could not show this because they don't use the Keystore.
- Tests: `protocol` 48 and `phone` 33 pass; 7 new link tests (nothing in clear text, plaintext client gets no challenge, failed handshakes don't lock, pinned reconnect, different certificate refused before any PIN is sent, TV that can't prove the PIN rejected, plaintext TV refused by the phone). Lint: 0 errors in both apps.
- Checked on the real Chromecast (Android 14), from the Mac over Wi-Fi: TLS 1.3, same certificate on every connection, correct PIN accepted with the TV's proof verified, wrong PIN refused, proof made for another certificate refused, plaintext client gets nothing. The app's own Kotlin client was also run against it (first pairing, pinned reconnect, wrong certificate gives `IdentityChanged`, wrong PIN gives `WrongPin`).
- Not checked: the phone app's screens for this (the PIN screen with the identity-changed warning, the "update the TV app" message). Both apps were installed on the SCG22 and the Chromecast, but the phone UI was not driven.
- Remaining weakness at this point: the first pairing trusted whatever certificate the TV showed, and a device intercepting it could brute-force the 6-digit PIN from the proof. Fixed the same day, see next section.

## Pairing codes instead of a PIN (built and tested 11/15)

- Why: any proof built from a 6-digit PIN can be brute-forced offline in milliseconds by whoever receives it, so a device in the middle of the first connection could learn the PIN. A full PAKE (SPAKE2) needs elliptic-curve maths the TV app doesn't have without a big dependency.
- What: Bluetooth LE Secure Connections passkey entry. The TV shows a fresh 6-digit code per attempt; phone and TV check its 20 bits one at a time (phone commits, TV commits, phone opens, TV opens), each commitment an HMAC keyed by a random nonce over role, round, the certificate fingerprint that side sees, and the bit. A side without the code is caught at its first wrong guess having learnt one bit, and the next attempt has a new code. Success then hands the phone a random 256-bit key (`PAIRED <name> <id> <key>`); reconnections prove that key both ways (`AUTH <id> <hmac>`), bound to the certificate. The phone stores id, key and fingerprint (`Credential`).
- Code: `protocol/.../Auth.kt` (`Credential`, `PairedPhones`, `Auth`, `Passkey`), `ControlServer.pair()`, `ControlClient.pair()`, new failures `NotPaired`, `Busy`. TV: `PhoneStore.kt` replaces `PinStore.kt` (up to 8 phones, oldest dropped; removes the old PIN); the status pill shows "Pairing code 123456" while a phone pairs, plus a toast. Phone: `Prefs.browserCredential`, `BrowserLink` connects first and asks for the code when the TV shows it; a wrong code starts a new attempt automatically (the TV shows the next code); an identity change pairs again with a notice.
- Decisions: one pairing at a time (`ERR busy`), so nothing can swap the code on screen mid-attempt; abandoned or timed-out attempts count towards the lockout; a pairing that times out on the phone isn't retried unasked for the same reason.
- Tests: `protocol` 55 (25 link tests). New: pairing with the code on screen, reconnect with key and no code, wrong and non-numeric codes, fresh code per attempt, lockout, one pairing at a time, abandoned attempt clears the code, unknown key, a relay in the middle with its own certificate forwarding every byte while the person types the right code (fails, TV issues no key), an impostor TV without the code (phone reports `Untrusted`), plus primitive tests. Checked the relay test is real: with the fingerprint removed from the commitments it fails (the relay pairs). `phone` 33 pass and lint 0 errors, checked on a copy of the tree because the working tree also holds unfinished video-volume work (`prefs.videoVolume` missing) that doesn't compile yet.
- Checked on the real Chromecast from the Mac (Python client, code read from the TV screen with uiautomator): pairing succeeded, key reconnect with the TV's proof verified, wrong key refused, wrong code refused at bit 0, code removed from the screen afterwards. One test pairing from the Mac is now in the TV's list; its key existed only in that script.
- Not checked: the phone app's own pairing screens on a device (the phone build in the working tree doesn't compile until the video-volume change is finished, so the new phone APK was not installed).
- Still open: no on-TV way to remove paired phones except clearing the app's storage.

## Next steps

- [x] Confirm phone platform (Android or iPhone). Android.
- [x] Prototype the browser and phone trackpad first. Done 11/10.
- [x] Widevine level on the Chromecast: L1 (11/10).
- [ ] Play a DRM stream in the TV browser (start page ▸ DRM test streams) and judge speed.
- [x] TV app installed on the Chromecast over wireless debugging (11/10).
- [ ] Pair the phone app with the Chromecast and judge the pointer feel; tune `BASE_GAIN`, `MAX_BOOST` in `TrackpadTracker.kt`.
- [x] TLS for the control channel and safe first pairing (done 11/15).
- [ ] A "Forget paired phones" option on the TV.
- [ ] Scope and build the first version.

## Conversation log

| Date | Topic | Outcome |
|------|-------|---------|
| 11/2 1:23 AM | Initial idea: app to enhance the Chromecast | Phone remote and TV utility ideas; suggested first version |
| 11/2 1:31 AM | Fixing weak browser and app shortage; more features | Browser and app-gap fixes, extra features, priority list |
| 11/2 1:52 AM | What a WebView is; test with a streaming link | WebView capabilities and limits; declined the pirate-site link, suggested legal test sites |
| 11/4 | Full feature research, five agents | Findings section above; full report given in chat |
| 11/4 | Build the phone remote | `phone/` app builds, 9 JVM tests pass; not run on a device yet |
| 11/9 | Screens in Doop | New "Chromecast App" canvas, 23 frames on 3 pages |
| 11/10 | Prototype browser and trackpad | `tv/` and `protocol/` modules, trackpad screen in `phone/`; 24/24 live checks on a phone |
| 11/10 | Implement the Doop designs | Phone app restyled to the canvas (tabs, keypad pairing, trackpad); TV toolbar, Reader, bookmarks; checked on the phone |
| 11/10 | Install and test on the Chromecast | Installed over wireless ADB; Widevine L1; 25/25 live checks; fixed Backspace on Google TV |
| 11/10 | Volume keys not working | Root cause: HDMI-CEC volume control off on the Chromecast; switched on over adb; app no longer shows a fake Vol 0 and warns when volume is off |
| 11/10 | Video controls, speed menu, scroll strip, volume re-check | Floating Video button and sheet; fixed speed menu and rounding; scroll strip; volume messages confirmed reaching the TV |
| 11/10 | Whole-app review | Phone: keyboard/tab bar layout, PIN keypad size, stuck spinner, auto-reconnect, forget confirm, next-episode notes. TV: error page and reload, certificate errors, link at tab limit, D-pad edge scroll. Built and unit-tested only |
| 11/10 | History, Watching notes, Quick note | History on the TV start page; notes first built on the TV, then moved to the phone (Watch notes screen with edit and delete, shared note sheet, `W` command removed); builds and unit tests pass; not run on a device |
| 11/12 | Survey of open-source TV apps on GitHub | Open-source survey section above; feature and app list given in chat |
| 11/12 | Widget and browser features from the survey | Home-screen widget; TV player handoff, maintained ad lists with per-site switch, per-site zoom, split view, site helpers; builds, 75 unit tests and lint pass; not run on a device |
| 11/12 | Revamp the phone screens in Doop, then implement them | 11 revamp frames on the canvas; Remote, Type, Apps, Choose your TV, Browser and video sheet rebuilt to match; 32 unit tests and lint pass; installed on the SCG22, not seen running |
