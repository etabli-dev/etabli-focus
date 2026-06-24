# Établi Focus

> Track focused work blocks by category.

`iOS` `Android` · Apache-2.0 · Part of the [Établi Suite](https://github.com/etabli-dev)

Établi Focus is a Pomodoro and focus-session tracker. Track work blocks by category (deep work, review, admin, research, manuscript, data analysis) with configurable focus and break durations and local notifications. Timers survive force-quit and reboot. Entirely offline.

## Availability

Établi Focus is **under active development**. There are no App Store, Google Play or F-Droid releases yet.

- **Android:** install the current **development build** as a signed **APK** from **[GitHub Releases](../../releases)**.
- **App Store (iOS):** planned — not yet available.
- **Google Play:** planned — not yet available.
- **F-Droid:** planned — not yet available.

## Privacy

No analytics. No third-party SDKs. No accounts. Credentials, where needed, live only in the platform secure store (iOS Keychain / Android EncryptedSharedPreferences). This app is fully offline.

## Repository layout

```
ios/        SwiftUI app
android/    Kotlin + Jetpack Compose app
fastlane/   F-Droid / store listing metadata
```

Both platforms are one product, sharing the Coder Design System tokens.

## Tech

iOS: SwiftUI + SwiftData. Android: Compose, Room, DataStore, AlarmManager

**Status:** In active development — not yet released; dev builds available as a signed APK via [GitHub Releases](../../releases).

## Support development

- 💚 **[Liberapay](https://liberapay.com/rabanheller/)** — recurring, 0% commission, to be shown on the F-Droid listing once published.
- ☕ [Buy Me a Coffee](https://buymeacoffee.com/rabanheller) — one-off tip (also the in-app link on iOS/Android).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Copyright 2026 R. Heller.
