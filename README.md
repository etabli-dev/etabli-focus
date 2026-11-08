# Établi Focus

> Track focused work blocks by category.

`iOS` `Android` `Linux` `Windows` `macOS` (desktop preview) · Apache-2.0 · Part of the [Établi Suite](https://github.com/etabli-dev)

Établi Focus records focused work by category, with weekly targets and local history. Android uses Compose, Room, DataStore and AlarmManager; iOS uses SwiftUI, SwiftData and local notifications; the desktop preview (Linux, Windows, macOS) uses Compose Multiplatform with SQLite and DataStore. Active deadlines and pause budgets persist across process restarts. Completion is reconciled when the application resumes; notification delivery depends on platform permissions and device power policies.

## Build and first use

Android prerequisites: JDK 17, Android SDK platform 36 and build-tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, then:

```sh
cd android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug bundleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open Focus, choose a category, start a short block, pause/resume, and check Today and Stats after completion. Configure weekly targets under Template. Debug installs use a separate application ID. Release AABs remain unsigned until a private `keystore.properties` is supplied.

For iOS, open `ios/EtabliFocus.xcodeproj` with Xcode on macOS, choose the EtabliFocus scheme and a simulator/device, and configure your own signing team for devices. This workspace has command-line Swift tools but no Xcode; the full iOS build is not locally verified.

Desktop preview (Linux, Windows, macOS) prerequisites: a JDK 17 to 23 to run the Gradle 8.13 wrapper; the build provisions its JDK 17 toolchain if needed and bundles that runtime into the package. On Linux:

```sh
cd desktop
./gradlew test run                 # unit tests, then start the app
./gradlew packageDeb               # build/compose/binaries/main/deb/etabli-focus_0.1.0-1_amd64.deb
```

`packageMsi` and `packageDmg` build the Windows and macOS packages on those systems. The desktop app adds a tray countdown (where the desktop environment offers a system tray) and completion notifications; closing the window while a block is active keeps it running in the tray. Desktop history is separate from the phone's; nothing is synchronised.

Additional portable checks:

```sh
python3 scripts/check_migration.py
swiftc ios/App/TimerState.swift scripts/check_timer.swift -o /tmp/focus-timer-check
/tmp/focus-timer-check
```

## Availability

Établi Focus is **under active development**. There are no App Store, Google Play or F-Droid releases yet.

- **Android:** install the current **development build** as a signed **APK** from **[GitHub Releases](../../releases)**.
- **App Store (iOS):** planned — not yet available.
- **Google Play:** planned — not yet available.
- **F-Droid:** planned — not yet available.
- **Desktop (Linux .deb, Windows .msi, macOS .dmg):** **development preview**, built from source or downloaded as unsigned artifacts of the `Desktop build` GitHub Actions workflow. No signed desktop release yet; only the Linux build has been run locally.

**Platforms:** what runs where, what is verified, and the steps to signed releases on Ubuntu/Debian, Windows, macOS, iOS and Android are in the [platform roadmap](docs/platform-roadmap.md).

## Privacy

No accounts or analytics service. Categories, templates and session history stay in the local database; active timer state stays in platform preferences (on desktop, both live in one per-user data folder). Platform backups may include this data. The app uses the open-source platform libraries listed in its dependency manifests; it does not encrypt the history database.

## Repository layout

```
ios/        SwiftUI app
android/    Kotlin + Jetpack Compose app
desktop/    Kotlin + Compose Multiplatform desktop app (Linux, Windows, macOS)
fastlane/   F-Droid / store listing metadata
```

All platforms are one product, sharing the Coder Design System tokens.

## Tech

iOS: SwiftUI + SwiftData. Android: Compose, Room, DataStore, AlarmManager. Desktop: Compose Multiplatform, SQLite (sqlite-jdbc), DataStore

**Status:** In active development — not yet released; dev builds available as a signed APK via [GitHub Releases](../../releases); desktop builds are a development preview (source or CI artifacts).

## Support development

- 💚 **[Liberapay](https://liberapay.com/rabanheller/)** — recurring, 0% commission, to be shown on the F-Droid listing once published.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Copyright 2026 R. Heller.
