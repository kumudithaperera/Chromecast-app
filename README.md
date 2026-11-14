# Chromecast TV Remote and Browser

An Android phone app that controls a Chromecast with Google TV, plus a small
Android TV app that gives the TV a browser the phone can drive like a
trackpad.

> **Status: prototype (v0.1.0).** It has been built and unit-tested, and parts
> of it have run on a real Chromecast (Android 14) and an Android phone. Not
> everything has been seen running on a device. See [Known limits](#known-limits).

## What it does

**Phone app (`phone/`)**
- Finds TVs on the local network (mDNS) or takes an IP, then pairs with the
  code shown on the TV. It uses the Android TV Remote v2 protocol directly,
  with no ADB.
- D-pad, touchpad, power, home, back, volume, media keys, live keyboard,
  voice typing, app shortcuts and "Share, then Open on TV".
- Trackpad and browser control for the TV browser: cursor, taps, scroll, typed
  text, tabs, history, bookmarks and watch notes.
- Home-screen widget.

**TV app (`tv/`)**
- A WebView browser with an on-screen pointer, up to five tabs, popup and
  redirect blocking, a weekly-updated ad-host list, and per-site helpers.
- Pairs with the phone using a 6-digit PIN shown on the TV.

**Shared module (`protocol/`)**
- The wire format, authentication, client and server used between the phone
  and the TV browser.

## Build

Requires JDK 17 and the Android SDK (set `sdk.dir` in `local.properties`).

```sh
./gradlew :phone:assembleDebug :tv:assembleDebug
./gradlew :protocol:test :phone:testDebugUnitTest
```

APKs are written to `phone/build/outputs/apk/debug/` and
`tv/build/outputs/apk/debug/`. Install with `adb install -r <apk>`.

## Known limits

- **The TV browser's control channel is not encrypted.** Traffic between the
  phone and the TV browser (port 8765), including anything typed, is sent in
  clear text on the local network. Do not type passwords on a shared or guest
  Wi-Fi. The Android TV Remote v2 connection used by the remote is TLS with a
  pinned certificate; this limit applies to the browser link only.
- The ad-host list and site helpers are best effort. Sites change their pages.
- Several features were built without being run on a device. The project
  notes say which.

## Open source and credits

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the libraries, fonts,
icons and references this project uses or studied, with licenses and how each
is used.

## Project notes

[PROJECT_NOTES.md](PROJECT_NOTES.md) is the working log: decisions, bugs found
and how they were fixed, and what was and was not verified.
