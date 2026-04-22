Run the Cashew Bridge deploy pipeline: build debug APK, install on connected Android device, launch app, scan logcat for crashes, and report results with fix guidance.

## Steps

1. **Run the deploy script** using Bash:
   ```
   bash scripts/deploy.sh
   ```
   The script writes progress to `.deploy-result` and crash details to `.deploy-crash.log`.

2. **Read the result file** once the script exits:
   ```
   cat .deploy-result
   ```

3. **Report to the user:**
   - On exit 0: confirm deploy succeeded and which device was used.
   - On exit 1: summarize the failure stage (build / install / crash) and any auto-fix guidance printed by the script. If crash patterns were matched, explain the fix in concrete terms referencing the relevant source file.

4. **If the script is not executable or not found**, run:
   ```
   bash scripts/deploy.sh
   ```
   Do not use `./scripts/deploy.sh` — always invoke via `bash` to avoid permission issues on Windows/WSL paths.

## Notes

- **Notification access must be granted manually** after a fresh install (uninstall+reinstall): Settings → Apps → Special app access → Notification access → Cashew Bridge → ON. Remind the user if the install log shows the app was uninstalled.
- **Physical device required** — the Android emulator cannot receive real bank notifications.
- **POST_NOTIFICATIONS** (Android 13+) is auto-granted by the script via `adb shell pm grant`.
- If the build fails, read the Gradle error from `.deploy-result` and fix it before re-running.
- If no device is found, prompt the user to connect via USB or run `adb connect <ip>:5555` for wireless ADB.
