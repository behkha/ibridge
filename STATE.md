# STATE — iBridge

Goal: iBridge, our own take on iTell (itellapp.com). An Android phone holds the SIM; its texts, contacts, call log and call alerts reach the iPhone over encrypted Bluetooth LE.

## Approved
- Phase 1 scope: both apps, QR pairing, encrypted BLE, texts + one-time codes, contacts, call history, incoming-call alerts. No accounts/server. Phase 2 (later): call audio, see research/FINDINGS.md.
- iPhone design: **A Companion**. Mockups: design/mockups/Main.dc.html (Messages), A-Calls (out of reach), A-Persian (RTL). Canvas: https://claude.ai/artifact/W1RT3rqceZeGiZwpg3VYhs
  - Five tabs, a link pill top-right, newest code card on top of Messages, and an orange strip only when out of reach.
  - Ink #0E1A2B, accent #1D5BD8, linked #137A4B, out-of-reach #A65300/#FFF1E0, missed #C4001A. SF Pro, Vazirmatn for Persian.
- Android design: **Checklist**. Mockups: design/mockups/E-Checklist, E-QR (pairing sheet), F-Status (home).
  - A denied row turns orange without blocking the others, and the home screen reuses the list for warnings.
  - The QR is always black on white, expires with a countdown, and has a fallback code, LTR even in RTL.
- Toolchain install approved 2026-11-23. Devices: Android 12+ phone, iOS 17+ iPhone.

## Done
- docs/PROTOCOL.md v1: GATT `e38d0001…`, chunk framing, QR + typed-code pairing (5×6-bit rounds, Android reveals first), triple-DH session, JSON messages.
- android/ (Kotlin, Compose, minSdk 31) runs on the user's Galaxy S21 Ultra (Android 15, USB): checklist, pairing sheet with a QR that decodes and parses on Swift, foreground service, BLE advertising. Matches E-Checklist/E-QR.
- ios/ (SwiftUI, iOS 17, XcodeGen) builds on GitHub Actions; Simulator screenshots match Main, A-Calls, A-Persian (fixes for 24h time + Persian code alignment in run 2).
- Repo: private github.com/behkha/ibridge, CI `.github/workflows/build.yml` (iOS build + screenshots, Android APK).
- tools/interop/run.py: real Kotlin vs Swift protocol code, 4 scenarios pass. Steps to verify everything: .claude/skills/verify.
- Fresh-eyes review: 14 findings fixed (call-log LIMIT crash, code-pairing leak, GATT slot theft, iOS restore/BT/cursor/notification bugs).
- Toolchain: Temurin 21 (~/Library/Java), Android SDK 35, Gradle 8.11.1. REA local, MCP `rea` for this folder.
- iTell APK in research/target; duplicate in research/artifacts (delete only with the user's OK).

## Decisions
- Own protocol, not iTell-compatible. Android = BLE GATT peripheral, iPhone = central (bluetooth-central background mode, state restoration).
- Crypto: P-256 ECDH + HKDF-SHA256 + AES-256-GCM, per-packet counters. The QR carries the Android pubkey + a 5-min one-time token.
- Android: Kotlin + Jetpack Compose, minSdk 31, sideloaded (Play blocks SMS/call-log permissions for non-default apps).
- iPhone: SwiftUI, iOS 17. No Apple ID on this Mac, so it builds on GitHub Actions macOS runners (private repo, user's gh account `behkha`) and gets checked in their Simulator.
- Phase-1 incoming call on iPhone = a notification (decline / text back), not CallKit, since there is no audio yet.
- No unit tests (user rule). Verify by building and running on real devices/emulator/simulator.

## Next
1. Pairing + texts end to end on real phones (needs the iPhone install route).
2. Design review for screens without mockups: iPhone pairing, conversation, Keypad, Contacts, Settings; Android "waiting" home card.

## Open questions
- Who signs the iPhone app (TestFlight needs someone's paid Apple Developer account)?
