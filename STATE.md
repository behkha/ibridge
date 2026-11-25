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
- Round 2 (2026-11-24, built + verified on both phones; canvas page "Screens round 2"): Viewfinder pairing, Native conversation, Text pad, Quick text contacts, Phone card settings, Android Quiet away card. Liquid Glass: system glass only (CI on Xcode 26).
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
1. Phase 1 finished 2026-11-24 (MMS and a long real-world run skipped by the user). Verified on both phones: read both ways, send while away, catch-up + away card, deletes (`sms.gone`, `calls.gone`), contacts changes, ring re-sent on relink, missed-call alert, unlink while away delivered later.
   - Galaxy runs the release-signed APK (key ~/.ibridge, backup ~/Documents/iBridge-signing-backup: move it off this Mac). WRITE_SMS app-op allowed there (user OK).
   - Left: MMS/group texts; long real-world run; WRITE_SMS needs adb (phase 2's wireless-debugging route could grant it); one `BAD_PACKET bad tag` seen once mid-unlink (recovered by itself).
2. Phase 2 (calls with audio) started 2026-11-25. Spike: docs/notes/call-audio-spike.md. Earpiece quiet only with mic mute + REDIRECT + voice volume min; outgoing calls behave like incoming.
   - Approved design (P2-Calls, https://claude.ai/artifact/BoADihwAZHdtpm9yRWbEx7, file design/mockups/P2-Calls.html): Android checklist row "Talk on your iPhone" + live-steps sheet + code typed in a notification reply; home warnings (Wi-Fi / Auto Blocker / unsupported); iPhone green call key (Text to its left), blue call-back icons, CallKit system screen only, Settings row only when calls aren't ready. Galaxy silent while the iPhone rings. Wi-Fi is available for helper starts.
   - Protocol drafted: PROTOCOL §2 priority, §5.1 AUDIO (0x21, own counters), §6 call.answer/dial/hangup/dtmf, call.state, bench, §8.
   - In progress: Android helper + ADB client (Kadb) + relay (worker); iPhone CallKit + audio (worker). Then: interop AUDIO scenario, bench on the real link, Android setup UI, real call on both phones, latency both ways, fresh-eyes review.
## Open questions
- Paid Apple account would allow TestFlight (7-day free signing is fine for now).
