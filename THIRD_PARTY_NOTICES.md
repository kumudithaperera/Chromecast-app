# Third-party notices

This project uses the open-source software, fonts and icons listed below.
It also records what it only studied or followed as a specification, so the
line between "used" and "learned from" is clear.

Last checked against the build files on 2026-11-15. If a dependency in a
`build.gradle.kts` changes, update this file.

## 1. Libraries linked into the apps

Versions are taken from `phone/build.gradle.kts`, `tv/build.gradle.kts` and
`protocol/build.gradle.kts`.

| Library | Version | License | Used by | Why |
|---|---|---|---|---|
| Jetpack Compose (BOM, `ui`, `foundation`, `material3`, `material-icons-core`) | 2024.09.00 | Apache-2.0 | phone | Phone UI |
| AndroidX Activity Compose | 1.9.3 | Apache-2.0 | phone | Compose host activity |
| AndroidX Core KTX | 1.15.0 | Apache-2.0 | phone | Android helpers |
| AndroidX WebKit | 1.12.1 | Apache-2.0 | tv | Reaches video players inside cross-origin iframes, which plain `evaluateJavascript` cannot |
| kotlinx.coroutines (Android) | 1.9.0 | Apache-2.0 | phone | Async work |
| Bouncy Castle `bcpkix-jdk18on` (with `bcprov` and `bcutil`) | 1.79 | Bouncy Castle License (MIT-style) | phone | Builds the self-signed client certificate the TV pairs with |
| Kotlin standard library | 2.1.20 (compiler) | Apache-2.0 | all | Language runtime |

The linked libraries also pull in their own transitive dependencies (AndroidX
and Compose runtime artifacts, Kotlin libraries). These are Apache-2.0.

### Test and build tooling (not shipped in the apps)

| Tool | Version | License |
|---|---|---|
| JUnit 4 | 4.13.2 | EPL-1.0 |
| Gradle wrapper | 8.14.3 | Apache-2.0 |
| Android Gradle Plugin | 8.13.2 | Apache-2.0 |
| Kotlin Gradle plugins | 2.1.20 | Apache-2.0 |

## 2. Fonts and icons bundled in the repo

Full license texts are in `phone/src/main/assets/licenses/` and
`tv/src/main/assets/licenses/`.

| Asset | License | Copyright holder |
|---|---|---|
| Schibsted Grotesk (font) | SIL OFL 1.1 | The Schibsted-Grotesk Project Authors |
| DM Mono (font) | SIL OFL 1.1 | The DM Mono Project Authors |
| Lucide icons (converted to Android vector drawables, `ic_*.xml`) | ISC | Lucide Contributors; portions Cole Bemis (Feather, MIT) |

## 3. Data fetched at runtime, not shipped

| Data | License | How it is used |
|---|---|---|
| [EasyList and EasyPrivacy](https://easylist.to/) | GPL-3.0 or CC BY-SA 3.0 | Filter lists for the Standard and Strict levels. |
| [uBlock Origin filter lists](https://github.com/uBlockOrigin/uAssets) (filters, badware, privacy, quick fixes, unbreak, and the files `filters.txt` includes) | GPL-3.0 | Filter lists for the Standard and Strict levels, including the scriptlet rules (`##+js(…)`) that the app's own scriptlets carry out. |
| [HaGeZi DNS blocklists](https://github.com/hagezi/dns-blocklists) ("Pop-Up Ads"; "Light" for Strict only) | GPL-3.0 | Host lists for pop-up ad networks and, at the Strict level, ad and tracker hosts. |

The TV app downloads these lists over HTTPS once a week, keeps them as files on
the device, and builds its filter from them there. None of them is stored in
this repository or bundled in the APK.

`tv/src/main/assets/blocklist.txt` is a short seed list written for this
project. It is the Basic level, and the only list in use until the first
download succeeds.

## 4. Specifications and references (no code copied)

| Source | License | Relationship |
|---|---|---|
| Android TV Remote v2 protocol, including the pairing ("polo") messages from AOSP's google-tv-pairing-protocol | Apache-2.0 (AOSP) | The message layout is implemented from the specification, in `phone/.../atv/Pairing.kt` and `Remote.kt`. The protobuf encoder in `phone/.../proto/Proto.kt` is hand-written. |
| [androidtvremote2](https://github.com/tronikos/androidtvremote2) (Python, by tronikos) | Apache-2.0 | Used as a behavioral reference: the shape of the client certificate and the pairing-secret formula. The unit tests check the pairing hash against its formula. The code is a Kotlin reimplementation, not a line-by-line port. |

| Adblock Plus and uBlock Origin filter syntax, and uBlock Origin's scriptlet documentation | (documentation) | The filter engine (`protocol/.../AdFilter.kt`) reads the syntax as documented. The scriptlets in `tv/src/main/assets/adblock/scriptlets.js` are this project's own code, written from the documented behavior; no uBlock Origin or AdGuard scriptlet code is copied (theirs is GPL-3.0). |
| Bluetooth Core Specification, LE Secure Connections passkey entry (Vol 3, Part H, 2.3.5.6.3) | Bluetooth SIG specification | Design reference for the TV browser's first pairing (bit-by-bit code check). Implemented with HMAC-SHA256 in `protocol/.../Auth.kt` (`Passkey`); no code copied. |

## 5. Projects studied for ideas only

These were read during research. No code from them is in this repository.

| Project | License | What informed the design |
|---|---|---|
| tellyscope | GPL-3.0 | Survey of what a phone-side TV tool can do |
| tv-remote (HoangTran0410) | MIT | Survey of remote and widget features |
| couch-tv (Seagey) | MIT | Survey of home-screen ideas |
| mrowser (m-salehi-v) | MIT | Idea of playing sniffed streams in a native player |
| TV Bro | custom (redistribution restricted) | Considered as a browser base, not used. Its ad blocking module showed the approach of a Kotlin filter engine for a TV WebView (request types guessed from the Accept header, element hiding by injected CSS). Its license doesn't allow reuse, so none of its code was copied. |
| [Brave adblock-rust](https://github.com/brave/adblock-rust), [Ghostery adblocker](https://github.com/ghostery/adblocker) | MPL-2.0 | Reference engines. Ghostery's was run during development to cross-check this engine's verdicts on the same lists; it is not part of the build. |

## 6. Note on the app's own license

This project's own code is licensed under Apache-2.0 (see `LICENSE`). The
third-party components in sections 1 to 3 keep their own licenses, and the apps
must keep the notices above when distributed.
