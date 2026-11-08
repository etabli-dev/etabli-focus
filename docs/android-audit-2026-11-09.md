# Android backup and IDE review — 2026-11-09

Android Studio Rabbit 2 (2026.2.2 Patch 1) bundles Java 25, while this project uses Gradle 8.13. Select JDK 17 under Settings → Build, Execution, Deployment → Build Tools → Gradle. The local audit configured `GRADLE_LOCAL_JAVA_HOME` accordingly; machine-specific paths are not committed.

The backup rules contained `<include domain="root" />` without the mandatory path. Replacing these with empty default-mode sections preserves the intended Android backup set. An explicit legacy `full-backup-content` resource now covers pre-Android-12 devices as well. Android continues to exclude cache and `noBackupFilesDir` regardless of these default rules.

Both XML resources parse, and Android build-tools 35.0.0 `aapt2 compile` successfully compiled the full resource tree. Exact-commit lint and Android packaging are checked by the verification workflow after publication.

FlowSavvy/CalDAV credentials and sync receipts remain in `noBackupFilesDir`; this change does not move them into backups. Restored session history remains protected from replay by the existing installation-bound sync ledger.

Reference: [Android Auto Backup rules](https://developer.android.com/identity/data/autobackup).
