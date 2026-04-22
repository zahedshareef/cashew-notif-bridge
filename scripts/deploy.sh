#!/usr/bin/env bash
# Cashew Bridge — autonomous build-install-verify pipeline
# Run from project root: bash scripts/deploy.sh
set -euo pipefail

# Android Studio JBR — required when JAVA_HOME is not set in the shell
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
export ANDROID_HOME="${ANDROID_HOME:-$USERPROFILE/AppData/Local/Android/Sdk}"

PACKAGE="com.cashewbridge.app"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
CRASH_LOG=".deploy-crash.log"
LOCK_FILE="/tmp/deploy-cashew-bridge.lock"
RESULT_FILE=".deploy-result"

log() { echo "$1" | tee -a "$RESULT_FILE"; }

# ── Debounce: skip if another deploy ran < 15s ago ──────────────────────────
if [ -f "$LOCK_FILE" ]; then
    MTIME=$(date -r "$LOCK_FILE" +%s 2>/dev/null || stat -c%Y "$LOCK_FILE" 2>/dev/null || echo 0)
    AGE=$(( $(date +%s) - MTIME ))
    if [ "$AGE" -lt 15 ]; then
        exit 0
    fi
fi
touch "$LOCK_FILE"

echo "⏱  $(date '+%H:%M:%S') Deploy started" > "$RESULT_FILE"
rm -f "$CRASH_LOG"

# ── 1. Build ─────────────────────────────────────────────────────────────────
log ""
log "🔨 Building debug APK..."
if ! ./gradlew assembleDebug 2>&1 | tee -a "$RESULT_FILE"; then
    log ""
    log "❌ BUILD FAILED — fix errors above before deploying"
    exit 1
fi

if [ ! -f "$APK_PATH" ]; then
    log "❌ APK not found at $APK_PATH after build"
    exit 1
fi

# ── 2. Device discovery ──────────────────────────────────────────────────────
log ""
log "📱 Discovering ADB devices..."
DEVICE=$(adb devices 2>/dev/null | awk -F'\t' 'NR>1 && $2=="device"{print $1; exit}')
if [ -z "$DEVICE" ]; then
    log "❌ NO DEVICE CONNECTED — connect via USB or run: adb connect <ip>:5555"
    exit 1
fi
log "  → $DEVICE"

# ── 3. Install ───────────────────────────────────────────────────────────────
log ""
log "📦 Installing..."
if ! adb -s "$DEVICE" install -r "$APK_PATH" 2>&1 | tee -a "$RESULT_FILE"; then
    log "  install -r failed — retrying with full uninstall (preserves notification access grant)..."
    adb -s "$DEVICE" uninstall "$PACKAGE" 2>/dev/null || true
    if ! adb -s "$DEVICE" install "$APK_PATH" 2>&1 | tee -a "$RESULT_FILE"; then
        log "❌ INSTALL FAILED"
        exit 1
    fi
    log "  ⚠️  App was uninstalled — re-grant notification listener access:"
    log "       Settings > Apps > Special app access > Notification access > Cashew Bridge"
fi

# ── 4. Grant permissions ─────────────────────────────────────────────────────
API=$(adb -s "$DEVICE" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n')
if [ "${API:-0}" -ge 33 ]; then
    adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
fi
# Required for the notification listener to receive sensitive (financial) notifications
adb -s "$DEVICE" shell appops set com.zaheds.firebug RECEIVE_SENSITIVE_NOTIFICATIONS allow 2>/dev/null || true

# ── 5. Launch ────────────────────────────────────────────────────────────────
log ""
log "🚀 Launching $PACKAGE..."
adb -s "$DEVICE" logcat -c 2>/dev/null
adb -s "$DEVICE" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 2>/dev/null
log "  Waiting 8s for startup..."
sleep 8

# ── 6. Logcat snapshot ───────────────────────────────────────────────────────
log "📋 Scanning logcat..."
adb -s "$DEVICE" logcat -d 2>/dev/null \
    | grep -E \
        "FATAL EXCEPTION|E AndroidRuntime: (Process:|at |Caused by:)|signal 11|signal 6|\
E/CashewBridge|CashewBridge.*ERROR|SQLiteException|\
Room.*migration|MigrationException|\
SecurityException.*notification|NotificationListenerService.*ERROR" \
    | grep -v "LoadedApk\|com.android.commands.monkey\|isPerfLogEnable" \
    > "$CRASH_LOG" 2>/dev/null || true

CRASH_COUNT=$(wc -l < "$CRASH_LOG" 2>/dev/null | tr -d ' ')

if [ "$CRASH_COUNT" -eq 0 ]; then
    log ""
    log "✅ DEPLOY OK — no crashes on $DEVICE"
    rm -f "$CRASH_LOG"
    exit 0
fi

# ── 7. Diagnosis & auto-fix ──────────────────────────────────────────────────
log ""
log "❌ CRASHES DETECTED ($CRASH_COUNT lines)"

# Room migration failure
if grep -qE "SQLiteException|MigrationException|Room.*migration|no such (table|column)" "$CRASH_LOG"; then
    log "  🩹 [KNOWN] Room migration failure"
    log "       Check AppDatabase.kt — ensure MIGRATION_N_(N+1) exists for every schema bump."
    log "       Current schema: v4. Never use fallbackToDestructiveMigration in production."
fi

# NotificationListenerService binding / permission
if grep -qE "SecurityException.*notification|NotificationListenerService.*bind|not allowed to bind" "$CRASH_LOG"; then
    log "  🩹 [KNOWN] NotificationListenerService not granted"
    log "       Settings > Apps > Special app access > Notification access > Cashew Bridge → ON"
fi

# NullPointerException inside parser
if grep -qE "NullPointerException|null check operator" "$CRASH_LOG" \
    && grep -qE "NotificationParser|CashewLinkBuilder|NotificationListenerService" "$CRASH_LOG"; then
    log "  🩹 [KNOWN] Null crash in parser/service"
    log "       Check NotificationParser.kt — ensure ParsedTransaction fields have null-safe defaults."
    log "       Check CashewLinkBuilder.kt — use Uri.Builder and encode each param with encodeQueryParameter."
fi

# Coroutine / CoroutineScope crash
if grep -qE "JobCancellationException|CoroutineScope|serviceScope" "$CRASH_LOG"; then
    log "  🩹 [KNOWN] Coroutine scope error"
    log "       Ensure serviceScope uses SupervisorJob() so individual failures don't cancel the scope."
    log "       Location: NotificationListenerService.kt — val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)"
fi

# Device went offline mid-deploy
if grep -qE "device offline|error: device|no devices" "$CRASH_LOG"; then
    log "  🩹 [KNOWN] Device ID drift — wireless ADB IP changed."
    log "       Run: adb disconnect && adb connect <new-ip>:5555"
fi

log ""
log "── Crash log (first 20 lines) ───────────────────────────────────────────"
head -20 "$CRASH_LOG" >> "$RESULT_FILE"
log "── End crash log ────────────────────────────────────────────────────────"
exit 1
