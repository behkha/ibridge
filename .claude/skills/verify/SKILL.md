---
name: verify
description: Build and run iBridge to check a change for real - protocol interop (Kotlin vs Swift), the Android app on the user's phone over adb, and the iOS Simulator build + screenshots on GitHub Actions. Use before reporting any change to android/, ios/, or docs/PROTOCOL.md as done.
---

# Verify iBridge

Env for every Android/JVM step:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # Temurin 21 in ~/Library/Java
A=~/Library/Android/sdk/platform-tools/adb
```

## 1. Protocol interop (any change to core/ on either side, or PROTOCOL.md)
```bash
cd tools/interop
swiftc -O -o build/ios-side ../../ios/iBridge/Core/*.swift main.swift
../../android/gradlew -p . installDist -q
./run.py          # 5 scenarios must print PASS; add -v for both sides' logs
```
Runs the app's real Kotlin `LinkEngine` (JVM, software key) against the real Swift `Handshake` over pipes at a 23-byte MTU.

## 2. Android on the phone
```bash
cd android && ./gradlew assembleRelease -q        # the Galaxy runs the release-signed build (key: ~/.ibridge, backup ~/Documents/iBridge-signing-backup)
$A devices -l                                  # user's phone: Galaxy S21 Ultra (SM-G998B), Android 15, USB
$A install -r app/build/outputs/apk/release/app-release.apk   # a debug APK won't install over it (other key)
$A shell am start -n app.ibridge.android/app.ibridge.MainActivity
$A shell uiautomator dump /sdcard/ui.xml && $A shell cat /sdcard/ui.xml | grep -oE 'text="[^"]+"'   # read the screen as text
$A exec-out screencap -p > design/evidence/<name>.png
$A shell dumpsys activity services app.ibridge.android | grep isForeground   # service up, types=0x10
$A shell dumpsys bluetooth_manager | grep -A1 mAdvertisingServiceUuids          # advertising
```
- Permission prompts are the user's to tap; ask them, never `pm grant`.
- `NoSuchFileException …/scratchpad/gradle-…` = a Gradle daemon left by another session: `pkill -f GradleDaemon`, then build again.
- Away card (W1): ask the user to switch the iPhone's Bluetooth off in Settings; expect `Waiting for … / Away since HH:mm.`
- Decode the pairing QR from a screenshot: `../../android/gradlew -p tools/interop decodeQr -q --args=$PWD/design/evidence/android-pairing.png`, then `tools/interop/build/ios-side parse "<uri>"` must exit 0.
- Compare screenshots with design/mockups/*.dc.html in a sub-agent (image analysis never runs in the lead).

## 2b. Reconnect test (locked iPhone)
- iPhone log over USB: `idevicesyslog -p iBridge` (libimobiledevice). Link lines: discovered / connected / live / link lost.
- Lock the iPhone at least 1 minute first (iBridge suspended).
- Restart Android's side: `$A shell am force-stop app.ibridge.android`, then `$A shell am start -n app.ibridge.android/app.ibridge.MainActivity` and `$A shell input keyevent KEYCODE_HOME` (the service is not exported, so `start-foreground-service` fails). Check `logcat` shows iBridge lines, or the test measured nothing.
- Expect `leftover link: held, now released`, then `← hello` within ~10 s; the iPhone shows `link lost; app state background` then `live`. Android only releases the link of the iPhone saved in `shared_prefs/gatt.xml` (set when a session goes live): a fresh install has none until the first link.
- Text back: call the Android SIM, press and hold the alert on the iPhone, Text back; Android logs `← call.decline`, `← send`, `→ sent`.
- Wait for `← hello` in `$A logcat -s iBridge`; message types are logged as → / ←.
- Logic checks (PROTOCOL §6 "While the link is down"): with the iPhone's Bluetooth off, Android logs `(not sent: no live link)` and the home card reads `Away since HH:mm. N text(s) will follow…`; on relink expect `← hello`, `→ status`, then the held `sms.read` / `sms.gone` / `contacts.changed`, then `page`s, then the iPhone's queued `← send` / `← read`. iPhone reads need `appops set app.ibridge.android WRITE_SMS allow` (user OK given for the Galaxy) to log `marked N of N texts read`. Google Messages deletes go to its bin first; `sms.gone` comes when the bin is emptied.
- Who is scanning on the iPhone: `idevicesyslog | grep ScanParams` lists scan agents.

## 2c. Calls with audio (phase 2)
- Helper state: `$A logcat -s iBridge | grep -E "audio|adb:|helper|relay|call"`. Expect `audio: helper proof accepted in N ms` (the helper proves it is the shell by a DUMP-only broadcast; without it iBridge refuses the peer), then `audio: … → ready` and `call paths outside a call: {… "record":true,"track":true}`.
- Restart test: `$A shell "pkill -f '^ibridge_call'"` (the process shows as `main`; `pkill -x` doesn't match it). Expect `audio: starting the helper (helper gone)` → `ready` within ~10 s, and `settings get global adb_wifi_enabled` back to `0`. Stale mDNS ports log `adb: port N refused` and are skipped.
- Start the helper over USB instead: `$A shell am broadcast -a app.ibridge.HELPER_COMMAND -n app.ibridge.android/app.ibridge.service.HelperCommandReceiver` prints the command line (it carries the helper secret: don't paste it anywhere). Opus check: `-a app.ibridge.OPUS_CHECK`.
- The helper grants iBridge `WRITE_SECURE_SETTINGS` itself (by design, so iBridge can switch Wireless debugging); test steps still never `pm grant` runtime permissions.
- A real call: the user places it; check by ear (Galaxy earpiece near-silent, both sides hear). Android logs `← call.answer`, `relay <id>: sent ~50 received ~50` per second, `← call.hangup`; iPhone (`idevicesyslog -p iBridge`) logs `audio: in 50 out 50 depth 2-4 …`. After the call `$A shell dumpsys audio | grep -m3 -i "mode\|mic"` is back to normal.
- Bench: hold the Settings phone card on the iPhone for 2 s (or launch with `-bench YES`); iPhone logs `bench A→I: 500/500 records in … ms`, Android logs the reverse.
- Interop (§1) includes a bench scenario with AUDIO records both ways: 5 scenarios must PASS.
- CI screenshots now also include `P2-calls` and `P2-keypad-text`. CI runs on any branch push, not only `main`.

## 3. iOS (no Xcode here)
Push to `main` of the public repo behkha/ibridge; `.github/workflows/build.yml` builds the Simulator app with XcodeGen and runs `scripts/ios-screenshots.sh` (Main, A-Calls, A-Persian, Conversation(-fa), Keypad, Contacts, Settings(-fa), Pairing(-code) via `-demo`). Runner macos-26 (Xcode 26, system Liquid Glass bars); compiler errors print in the Build step.
```bash
gh run watch <id> --exit-status
gh run download <id> -n ios-screenshots -D design/evidence/ios
```
Core Swift files can be type-checked locally: `swiftc -typecheck ios/iBridge/Core/*.swift ios/iBridge/Model/*.swift ios/iBridge/Link/*.swift` (UI files need the iOS SDK).
