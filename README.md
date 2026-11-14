# Chromecast TV Remote and Browser

An Android phone app that controls a Chromecast with Google TV, plus a small
Android TV app that gives the TV a browser the phone can drive like a
trackpad.

> **Status: prototype (v0.1.0).** It has been built and unit-tested, and parts
> of it have run on a real Chromecast (Android 14) and an Android phone. Not
> everything has been seen running on a device. Read [Security](#security)
> before using it on a network you don't control.

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
- Pairs with the phone using a 6-digit code shown on the TV.

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

## Security

The phone talks to the TV over two links, and both are encrypted.

| Link | Protection |
|---|---|
| Remote keys (Android TV Remote v2, to the Chromecast itself) | TLS. The phone pins the TV's certificate the first time it connects. |
| TV browser (port 8765: cursor, taps, typed text, URLs) | TLS 1.2/1.3 only, no plaintext fallback. The TV has its own certificate, kept in the Android Keystore. The phone pairs once by typing a code shown on the TV, and afterwards refuses any other certificate. |

**How pairing works.** When you connect a phone for the first time, the TV
shows a 6-digit code and you type it on the phone. The phone and the TV then
check the code against each other one bit at a time, each side committing to
its answer before it sees the other's. This is the method Bluetooth uses for
passkey pairing. Each answer is tied to the TLS certificate that side sees.
The code itself is never sent over the network, even encrypted.

After pairing, the TV gives the phone a random 256-bit key. Later connections
prove that key in both directions, with no code to type.

**What this protects against.**

- Someone on the same Wi-Fi (a guest network, a hotel, a flat share) can't read
  what you type into the TV browser, including passwords, or see the pages you
  open.
- They can't control the browser without the code on the TV screen.
- A device that sits between your phone and the TV, including during the very
  first pairing, can't complete pairing. Without the code it has to guess one
  bit at a time, and each wrong guess ends the attempt. Every attempt gets a
  new code, so its chance is about one in a million per try.
- Five failed attempts lock the TV out for 30 seconds, doubling each time.

**What to know before you rely on it.**

- **Earlier builds were not encrypted.** Versions before this change sent the
  browser link in clear text, including typed passwords. Update both apps. A
  new phone app will not talk to an old TV app, and the phone says so.
- **Anyone who can see the TV screen while a phone is pairing can pair.** That
  is the point of showing the code, but it means pairing in a shared room lets
  people there pair too. Paired phones are kept on the TV (up to eight). To
  remove them all, clear the TV app's storage (Settings, then Apps, then TV
  Browser).
- **If the phone says the TV's security identity changed, it pairs again.**
  This is normal after reinstalling the TV app or clearing its storage. The
  code check still protects that new pairing.
- **The browser itself is a normal WebView.** Websites you open can still
  track you or be malicious. Pages on plain `http://` are visible to the
  network, as in any browser; this fix protects the phone-to-TV link, not the
  TV-to-website link.
- This is a hobby project that has not had an independent security review.

Found a problem? Open an issue on this repository.

## Known limits

- The ad-host list and site helpers are best effort. Sites change their pages.
- Several features were built without being run on a device. The project
  notes say which.

## Open source and credits

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the libraries, fonts,
icons and references this project uses or studied, with licenses and how each
is used.

## License

Apache License 2.0. See [LICENSE](LICENSE). Third-party components keep their
own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Project notes

[PROJECT_NOTES.md](PROJECT_NOTES.md) is the working log: decisions, bugs found
and how they were fixed, and what was and was not verified.
