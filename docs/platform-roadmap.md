# Établi Focus — platform roadmap

Status as of 2026-11-08. This document says what runs where today, what has
been verified and how, and the ordered work that takes each platform to a
signed, distributable release. Verification claims are deliberately narrow:
"CI" means a GitHub Actions job definition exists or ran, "local" means it was
run on a Linux development machine, and anything not listed was not checked.

## 1. Platform role

Établi Focus is a **both** app: desktop and phone matter equally. Focused work
mostly happens at the desk, so the desktop app is where blocks are started,
paused and reviewed during the day; the phone is the companion for blocks away
from the computer and for glancing at today's plan.

| Platform | What it is for |
|---|---|
| Ubuntu/Debian | Desk work on Linux: timer beside the work, tray countdown, weekly review in Stats. |
| Windows | The same desktop role on Windows workstations. |
| macOS | The same desktop role on Macs (menu-bar countdown via the tray item). |
| iOS | Companion: blocks away from the desk, today's plan, notifications at block end. |
| Android | Companion with the same role as iOS; currently the most complete and most verified build. |

Each installation keeps its own local history. There is no account, server or
sync between devices, and none is planned in this roadmap.

## 2. Current state

| Platform | Status | Technology | How it is built | What is verified | Distribution |
|---|---|---|---|---|---|
| Ubuntu/Debian | Development preview (new, `desktop/`) | Compose Multiplatform 1.9.0 desktop, Kotlin 2.2.20, bundled JDK 17 runtime; SQLite (sqlite-jdbc) + Preferences DataStore | `cd desktop && ./gradlew packageDeb` | Local, 2026-11-08 (Ubuntu 26.04 host): 28 unit tests pass; `scripts/smoke-test.sh` passes for the app image and for the launcher extracted from the `.deb`; `.deb` inspected with `dpkg-deb -I/-c` (not installed system-wide). A scripted UI journey on a private Xvfb display: 5-minute block started, paused, process terminated and relaunched with the paused remaining time intact, resumed, completed at its deadline with one history row; a second block left running while the app was closed was recorded once, dated at its deadline, on the next launch; targets set in Template showed against completions in Today and Stats; the theme choice survived a restart. The completion notification was checked only as the `notify-send` call (a stub recorded it); no notification was displayed and the tray was not seen (Xvfb has neither). | CI artifact only (`desktop.yml`, job `desktop (deb)`); not yet run because nothing is pushed. |
| Windows | Development preview, **not verified locally (CI only)** | Same desktop code | `./gradlew packageMsi` on `windows-latest` | Nothing yet: the job is defined but has not run. | CI artifact (unsigned MSI). |
| macOS | Development preview, **not verified locally (CI only)** | Same desktop code | `./gradlew packageDmg` on `macos-15` (Apple Silicon) | Nothing yet: the job is defined but has not run. | CI artifact (unsigned, not notarized DMG, arm64 only). |
| iOS | Source complete, unreleased | SwiftUI + SwiftData, local notifications | Xcode project `ios/EtabliFocus.xcodeproj`; CI builds the unsigned simulator app | CI: unsigned simulator build passed (run 41484782973, commit 269b7f5, 2026-11-02). `scripts/check_timer.swift` checks persisted-timer compatibility. No simulator or device run is recorded. | None. TestFlight/App Store planned. |
| Android | Development builds | Kotlin, Jetpack Compose, Room 2.6.1, DataStore, AlarmManager | `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease` | CI: tests, lint, debug APK, unsigned AAB passed (run 41484782973). Local API 35 emulator journey on 2026-11-02 (see `docs/refinement-2026-10.md`). Physical devices, Doze/reboot and API 36/16 KiB not verified. | Debug-signed `focus-0.1.0.apk` pre-release on GitHub Releases (2026-06-24; predates the October fixes). Google Play and F-Droid planned; F-Droid/Play listing text exists in `fastlane/metadata/android/`. |

## 3. Architecture

### Now

Three codebases implement one contract:

- **Android** (`android/`): `PomodoroEngine` keeps the active timer in a
  Preferences DataStore (deadline, paused remaining time, original start) and
  writes finished blocks to Room (`category`, `session`, `template_entry`,
  schema version 2).
- **iOS** (`ios/`): the same contract in Swift; timer state as JSON in
  UserDefaults, history in SwiftData.
- **Desktop** (`desktop/`): a Compose Multiplatform JVM app. Its domain code is
  a deliberate near-copy of Android's so that the two can merge later:
  - `engine/TimerState.kt` and `engine/SessionDays.kt` are verbatim copies;
    `engine/PomodoroEngine.kt` keeps Android's command logic (start, pause,
    resume, cancel, tick, start-up restore) unchanged, replaces the Android
    wiring (Context, AlarmManager) with an in-process wall-clock alarm and adds
    a completion callback for desktop notifications.
  - Timer state uses the **JVM artifact of Preferences DataStore** with the same
    keys and file name as Android (`timer_state.preferences_pb`).
  - History uses **SQLite through sqlite-jdbc** with exactly the DDL Room
    generates for the Android entities and `PRAGMA user_version = 2`. DAO
    interfaces have the same names and signatures as the Room DAOs.
  - UI tokens (`ui/theme/CoderTheme.kt`) are copied from Android, which mirrors
    the iOS `Theme.swift`; accent `#28A745` as in `design/coder-design-system.json`.

Why SQLite via sqlite-jdbc and not JSON or Room right now: completion must be
written exactly once even when it is reconciled at start-up after a crash, and
Stats queries by date range. SQLite gives idempotent `INSERT OR REPLACE` by
session id and indexed queries for free; a JSON file would need its own atomic
rewrite and query code, so it is not simpler. Room KMP would need the Android
module to move from Kotlin 1.9/Room 2.6 to Kotlin 2.x/KSP2/Room 2.7 first, which
cannot be verified on the machine where the desktop app was created (no Android
SDK). Keeping Room's exact schema is meant to make the later switch a
dependency change rather than a data migration; milestone M4 proves this with
a test before sqlite-jdbc is removed.

Desktop-only behaviour: a system-tray item shows the remaining time and offers
Pause/Resume, Show window and Quit; the window title shows the countdown;
closing the window while a block is active hides it to the tray (when the
desktop offers a tray), otherwise closing quits. Block completion posts a
notification through `notify-send` on Linux, or through the tray elsewhere. A
file lock prevents two instances from driving the same timer. Data lives in
`~/.local/share/etabli-focus` (Linux), `~/Library/Application Support/com.raban.etabli.focus`
(macOS) or `%APPDATA%\EtabliFocus` (Windows).

### Target

```
shared/            Kotlin Multiplatform: commonMain + androidMain + jvmMain
  engine/          TimerState, PomodoroEngine, SessionDays, planning/targets
  data/            entities, Room KMP database + DAOs, DataStore KMP timer store
android/           Android app (UI, AlarmManager, notifications) -> depends on shared
desktop/           Compose desktop app (UI, tray, notifications)  -> depends on shared
ios/               SwiftUI app, unchanged
```

- **Room KMP** (androidx.room 2.7+, KSP, `BundledSQLiteDriver` on JVM) replaces
  sqlite-jdbc; the Android migration 1→2 moves with it.
- **DataStore KMP** (`datastore-preferences-core`) holds timer state on both
  platforms; the desktop already uses it.
- Sharing Compose UI between Android and desktop is possible afterwards but is
  not required for parity (owner decision, section 6).
- iOS stays SwiftUI. Parity with iOS is kept by the shared contract and its
  checks (`scripts/check_timer.swift`), not by shared code.

## 4. Per-platform execution

### Ubuntu/Debian

- **Build and run:**
  ```sh
  cd desktop
  ./gradlew test                                   # unit tests
  ./gradlew run                                    # development run
  ./gradlew createDistributable && scripts/smoke-test.sh   # headless launch check
  ./gradlew packageDeb                             # build/compose/binaries/main/deb/etabli-focus_0.1.0-1_amd64.deb
  sudo apt install ./build/compose/binaries/main/deb/etabli-focus_0.1.0-1_amd64.deb
  ```
  JDK 17 is provisioned through the Gradle toolchain (Temurin is downloaded if
  no JDK 17 is installed); `fakeroot` and `dpkg-deb` are needed for `.deb`.
- **Formats:** `.deb` for Ubuntu/Debian (installs to `/opt/etabli-focus`, menu
  entry via `xdg-desktop-menu`). jpackage derives `Depends:` from the build
  host, so a `.deb` built on Ubuntu 24.04 names the 64-bit-time packages
  (`libasound2t64`, `libpng16-16t64`) and targets Ubuntu 24.04+ and Debian 13+;
  Debian 12 needs a second `.deb` built on a Debian 12 (or Ubuntu 22.04)
  runner. Add an AppImage (wrap the `createDistributable` output with
  `appimagetool`) for other distributions; `packageRpm` is available from the
  same plugin if Fedora users ask. Flatpak/Flathub is optional and heavier
  (offline Gradle build inside flatpak-builder).
- **Signing prerequisites:** none for `.deb` files attached to GitHub Releases
  (publish SHA-256 sums). An apt repository would need a GPG signing key.
- **CI:** `.github/workflows/desktop.yml`, job `desktop (deb)` on
  `ubuntu-24.04`: unit tests, headless smoke test under xvfb, `packageDeb`,
  artifact `etabli-focus-desktop-deb`.
- **Acceptance:** on clean Ubuntu 24.04 and Debian 13 installs, the package
  installs and appears in the app menu with its icon; a block can be started,
  paused, resumed and reset; quitting while paused and relaunching keeps the
  remaining time; a block that ends while the app is closed is recorded once at
  the next launch; a completion notification appears; on Ubuntu (AppIndicator
  extension) the tray shows the countdown; `apt remove` leaves the data folder
  intact.

### Windows

- **Build:** `cd desktop && ./gradlew packageMsi` on Windows (the Compose
  plugin downloads WiX). Output: `build/compose/binaries/main/msi/EtabliFocus-0.1.0.msi`.
- **Format:** MSI, per-user install (no administrator rights), Start-menu entry
  and desktop shortcut, fixed upgrade UUID so later versions upgrade in place.
  MSIX only if a Microsoft Store listing is wanted.
- **Signing prerequisites:** an Authenticode code-signing certificate (OV/EV
  from a CA) or an Azure Trusted Signing account; stored as CI secrets and
  applied with `signtool` after `packageMsi`. Unsigned MSIs trigger SmartScreen
  warnings.
- **CI:** job `desktop (msi)` on `windows-latest`: unit tests, `packageMsi`,
  artifact `etabli-focus-desktop-msi`. Not verified locally (CI only).
- **Acceptance:** installs on Windows 11 without elevation; tray icon with
  countdown and completion notification; data in `%APPDATA%\EtabliFocus`, apart from the install folder `%LOCALAPPDATA%\EtabliFocus`;
  installing 0.1.1 over 0.1.0 replaces it and keeps the data; uninstall keeps
  the data; once signed, `signtool verify /pa` succeeds.

### macOS

- **Build:** `cd desktop && ./gradlew packageDmg` on a Mac. `macos-15` runners
  are Apple Silicon; an Intel DMG needs a separate job on an Intel runner
  (jpackage does not build universal apps).
- **Version note:** macOS requires the bundle version to start at 1, so the DMG
  carries `1.0.0` while Linux and Windows carry `0.1.0`
  (`nativeDistributions.macOS.packageVersion` in `desktop/build.gradle.kts`).
- **Format:** DMG, bundle ID `com.raban.etabli.focus` (same as iOS).
- **Signing prerequisites:** Apple Developer Program membership; a *Developer
  ID Application* certificate (exported `.p12` + password as secrets); an App
  Store Connect API key (issuer ID, key ID, `.p8`) for `notarytool`. Configure
  `compose.desktop.application.nativeDistributions.macOS.signing` and
  `notarization`, then run `packageDmg notarizeDmg` and staple.
- **CI:** job `desktop (dmg)` on `macos-15`: unit tests, `packageDmg`, artifact
  `etabli-focus-desktop-dmg`. Not verified locally (CI only).
- **Acceptance:** `spctl -a -vv` accepts the app and `xcrun stapler validate`
  passes; first launch shows no Gatekeeper warning; notification permission is
  requested; the menu-bar item shows the countdown; data in
  `~/Library/Application Support/com.raban.etabli.focus`.

### iOS

- **Build:** open `ios/EtabliFocus.xcodeproj` in Xcode on macOS, scheme
  `EtabliFocus`; CI runs
  `xcodebuild -project ios/EtabliFocus.xcodeproj -scheme EtabliFocus -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO build`.
- **Format:** TestFlight build, then App Store release. Bundle ID
  `com.raban.etabli.focus`.
- **Signing prerequisites:** Apple Developer Program team; App Store Connect app
  record; distribution certificate and App Store provisioning profile (or
  automatic signing with an App Store Connect API key in CI); privacy details
  (the app ships `PrivacyInfo.xcprivacy`), screenshots and support URL.
- **CI:** add a tag-triggered job on `macos-15` that archives
  (`xcodebuild archive`), exports with an `ExportOptions.plist` generated in the
  job (the file is git-ignored) and uploads to TestFlight with the API key.
- **Acceptance:** TestFlight build installs on a physical iPhone; start, pause,
  force-quit, relaunch keeps the remaining time; a block ending while the app
  is closed is recorded once; notification delivered with permission granted
  and absent when denied; App Review passes.

### Android

- **Build:** see README (`./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease`).
- **Formats:** release-signed APK on GitHub Releases (replacing the
  debug-signed pre-release), AAB for Google Play, F-Droid build from source
  (listing metadata already in `fastlane/metadata/android/`; an fdroiddata
  recipe is still to be written).
- **Signing prerequisites:** the upload/release keystore via
  `android/keystore.properties` locally and as base64 + passwords in CI secrets;
  Play App Signing enrolment; Google Play developer account. See
  `android/SUBMISSION.md`.
- **CI:** `android.yml` already tests and packages; add a tag-triggered release
  job that signs the APK/AAB from secrets and attaches the APK to the release.
- **Acceptance:** `apksigner verify --print-certs` shows the release key;
  physical-device journeys incl. notification delivery, Doze and reboot; API 36
  with 16 KiB pages; Play internal testing track accepted; F-Droid build
  reproduces.

## 5. Milestones

1. **M1 — Desktop preview passes CI on all three systems.** Push `desktop/` and
   `desktop.yml`. Exit: the `deb`, `msi` and `dmg` jobs are green; the three
   artifacts download; the Linux acceptance list above is walked through on a
   real Ubuntu 24.04 session (tray, notification, menu entry) and the MSI and
   DMG are each launched once on a real machine.
2. **M2 — Desktop polish for a public preview.** Linux desktop entry named
   "Établi Focus" with a matching `StartupWMClass` (jpackage currently writes
   `Name=EtabliFocus`); AppImage alongside the `.deb`; release workflow that
   attaches `.deb`, AppImage, MSI and DMG plus `SHA256SUMS` to a GitHub
   pre-release on a tag. Exit: a `v0.2.0-desktop-preview`-style pre-release
   (tag name per owner) with the four files, installed and launched on each OS.
3. **M3 — Shared domain module.** Create `shared/` (Kotlin Multiplatform,
   targets `androidTarget` and `jvm`) holding `TimerState`, `PomodoroEngine`,
   `SessionDays`, planning and entities; delete the desktop copies; move the
   Android app to Kotlin 2.2 with the Compose compiler plugin and depend on
   `shared`. Exit: `android` CI and `desktop` CI green from one engine source;
   the engine tests run once in `shared` (JVM) and still cover pause budget,
   restart persistence and reconciliation.
4. **M4 — Room KMP and DataStore KMP in `shared`.** Room 2.7+ with KSP for
   Android and `BundledSQLiteDriver` for desktop; keep schema version 2 and
   `MIGRATION_1_2`; DataStore preferences in common code. Exit: a Room
   `MigrationTestHelper` test on Android; a desktop test that opens a database
   written by the sqlite-jdbc build with Room and reads its history; sqlite-jdbc
   removed.
5. **M5 — Signed releases.** Android release keystore in CI; Windows
   Authenticode signing; macOS Developer ID signing and notarization (arm64 and
   Intel); one tag produces all signed artifacts on GitHub Releases. Exit:
   `apksigner verify`, `signtool verify /pa`, `spctl -a -vv` and
   `xcrun stapler validate` all pass on the published files.
6. **M6 — Store channels.** Google Play internal testing → production; F-Droid
   inclusion request with a reproducible recipe; TestFlight → App Store. Exit:
   listings live, each linked from the README Availability section.

## 6. Owner decisions and prerequisites

- **Accounts:** Apple Developer Program (needed for both iOS and macOS
  signing/notarization); Google Play developer account; optionally a Microsoft
  Partner Center account (only for a Store/MSIX listing).
- **Certificates and keys:** Android upload/release keystore (preserve any
  existing key); Apple Developer ID Application and Apple Distribution
  certificates plus an App Store Connect API key; a Windows code-signing
  certificate or Azure Trusted Signing; a GPG key only if an apt repository is
  wanted.
- **Store listings:** Play and F-Droid texts exist in `fastlane/`; App Store
  listing, screenshots and privacy answers are still to be prepared.
- **Open choices:**
  - Version scheme: keep the split (DMG 1.0.0, others 0.1.0) during the preview
    or move all platforms to 1.0.0 at the first public release.
  - Linux channels: GitHub Releases only, or also an apt repository / Flathub.
  - Intel Mac support (adds a second DMG and CI job).
  - Close-to-tray: the preview hides the window only while a block is active;
    confirm or change.
  - After M4, whether Android and desktop should also share Compose UI.
  - Histories stay per device (no sync); confirm that this remains the product
    stance.
