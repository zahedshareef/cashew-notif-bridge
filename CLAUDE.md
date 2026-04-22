# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Cashew Bridge** is a native Android app (Kotlin) that intercepts bank/payment notifications and forwards parsed transactions to the [Cashew](https://cashewapp.web.app/) personal finance app via `cashewapp://addTransaction` deep links. All processing is on-device — there is no INTERNET permission.

## Build & Development

**Requirements:** Android Studio Iguana (2023.2+), Android SDK 26+, Java 11, Kotlin 2.1.0

```bash
# Debug build
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Filter logcat for this app
adb logcat | grep "CashewBridge\|NotificationListener"

# Clean build
./gradlew clean
```

No automated tests are configured. The `RuleEditDialogFragment` provides an **inline regex tester** for validating rules manually. Physical device required for live notification testing.

## Architecture

### Core Data Flow

```
Bank notification → NotificationListenerService → NotificationParser → ParsedTransaction
    → [dedup check] → [confirm/batch/undo logic] → CashewLinkBuilder → cashewapp:// deep link
```

### Key Layers

**Service (`service/`)**
- `NotificationListenerService.kt` — Entry point for all notifications; handles dedup (exact + fuzzy), per-rule cooldowns, confirm mode, undo window (10s alarm), batch window scheduling, and reminder alarms.
- `NotificationHelper.kt` — Creates 6 notification channels (CONFIRM, ALARM, UNDO, REMINDER, BATCH, SUMMARY) and builds actionable notification UIs.
- `NotificationCache.kt` — In-memory notification cache synced to Room for restart durability.
- Broadcast receivers: `ConfirmActionReceiver`, `UndoActionReceiver`, `BatchAlarmReceiver`, `ReminderReceiver`, `SummaryReceiver`, `BootReceiver`.

**Parser (`parser/`)**
- `NotificationParser.kt` — Evaluates user rules sorted by priority, falls back to confidence-scored heuristics. Detects 9 currencies (USD, INR, GBP, EUR, AED, SGD, AUD, CAD, JPY). Returns `ParsedTransaction` with confidence 0–100.
- `CashewLinkBuilder.kt` — Constructs the `cashewapp://addTransaction?...` URI.

**Database (`model/`)**
- `AppDatabase.kt` — Room DB, current schema **v4** with migrations `1→2`, `2→3`, `3→4`. Never skip migrations.
- `Models.kt` — Entities: `NotificationRule`, `ParsedTransaction`, `ProcessedLog`, `CachedNotification`, `BatchedTransaction`, `AppBlocklistEntry`.

**Preferences (`prefs/`)**
- `AppPreferences.kt` — SharedPreferences wrapper for 30+ settings (master toggle, wallet, min amount, confirm/batch/undo/reminder/summary modes, fuzzy dedup, privacy mode, theme).

**UI (`ui/`)**
- Activities: `MainActivity`, `RulesActivity`, `NotificationsActivity`, `LogsActivity`, `BatchReviewActivity`, `InsightsActivity`, `AppBlocklistActivity`.
- `RuleEditDialogFragment.kt` — Rule editor with live regex test UI.
- `TemplatesDialogFragment.kt` — 20 built-in templates (Chase, HDFC, PayPal, Venmo, etc.).

### Notification Workflow Modes

| Mode | Behavior |
|------|----------|
| Confirm | Shows actionable notification; user taps Send or Skip |
| Undo | Forwards immediately but shows 10s cancel window via alarm |
| Batch | Collects transactions for N minutes, then opens `BatchReviewActivity` |
| Direct | Fires deep link immediately with no interaction |

### Cashew Deep Link Format

```
cashewapp://addTransaction?amount=25.50&name=Starbucks&account=Wallet&note=...&reoccurrence=...&date=...
```

## Conventions

- **IO operations** run on `Dispatchers.IO`; UI updates on `Dispatchers.Main` via `lifecycleScope`.
- **Scoped coroutines**: `NotificationListenerService` uses a `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- **Database migrations** must be added to `AppDatabase.kt` whenever schema changes; never use `fallbackToDestructiveMigration`.
- **Privacy invariant**: The INTERNET permission must never be added to `AndroidManifest.xml`.
- Rule priority is descending — higher number = evaluated first.
