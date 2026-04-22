# Cashew Bridge

A native Android app that privately monitors your notifications and automatically creates transactions in the [Cashew](https://cashewapp.web.app/) app — without ever granting Cashew direct notification access.

## How it Works

```
Bank App → Notification → Cashew Bridge → Cashew App Link (cashewapp://addTransaction)
```

1. **Cashew Bridge** receives your bank/payment notifications via Android's `NotificationListenerService`
2. It parses the notification text to extract the amount, merchant, and transaction type
3. It fires a `cashewapp://addTransaction` deep link to create the transaction in Cashew
4. Your data **never leaves your device** — no internet required

## Building the App

### Requirements
- Android Studio Iguana (2023.2) or newer
- Android SDK 26+
- Java 11+

### Steps
1. Clone or download this folder
2. Open the `cashew-notif-bridge` folder in Android Studio
3. Let Gradle sync complete
4. Run on your device (`Run > Run 'app'`) — you need a real device (not emulator) to receive real notifications

### Or build APK directly:
```bash
./gradlew assembleDebug
# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

## Setup on Device

1. Install the app
2. Open **Cashew Bridge** → tap **Grant Notification Access**
3. Enable **Cashew Bridge** in the notification listener list
4. Come back to the app — the status should show **Active**

## Configuration

### Global Settings
| Setting | Description |
|---|---|
| **Default Wallet Name** | The Cashew wallet to add transactions to (leave blank for default) |
| **Minimum Amount** | Skip notifications below this amount (0 = forward everything) |
| **Confirm Before Adding** | Shows a dialog before creating each transaction (future feature) |

### Rules
Rules let you fine-tune how notifications from specific apps are handled.

**To add a rule:**
1. Tap **Manage Rules** → tap the **+** button
2. Fill in:
   - **Rule Name**: anything descriptive, e.g. "Chase Bank Debit"
   - **App Package**: the notification sender's package, e.g. `com.chase.sig.android`
   - **Title Contains**: substring to match in the notification title (optional)
   - **Body Contains**: substring to match in the notification body (optional)
   - **Amount Regex**: a regex with capture group 1 being the amount, e.g. `\$([0-9,.]+)`
   - **Merchant Regex**: a regex with capture group 1 being the merchant, e.g. `at\s+([A-Za-z ]+)`
   - **Default Category**: the exact Cashew category name to assign
   - **Wallet Name**: override the global wallet for this rule
   - **Priority**: higher number = checked first

**If no rules match**, the app falls back to built-in heuristics that recognize common patterns like:
- `$1,234.56` / `USD 100.00` / `₹500` / `€12`
- Keywords like "debited", "credited", "charged", "payment", "received"

### Common Bank Package Names
| Bank/App | Package Name |
|---|---|
| Google Pay | `com.google.android.apps.walletnfcrel` |
| PhonePe | `com.phonepe.app` |
| Paytm | `net.one97.paytm` |
| Chase | `com.chase.sig.android` |
| Bank of America | `com.infonow.bofa` |
| Wells Fargo | `com.wf.wellsfargomobile` |
| Venmo | `com.venmo` |
| PayPal | `com.paypal.android.p2pmobile` |

> **Tip**: To find any app's package name, check Settings → Apps → [App Name] → Advanced, or use a free app like "Package Name Viewer"

### Example Rules

**Google Pay (India) — UPI Debit:**
- Package: `com.google.android.apps.walletnfcrel`
- Body Contains: `paid`
- Amount Regex: `₹([0-9,]+\.?[0-9]*)`
- Merchant Regex: `to\s+([A-Za-z][A-Za-z0-9 ]{2,30})`
- Category: `Food` (or whichever fits)

**Chase Bank — Debit Alert:**
- Package: `com.chase.sig.android`
- Title Contains: `Transaction`
- Amount Regex: `\$([0-9,]+\.[0-9]{2})`
- Merchant Regex: `at\s+([A-Z][A-Za-z0-9 &]{2,30})`
- Income: off

**Salary Deposit:**
- Body Contains: `salary` or `salary credited`
- Income: on
- Category: `Income`

## Activity Log

Tap **View Logs** to see every notification the app processed, including:
- Whether it was **LAUNCHED** (sent to Cashew), **SKIPPED**, or found **NO_AMOUNT**
- The parsed amount, merchant, and which rule matched
- The raw notification text (tap any row for full details)

## Privacy

- No network permissions — the app cannot send your data anywhere
- All processing happens on-device
- The Room database storing logs is private to the app
- You can clear all logs at any time from the Logs screen

## Cashew App Link Format

The app uses this URL scheme documented at [cashewapp.web.app/faq.html#app-links](https://cashewapp.web.app/faq.html#app-links):

```
cashewapp://addTransaction
  ?amount=50.00
  &title=Starbucks
  &categoryName=Food
  &walletName=Checking
  &income=false
  &updateData=true
```
