# FlowSavvy and calendar integration

## First use

Start Focus and open **Today → FlowSavvy tasks**. Enter an API key from **FlowSavvy → Settings → Integrations → API**. API access requires FlowSavvy Pro. Choose a category, duration and task, then start the Pomodoro. Scheduled task occurrences are listed first; non-repeating unscheduled tasks remain selectable. Recurring series without a dated occurrence in the loaded schedule are not offered, so a session cannot accidentally update every future occurrence.

The finished focus time is recorded locally first, then reported in whole minutes. Paused time and breaks are excluded. Cancelling a block retains its actual focus time. Sub-minute remainders accumulate rather than rounding every block upward. Completion is a separate **Mark complete** action with confirmation; a timer ending does not call the completion endpoint.

The task-sync API uses a full replacement update. Focus rereads the task and preserves its other writable fields. A persisted baseline/target receipt prevents adding the same minutes again after a lost response or restart. A remote progress value inconsistent with an outstanding receipt stops sync and shows the conflict; no forced overwrite is attempted. The API does not expose a documented conditional-update facility, so avoid editing or timing the same task concurrently on several devices while a progress write is in flight. This is not a general multi-device history merge.

Unsent work is tied to the credential and local sync-ledger identity. Reconnect the original key to send its pending work before rotating keys. Restoring old session history without the corresponding device-local ledger does not replay that history into the account. Do not delete the local ledger to resolve a conflict. A completed/deleted remote task leaves unsent time visible for review rather than reopening it.

## Calendar appointments

On iOS, configure CalDAV under the system calendar-account settings, then enable calendar access in Focus and select the calendars to display. EventKit supplies recurring occurrences and timezone conversion. Although iOS requires Full Access to read calendars, Focus never creates, updates or deletes calendar events.

On Android and desktop, enter the HTTPS **calendar collection URL**, not the server root or account home. Multiple calendar URLs sharing one username/app password can be entered on separate lines. For example, a provider may expose individual collections below `/remote.php/dav/calendars/<username>/<calendar>/`. Use the actual URL provided by your service. Credentials are never sent across redirects. Only `REPORT` calendar queries are sent; there are no calendar write requests. Servers must implement RFC 4791 `calendar-data/expand`; unsupported recurrence expansion is reported as an error rather than silently dropping recurring meetings.

The agenda covers the coming week and displays the next appointment beside the timer. Default reminders are five minutes before and at the event start. All-day entries remind at 09:00 local time. Reminders show the title/time and lead back to the calendar view; they do not pause or complete the timer. Changed/cancelled appointments replace pending reminders after successful refresh. Refresh happens while the app is running; mobile OS restrictions, denied notification permission and offline calendar changes can delay or suppress alerts. Keep desktop Focus running for desktop alerts. Calendar entries are not guaranteed current when the last sync failed.

## Storage and privacy

FlowSavvy and CalDAV are optional. Without connections, the local timer works independently. Android stores credentials encrypted with a non-exportable Keystore key outside backups; macOS/iOS use Keychain. Windows/Linux desktop credentials are held only in process memory. The local history contains task references/titles and focus durations, but no API key. Session history remains on the device; only task progress and explicitly confirmed completion are sent to FlowSavvy. Calendar contents are read only and are not sent to FlowSavvy. Reminder titles may be visible on the device's lock screen according to notification settings.

## Verification

The iOS task-sync fixture suite covers field preservation, dated recurrence, minute aggregation, duplicate history, lost responses, restart recovery, conflicting remote progress, credential changes, failed ledger writes and explicit completion. The calendar planner covers lead/start reminders, all-day 09:00 timing, deduplication and the pending-notification limit. Unsigned iOS simulator and device Release builds passed with Xcode 27.2. No live FlowSavvy account was modified during tests.

Android/desktop use shared Kotlin networking and sync logic. CalDAV fixtures exercise server expansion, cancellation, timestamps, malformed or unsafe XML, per-item access errors, multi-calendar merging and lead/start reminders. Final Kotlin verification passed 49 desktop tests and 16 Android tests with no failures or skips. Android lint reported no errors; debug APK and unsigned release AAB builds passed. The macOS DMG was rebuilt and its app started successfully with an isolated empty profile. Final iOS device Release compilation and 15 task-sync plus 15 calendar scenario groups passed, including interrupted writes followed by new work and refresh at a reminder deadline. Earlier simulator compilation also passed. Real account connectivity, notification delivery on physical devices and signed/notarized distribution remain separate acceptance checks.

Sources: [FlowSavvy API specification](https://my.flowsavvy.app/api/docs/public/openapi.json), [CalDAV RFC 4791](https://www.rfc-editor.org/rfc/rfc4791).
