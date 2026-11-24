# STATE — iBridge
Goal: our own iTell. Android holds the SIM; texts, contacts, calls and call alerts reach the iPhone over encrypted BLE.
## Approved
- Phase 1 scope: both apps, QR pairing, encrypted BLE, texts + one-time codes, contacts, call history, incoming-call alerts. No accounts/server. Phase 2 (later): call audio, see research/FINDINGS.md.
- iPhone design: **A Companion**. Mockups: design/mockups/Main.dc.html (Messages), A-Calls (out of reach), A-Persian (RTL). Canvas: https://claude.ai/artifact/W1RT3rqceZeGiZwpg3VYhs
  - Five tabs, a link pill top-right, newest code card on top of Messages, and an orange strip only when out of reach.
  - Ink #0E1A2B, accent #1D5BD8, linked #137A4B, out-of-reach #A65300/#FFF1E0, missed #C4001A. SF Pro, Vazirmatn for Persian.
- Android design: **Checklist**. Mockups: design/mockups/E-Checklist, E-QR (pairing sheet), F-Status (home).
  - A denied row turns orange without blocking the others, and the home screen reuses the list for warnings.
  - The QR is always black on white, expires with a countdown, and has a fallback code, LTR even in RTL.
- Round 2 (2026-11-24, built + verified; canvas page "Screens round 2"): pairing **Viewfinder**, conversation **Native**, Keypad **Text pad**, Contacts **Quick text**, Settings **Phone card**, Android away card **Quiet**. Liquid Glass: **system glass only** (CI on Xcode 26 so system tab bar/nav bars/sheets go glass; our buttons, composer, pairing controls stay flat as drawn). Sleek skill declined.
- Toolchain install approved 2026-11-23. Devices: Android 12+ phone, iOS 17+ iPhone.
## Done
- docs/PROTOCOL.md v1: GATT `e38d0001…`, chunk framing, QR + typed-code pairing (5×6-bit rounds, Android reveals first), triple-DH session, JSON messages.
- android/ (Kotlin, Compose, minSdk 31) runs on the user's Galaxy S21 Ultra (Android 15, USB): checklist, pairing sheet with a QR that decodes and parses on Swift, foreground service, BLE advertising. Matches E-Checklist/E-QR.
- ios/ (SwiftUI, iOS 17, XcodeGen) builds on GitHub Actions; Simulator screenshots match Main, A-Calls, A-Persian (fixes for 24h time + Persian code alignment in run 2).
- tools/interop/run.py: real Kotlin vs Swift protocol code, 4 scenarios pass. Steps to verify everything: .claude/skills/verify.
- Fresh-eyes review: 14 findings fixed.
- Toolchain: Temurin 21, Android SDK 35, Gradle 8.11.1, libimobiledevice, REA. iTell APK duplicate in research/artifacts (delete only with OK).
- Verified on real phones 2026-11-24: send text, OTP code card, call alert + Decline, full history. OS bond added (encrypted GATT).
- 2026-11-23: iPhone 17 (iOS 27.2.1) paired with the S21 Ultra by QR.
- 2026-11-24: locked iPhone relinks by itself: out of range (~1 s) and Android restart (4-6 s; Android releases the leftover link, PROTOCOL §1). Text back verified. Notes: docs/notes/background-reconnect.md.
- iPhone install: free Apple ID + AltServer 1.8.4 (notarized, ~/Applications), hold Option on its menu → Sideload .ipa → build-ipa/iBridge-unsigned.ipa from CI artifact `ios-ipa-unsigned`. Re-sign every 7 days. Wi-Fi path to AltServer is blocked here; USB works.
- Repo public github.com/behkha/ibridge (user's choice; research/ stripped, no-reply email). CI `.github/workflows/build.yml`; iOS jobs often wait for Apple-silicon runners, re-run when cancelled.
## Decisions
- Own protocol, not iTell-compatible. Android = BLE GATT peripheral, iPhone = central (bluetooth-central background mode, state restoration).
- Crypto: P-256 ECDH + HKDF-SHA256 + AES-256-GCM, per-packet counters. The QR carries the Android pubkey + a 5-min one-time token.
- Android: Kotlin + Jetpack Compose, minSdk 31, sideloaded (Play blocks SMS/call-log permissions for non-default apps).
- iPhone: SwiftUI, iOS 17; built on GitHub Actions (no Xcode here), installed with AltServer.
- Phase-1 incoming call on iPhone = a notification (decline / text back), not CallKit, since there is no audio yet.
- No unit tests (user rule). Verify by building and running on real devices/emulator/simulator.
## Next (user, 2026-11-24: focus on app logic; polish and redesign later)
1. Logic audit + fixes, end to end on the real phones. Known gaps:
   - iPhone never marks texts read: unread badges never clear, Android's read state never changes (no `read` message in PROTOCOL §6).
   - No send while out of reach: composer locks; no queue on iPhone to send on reconnect.
   - Read/delete on Android never reach the iPhone (sms re-pushed only on pending→ok/failed).
   - New Android contacts only arrive on pull-to-refresh or a perms change.
   - "N texts will follow" (Android away card) and catch-up sync after reconnect not yet seen on the phones.
2. Polish leftovers (later): unused Persian keys in Localizable.xcstrings; per-keystroke recompute in conversation.
   - Device evidence with real data stays out of the repo (public).
## Open questions
- Paid Apple account would allow TestFlight (7-day free signing is fine for now).
