
# October 2026 refinement

## First published CI run and follow-up

On 2026-10-31, the [first published verification run](https://github.com/etabli-dev/etabli-focus/actions/runs/41200121975) failed in the Android SDK installation step with `sdkmanager: command not found` (exit 127), before Android tests, lint or packaging ran. The workflow now explicitly installs Android command-line tools using `android-actions/setup-android` v4.0.4, pinned to its verified commit, before requesting API 36 and build-tools 35.0.0. Existing checks and artifact collection remain intact. YAML and the action reference were checked locally before the follow-up run. Earlier local build and device evidence below remains separate from this failed remote run.

Later on 2026-10-31, the [follow-up verification run](https://github.com/etabli-dev/etabli-focus/actions/runs/41200540333) passed for [commit `00e3c5dce3ab`](https://github.com/etabli-dev/etabli-focus/commit/00e3c5dce3ab6207a108ff4f4cd10d9c1f2f8e47): Android `testDebugUnitTest lintDebug assembleDebug bundleRelease` and the full unsigned iOS simulator build succeeded. The [documentation build and deployment](https://github.com/etabli-dev/etabli-focus/actions/runs/41200540311) also passed for that commit. These results verify compilation and the recorded automated checks; installed application journeys, release signing and store acceptance remain separate gates.

## Android submission compatibility milestone

The baseline targeted API 34, below the [current Google Play submission requirement](https://developer.android.com/google/play/requirements/target-sdk). Compile/target SDK now move to 36 while retaining minimum SDK 26. AGP 8.13.2, Gradle 8.13 (distribution SHA-256 pinned), and JDK 17 are a [documented compatible toolchain](https://developer.android.com/build/releases/agp-8-13-0-release-notes). The API 27 navigation-bar attribute is now in qualified resources; Android 8.0 uses a dark navigation bar so its white icons remain visible. Validate tests, lint, debug APK and unsigned release packaging after migration. Device checks must cover insets, predictive back, rotation and existing saved data.

## Timer integrity milestone

Confirmed: Android commands read an asynchronously updated StateFlow and run without serialization. Rapid operations can replace an active session or finalize twice. Both platforms infer the start from a movable deadline, and Android records late foreground return as the finish, inflating focus statistics. Cancel while paused can record a whole block. Alarm replacement only cancels the selected kind, and no receiver handles the declared boot permission.

Repair strategy: persist the original start, record active duration separately with a preserving Room migration / optional SwiftData field, use the completion deadline for completed sessions, serialize Android state transitions against current persisted preferences, keep failed writes retryable, and rearm active alarms on boot/start. Verify pause/resume/cancel and late completion accounting, migration preservation, unit/build/lint checks; device alarm delivery remains a separate gate from the unsigned iOS compilation subsequently verified in the 2026-10-31 CI run above.

## Final local evidence and release scope

Eight Kotlin tests now exercise active-time accounting, concurrent start/completion commands, failed-history retry, pause/resume/cancel and both European DST transitions. Android tests, lint, debug APK and unsigned release AAB all pass (`focus-engine-final`, 2026-10-30). SQLite executes the production migration SQL against a populated v1 history and preserves its original columns with a nullable new duration. Foundation Swift checks prove old timer JSON compatibility and pause/completion accounting. Both idle date screens refresh across midnight; chart buckets use calendar dates rather than elapsed 24-hour periods.

The full unsigned iOS simulator build passed in the 2026-10-31 CI run above. Remaining release gates: exercise an installed v1-to-v2 Room/SwiftData upgrade and check notification permission denial, background/force-stop/reboot delivery, accessibility and rotation on devices. The SQLite script is not a full Room/SwiftData runtime migration test. Existing old rows have no pause-duration data; their historical values are retained rather than guessed. Category/history export and richer timer modes are deferred. First-use commands are in README.

## Installed Android notification review

The completion receiver checked POST_NOTIFICATIONS, then posted outside a permission-failure boundary. Revocation between those calls could crash the receiver. Delivery now handles that SecurityException while retaining the initial permission check. Robolectric exercises the actual receiver for denied permission, granted delivery and a revocation during notify; device denial/delivery remains a separate check.

Local verification on 2026-10-30: `testDebugUnitTest lintDebug assembleDebug bundleRelease` passed in `focus-android-device-prep`; all 11 tests passed, including the three receiver permission cases. Debug APK and unsigned release AAB were rebuilt with the receiver fix. Installed notification delivery and OEM background behavior remain separate evidence.

## Phone layout and target accessibility

Confirmed from layout constraints: at 390 dp width, outer and card padding leave 326 dp. Seven weighted cells plus six 4 dp gaps leave about 43 dp per cell, although two 24 dp controls, their gap and padding need 60 dp. The controls could clip or overlap, and their nominal touch size was too small. Weekdays now wrap into as many minimum-116-dp cells as fit, with 48 dp action targets, full weekday/category accessibility labels, exposed block counts and disabled decrement/increment at 0/8. Narrow screens scroll through the same seven weekdays; tablet widths retain more columns.

Final native build evidence (2026-10-30, inspected 2026-10-31): `testDebugUnitTest lintDebug assembleDebug bundleRelease` passed in `focus-android-layout-final`. The JUnit XML records 15 tests, zero failures, errors or skips. All four Compose layout/interaction checks pass at 390 dp, 320 dp with 2x text, and 840 dp; 48 dp action targets stay in bounds and do not overlap, and block limits remain enforced. Debug APK and unsigned release AAB include these changes. Installed user journeys and signing/store acceptance remain separate gates.

## Exact-alarm manifest repair — 2026-11-02

The actual APK requested both exact-alarm permissions without a version boundary.
The manifest now follows [Android's documented split](https://developer.android.com/reference/android/Manifest.permission#USE_EXACT_ALARM):
`SCHEDULE_EXACT_ALARM` ends at API 32; `USE_EXACT_ALARM` serves the core timer on
API 33+. This removes the overlapping declaration on newer devices. The existing
`SecurityException` fallback to an inexact alarm is unchanged. Notification
consent remains separate; a user gesture alone does not establish permission
eligibility or store acceptance.

The full local `testDebugUnitTest lintDebug assembleDebug bundleRelease` run
passed (`focus-alarm-permission-android-20261102`), including all 15 tests without
failures or skips. Read-only inspection of the built APK with `apkanalyzer` and
the actual AAB with checksum-verified official bundletool 1.18.3 confirmed the
same version split, minimum API 26 and target API 36. The APK signature verifies
as Android Debug; the release AAB has no signing block and is unsigned. The
workspace receipt `focus-alarm-package-verification-20261102.json` retains exact
commands, raw manifests and artifact hashes. These package checks do not execute
alarm delivery or establish device/store acceptance.

The Android submission guide now matches SDK 36 and the actual optional private
signing configuration. It no longer assumes a placeholder keystore exists or
instructs replacing an existing key. Preserve signing identities and complete the
remaining device and release checks before distribution.

Debug APK SHA-256: `74f34a8f7ca988b637df28b1e5315c388a894324bb654f2ec0020f2dafccf5f1`.

Unsigned AAB SHA-256: `89c1b91d17c8e363b00a0c117e0fcf99b9f4cb4a3c62d379302f19ddce30fd03`.
