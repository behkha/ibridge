# Call-audio spike (phase 2), 2026-11-25

Question: can a shell-privileged process on the Galaxy S21 Ultra (SM-G998B, Exynos 2100, Android 15,
build `G998BXXSJHZA6`) hear a cellular call's far side and talk into it? **Yes, both work.**

Probe: `tools/call-audio-probe/` (throwaway). `./probe.sh check` needs no call (and pushes the build);
`./start-detached.sh 900 12` arms a run that survives USB drops and acts on **any** call in that window
(disarm: `./probe.sh stop`); log in `/data/local/tmp/ibridge-probe/run.log`. It records the far side to
`downlink.pcm` on the phone: tell the other party, and delete afterwards (`adb shell rm -r /data/local/tmp/ibridge-probe`).

## Route
- `dumpsys package com.android.shell`: uid 2000 holds `CALL_AUDIO_INTERCEPTION`, `CAPTURE_AUDIO_OUTPUT`,
  `MODIFY_AUDIO_ROUTING`, `MODIFY_PHONE_STATE`.
- `dumpsys media.audio_policy`: `Telephony Rx` (in), `Telephony Tx` (out) and an `incall_music_uplink` output
  (8/16/48 kHz); `IAudioService.isPstnCallAudioInterceptable()` = true.
- Built like AudioManager's hidden `getCallDownlinkExtractionAudioRecord` / `getCallUplinkInjectionAudioTrack`,
  without a Context (nectarlink's way): record = capture preset `VOICE_DOWNLINK` + `CALL_REDIRECT_PSTN`;
  track = system usage `CALL_ASSISTANT` + `CALL_REDIRECT_PSTN`; 16 kHz mono s16 requested, 20 ms frames.
  Both route to device type 18 (Telephony). Outside a call both open too; the record reads zeros.

## Real call (incoming, answered on the Galaxy, other phone counting aloud)
| Check | Result |
| --- | --- |
| Downlink (far side) | speech levels -23…-35 dBFS while talking, -65…-79 in pauses; 50 reads/s, read bursts ≤45 ms |
| Uplink (inject) | **every beep pattern heard on the far phone** (user, by ear), 0 underruns all call |
| `setMode(MODE_CALL_REDIRECT)` from shell | accepted (mode 5); downlink and uplink keep working; given back to IN_CALL |
| Open both once the call is up | 23 ms (track) / 86 ms (record) |
| `VOICE_UPLINK` record | opens, but never showed the injected tone (cause not established): no self-check of injection |

Latency is **not measured end to end**. What the log gives: the app reads each downlink sample 1-3 ms after the
HAL's capture timestamp (excludes modem, vocoder, network); 100-135 ms of written uplink audio is queued ahead of
the play head (sawtooth from timestamp refresh; `AudioTrack.getLatency` says 120 ms). Neither is mouth-to-ear.

Mic mute: the user muted the call in the Galaxy's dialer from the start (`isMicrophoneMuted()` true in phases A, B, D),
and the injected beeps still reached the far phone. So a muted Galaxy mic keeps its room sound out without blocking
injection: the simplest way to keep the Galaxy out of the call. The probe unmuted it during C (REDIRECT); the dialer's
mute came back after. Not yet checked: whether REDIRECT silences the Galaxy's earpiece; outgoing calls; screen off; VoLTE vs 2G/3G.

## Open questions closed (2026-11-25, `./start-detached.sh 3600 15 listen`, no file written)
Phases on one call, 15 s each, Galaxy mic muted throughout: E plain IN_CALL, F `MODE_CALL_REDIRECT`, G voice volume at
its minimum (index 1 of 1..8; 0 is not allowed), H REDIRECT + minimum volume.
| Check | Incoming (answered on the Galaxy) | Outgoing (dialed from the Galaxy) |
| --- | --- | --- |
| Earpiece (user, by ear) | loud in E, F, G; **near-silent only in H** ("could hardly hear") | same |
| Downlink capture | every phase, -37…-78 dBFS | every phase, -33…-44 dBFS; first sound 136 ms after start |
| Injection heard far side | every phase | every phase (1-4 beeps) |
| Call states seen | RINGING(5) → ACTIVE(1) | DIALING(3) → ALERTING(4) → ACTIVE(1), mode IN_CALL from DIALING |
- So the helper keeps the Galaxy out of the call with **mic mute + REDIRECT + voice volume at minimum**, and restores all
  three when the call ends or the helper dies. The earpiece is faint at the ear and inaudible in a pocket.
- Changing mode/volume stalls the downlink read once (up to 300 ms): set them before streaming, not mid-call.
- Call state comes from `dumpsys telephony.registry` `mPreciseCallState` (foreground 1 ACTIVE, 3 DIALING, 4 ALERTING).
  An outgoing call's ringback is downlink audio, so the relay starts at DIALING for calls placed from the iPhone.

## Go / no-go: **go**
Next, in order:
1. Shell helper: the app starts `app_process` over Wireless debugging (own ADB pairing client, as iTell does);
   the helper serves record/track over a local socket that **only iBridge can use** (authenticate it).
   Handle Wi-Fi loss, reboot and Samsung Auto Blocker turning Wireless debugging off. Models: nectarlink
   `CallAudio.kt`, tether `HelperMain`.
2. Keep the Galaxy out of the call: mic mute (shown to keep injection working) plus a quiet earpiece (REDIRECT or
   volume 0, check by ear); inject buffer ~40 ms.
3. Codec over BLE: Opus 16 kHz, 20 ms frames, ~16-24 kbit/s; measure GATT throughput on this link; clock drift
   and packet-loss concealment. New PROTOCOL.md messages for call audio and call control (answer/end from the iPhone).
4. iPhone: CallKit (`CXProvider` incoming / outgoing) + `AVAudioSession` voiceChat, ~60 ms jitter buffer.
5. Measure mouth-to-ear both ways with the whole chain; hidden APIs can break on an OS update, so the helper checks
   `isPstnCallAudioInterceptable` at start and the app says so when it is false.

Prior art read first: thtbee/nectarlink (`CallAudio.kt`), jemcik/JemRec (`CallProbe.java`, scrcpy FakeContext),
amirwmr/tether (Galaxy A54 notes: same API on Exynos, not tried on a real PSTN call there), wangzan101/Mika, kevinzhow/Rungic.

## Phase 2 result (2026-11-25, real calls, Galaxy in a pocket)
- Incoming calls ring on the iPhone with CallKit, answered and spoken there; keypad tones and hang-up from the iPhone work.
  Calls placed from the iPhone keypad ring out, connect and carry speech both ways. After each call the Galaxy is back to
  mic unmuted, voice volume 8, MODE_NORMAL. ~50 frames/s each way, after the uplink jitter fix 0 frames dropped.
- Mouth-to-ear latency, measured with the Mac playing test sounds into one phone and recording the other's speaker
  (matched filter, 10 sounds per test; the Mac recorder needs `-af aresample=async=1` or it drops audio):
  | Direction | Through iBridge | Normal call on the Galaxy | iBridge adds |
  | --- | --- | --- | --- |
  | caller → iPhone | 0.86 s (0.81–0.97) | 0.45 s | ~0.4 s |
  | iPhone → caller | ~0.70 s (5 of 10 sounds agree; rough) | 0.36 s | ~0.35 s |
- Known contributors to cut next: helper AudioTrack queue (~120 ms), helper uplink jitter queue (2–5 frames), iPhone
  jitter buffer (3–5 frames), MediaCodec Opus batching, BLE connection interval (iOS default 30 ms).

## Latency cut (2026-11-25, four real calls, per-stage timing on both phones)
Each phone logs once a second how long every stage held the sound (`relay …: … | lag encode … queue … sent … decode …`
on Android, `audio: … depth … | gap …, lag mic … send … decode …` on the iPhone). Final call, 127 s, averages:
| Stage | Caller → iPhone | iPhone → caller |
| --- | --- | --- |
| framing (one 20 ms frame) | 20 ms | 20 ms (+ 7 ms in the mic ring) |
| Opus on Android (MediaCodec) | 9 ms encode | 6 ms decode |
| link queue + notify / iPhone send | 4 ms | < 1 ms |
| BLE, 15 ms interval | ~8 ms | ~8 ms |
| receiver's buffer | iPhone jitter 2.8 frames ≈ 55 ms + the frame playing | helper queue ~1.9 frames ≈ 38 ms |
| output | iPhone I/O 7.5 ms | helper track **72 ms** (one frame written ahead; the path adds ~50 ms) |
| **iBridge adds (sum)** | **~115 ms** (was ~400) | **~150 ms** (was ~350) |
- What moved it: a 15 ms connection interval during calls (Android opens a GATT client on the iPhone's link and asks for
  CONNECTION_PRIORITY_HIGH; iOS grants 15 ms, its default is 30 ms); the helper track cut to one frame (two frames 98 ms;
  half a frame starved it, underruns on every write); codecs waited for 2 ms and left most packets a frame late, now up to
  12 ms; jitter buffers on both sides start at 2 frames, drop excess within a second, and hold off 10 s after running dry.
- Left: the downlink arrives in 40–70 ms bursts (the call's capture, not BLE), and the link stalls 90–140 ms every 30 s
  or so: those give ~1 concealed frame per 20 s on the iPhone and ~1 silent frame per 15 s at the caller. Covering them
  would cost their length in delay.
- Not measured mouth-to-ear (the user preferred real conversations to test sounds); the sums leave out the network and
  the phones' own call paths, which a normal call has too. Users: "works great".
- Gotcha: unplugging or replugging USB restarts adbd on the Galaxy, which kills the helper and ends a relayed call.
  "Revoke USB debugging authorizations" also revokes iBridge's Wireless debugging pairing (set up again from the app).
