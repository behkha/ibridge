# Background reconnect: findings (2026-11-24)

Goal: a locked iPhone (iBridge suspended) re-links by itself after Android's iBridge restarts or the phones drift out of range.

## What works
- Foreground: opening iBridge on the iPhone always links within seconds.
- Background right after pressing Home (app not yet suspended): relinked 11 s after an Android restart (22:40 test, before bonding).
- Send text, OTP code card, call alert (shown on ring, withdrawn on end), Decline from the notification (press-and-hold shows buttons). Text back: not tested yet.

## What fails
- iPhone locked > 1 min, then Android's iBridge restarts: no relink within 5 min, with or without the bond. `idevicesyslog -p iBridge` shows NOTHING from iBridge during the wait: no "link lost", no watchdog lines. iOS never wakes the app.

## Facts gathered
- Android rotates its advertising address per advertising set; unbonded, the iPhone's `lastKnown` identifier goes stale.
- iOS scan agents (`idevicesyslog | grep ScanParams`) did NOT include iBridge in one stuck state: the app was neither scanning nor pending-connecting. Watchdog (Central.checkLink, 10 s timer + didBecomeActive) added to fix that; only runs when the app is awake.
- With bonding (encrypted RX/CCCD, Gatt.kt), Samsung's stack auto-connects to the bonded iPhone as GATT client for BATTERY: `dumpsys bluetooth_manager` shows "(Connected) … Behzad's iPhone (BATTERY)" and an LE ACL with Android as central. iBridge's "drop links from an earlier run" at start cancels it, and it comes right back.
- iTell (com.itellapp.android) also runs a GATT server and advertises on the same Android; it can keep ACLs alive.
- Android side is fine: after restart it advertises (MESSAGE_ADV_SET_START), fast for 2 min then balanced then low power.

## Hypotheses to test next (in order)
1. The suspended app is not woken because the only link is Android-as-central (battery). iOS may not deliver didConnect to a CBCentralManager pending connect when the peer already holds a link in the other role. Test: with the link up (battery), does a foreground `connect()` call `didConnect`? (It works when opened, so probably yes; the question is whether a pending connect fires for an existing link while suspended.)
2. Make Android the GATT *client* path irrelevant: have Android notify on its own when its service starts, over the existing link (iPhone subscribed CCCD is per-bond persistent when bonded?). Check whether iOS persists the CCCD for bonded peers and whether a notification wakes the suspended app.
3. Alternative: iOS app as peripheral is not allowed in background for advertising; but iOS keeps GATT *server* alive in background. Consider Android as GATT client to an iPhone-hosted service (Android scans/connects to the bonded iPhone, like Samsung's battery client does). iOS wakes the app for read/write requests to its own characteristics (bluetooth-peripheral background mode). This flips the roles in PROTOCOL.md section 1 and is likely the robust design (it is how the battery link already behaves).
4. Check `CBConnectPeripheralOptionEnableAutoReconnect` (iOS 17+) on `connect()` for bonded peripherals.

## How to run the test
See .claude/skills/verify/SKILL.md section 2b. Restart Android via `am force-stop` + `am start -n app.ibridge.android/app.ibridge.MainActivity` then HOME (the service is not exported, so `start-foreground-service` fails). Lock the iPhone at least 1 minute first. Wait for `← hello` in `adb logcat -s iBridge`.

## Tests 2026-11-24 01:30 (Android-side evidence; iPhone was not on USB)
- **Out of range works.** Android Bluetooth off 8 s, then on: the suspended iPhone relinked ~1 s after BT came back (`← hello` 01:34:26.96). The ACL drop wakes the app and its pending connect to the bonded identity completes.
- **Android restart is the only failing case, and why:** the ACL is iPhone-initiated (`dumpsys` shim::acl `is_locally_initiated:false`) and survives the restart (link created 01:26:52 still up after the 01:31:24 restart). Android cannot drop it: `cancelConnection` logs `GATT_Disconnect … Hold link status size=0` and the link stays. The iPhone only gets two Service Changed indications (`Sent Service Changed Ind.` 01:31:24.0 / 01:31:25.3), which do not wake a suspended app.
- **A TX notification after the restart does not get through either.** Experiment (reverted; diff kept outside the repo): the new run sent a one-chunk `ERROR 8 RESTARTED` notification on TX (same handles, service 154) to the leftover link; `onNotificationSent` fired, the iPhone did nothing in 90 s. iOS invalidates the characteristic on Service Changed.
- So #1/#2 are ruled out, and #4 (auto-reconnect) cannot help: nothing disconnects. What remains needs Android to reach the iPhone app over the existing link: an iPhone-hosted characteristic Android writes to (doorbell), or the full role flip (#3).
