# Établi Focus — Android submission checklist

## Build outputs
- **Debug APK** — `app/build/outputs/apk/debug/app-debug.apk`
  - Signed with the Android debug keystore. App ID is `com.raban.etabli.focus.debug` (`.debug` suffix) so it can coexist with the release build on the same device.
- **Release AAB** — `app/build/outputs/bundle/release/app-release.aab`
  - R8 + resource shrinking enabled. Signed with `app/upload-keystore.jks`.
  - This is what you upload to the Play Console.

## Signing
The upload keystore is `app/upload-keystore.jks`. Credentials live in `keystore.properties` (gitignored).

> **Important**: the keystore was generated locally with a placeholder password (`android`). Before publishing, either:
> 1. Re-generate the keystore with a real password and update `keystore.properties`, **or**
> 2. Use Play App Signing — upload this keystore as your upload key, and let Google manage the signing key. Either way, **back up `upload-keystore.jks` somewhere safe**. If you lose it, you cannot ship updates to the same app.

To regenerate with a strong password:
```bash
keytool -genkeypair -v -keystore app/upload-keystore.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Your Name, O=Your Org, C=DE"
```

## What's done (technical)
- Target SDK 34, min SDK 26
- Kotlin 1.9.24 + Compose BOM 2024.06.00
- Room 2.6.1 + DataStore Preferences
- AlarmManager + BroadcastReceiver for "focus complete" notifications
- POST_NOTIFICATIONS requested at runtime on Android 13+
- SCHEDULE_EXACT_ALARM with graceful fallback to inexact alarms
- Adaptive icon (mipmap-anydpi-v26 + monochrome vector)
- Edge-to-edge insets via `enableEdgeToEdge()`
- Light + Dark themes with in-app Auto/Light/Dark override (persisted in DataStore)
- Backup rules + data extraction rules declared
- R8 + resource shrinking on release
- Proguard rules keep persisted enum names (TimerPhase/TimerKind/ThemePreference) so state survives upgrades

## What you must do before publishing
- [ ] **Real upload key** — see signing section above
- [ ] **Privacy policy URL** — required for Play Store. Even though this app is fully offline and stores nothing remote, Google requires a URL declaring exactly that
- [ ] **Store listing assets**:
  - Short description (≤80 chars) and full description (≤4000 chars)
  - 512×512 PNG hi-res icon
  - 1024×500 PNG feature graphic
  - At least 2 phone screenshots (1080×1920+ portrait works)
- [ ] **Content rating questionnaire** — IARC, takes 5 min in Play Console
- [ ] **Data safety form** — declare: collects no user data, no third-party SDKs, no ads
- [ ] **Target audience and content** — pick "Adults" unless you intend children
- [ ] **App category** — "Productivity"
- [ ] **Pricing & distribution** — free/paid + countries
- [ ] **Optional** — declare `com.google.android.gms.permission.AD_ID` exclusion (you don't request it, but the Play Console form asks)

## Test before upload
```bash
# Install debug variant
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.raban.etabli.focus.debug/com.raban.etabli.focus.MainActivity

# Install the release bundle locally for verification (via bundletool)
# brew install bundletool
bundletool build-apks --bundle=app/build/outputs/bundle/release/app-release.aab \
  --output=/tmp/focus.apks --mode=universal
bundletool install-apks --apks=/tmp/focus.apks
```
