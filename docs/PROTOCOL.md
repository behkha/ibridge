# iBridge link protocol, version 1

The Android phone (holds the SIM) and the iPhone talk over Bluetooth LE only. There is no server and no account.
Every byte after the handshake is encrypted end to end with keys that exist only on the two phones.

Roles: **A** = Android, the GATT **peripheral** (advertises, hosts the service). **I** = iPhone, the GATT **central** (scans, connects).
iOS keeps a central alive in the background (`bluetooth-central` mode + state restoration); it does not advertise for a backgrounded peripheral, so the roles are fixed.

Byte order is big-endian. `||` is concatenation. Public keys are P-256 in uncompressed X9.63 form (65 bytes, `04 || X || Y`) unless stated.

## 1. GATT layout

| Item | UUID | Properties |
|---|---|---|
| Service | `e38d0001-747f-44a1-b7fc-4a024772eb87` | primary, advertised |
| `RX` (I → A) | `e38d0002-747f-44a1-b7fc-4a024772eb87` | Write, Write Without Response |
| `TX` (A → I) | `e38d0003-747f-44a1-b7fc-4a024772eb87` | Notify (CCCD `2902`) |

`RX` writes and the `TX` CCCD require an **encrypted link**, so the first connection makes the phones bond at the OS level (LE Secure Connections, one system prompt on each phone). The bond is for reconnection, not secrecy: with Android's identity key iOS resolves its rotating address, and a pending `connect` completes even while iBridge is suspended. All protection still comes from sections 4 and 5.

- **Leftover link**: when A's iBridge restarts, the radio link I opened stays up, and I hears only Service Changed. iOS wakes a suspended app neither for that nor for notifications on the replaced characteristics, and a GATT server cannot drop a link it did not open. So when A's GATT server starts and the iPhone that last held a live session is still on the link 2 s later without claiming the slot, A opens a GATT client connection on that link and releases it at once; with no app holding the link, Android's stack drops it after about 1 s. I wakes on the disconnect and its pending connect reaches the new run. A touches no other device's link.
- **Advertising**: A advertises the 128-bit service UUID, connectable, no name, `ADVERTISE_MODE_LOW_POWER` while linked or idle and `LOW_LATENCY` while the pairing sheet is open. iOS background scans must filter on this UUID.
- **Identity** is proven by the handshake, not by the address: Android rotates its address. I caches the peripheral identifier of the last good link and calls `connect` on it (pending connects never time out on iOS). If a handshake with some peripheral fails with `NOT_PAIRED`, I ignores that identifier for 15 minutes.
- **One link at a time**: A's GATT server sees every LE link the phone has (watches, earbuds), so a central takes A's single iBridge slot only when it subscribes to `TX` or writes `RX`. Another central that does either while the slot is taken is disconnected. A central with no live session 20 s after taking the slot is disconnected. A subscribe to `TX` from the holder of a live session means I started over on a link that never dropped: A discards the old session and treats it as a new claim. A keeps advertising until a session is live.
- **MTU**: I lets iOS negotiate (typically 185 to 517). A records `onMtuChanged`. Chunk size = `MTU − 3`; default MTU 23 gives 20-byte chunks.

## 2. Framing

A *packet* is one handshake message or one encrypted record, at most **65 535 bytes**. It travels as one or more *chunks*, each one GATT write (`RX`) or one notification (`TX`).

```
chunk = header(1) || payload(1 .. MTU-4)
header bit 7 FIRST   set on the first chunk of a packet
header bit 6 LAST    set on the last chunk of a packet (a one-chunk packet sets both)
header bits 0-5 SEQ  chunk counter mod 64, per direction, never reset during a connection
```

Rules:
- The receiver appends payloads from FIRST to LAST. A SEQ gap, a FIRST in the middle of a packet, a non-FIRST chunk with no packet open, or a packet over 65 535 bytes drops the connection.
- Flow control: A sends the next notification only after `onNotificationSent`. I uses Write Without Response and waits for `peripheralIsReady(toSendWriteWithoutResponse:)` when `canSendWriteWithoutResponse` is false.
- Priority: a sender keeps `AUDIO` packets in their own queue and, at each packet boundary, sends a waiting `AUDIO` packet before the next queued packet of any other type. Chunks of different packets never interleave.
- GATT delivers in order within one connection, so no acknowledgements or retransmits exist at this layer. Everything above it is idempotent or re-synced on reconnect (section 6).
- A disconnect discards any half-built packet and the session keys.

Packet = `type(1) || body`.

| Type | Name | Direction | Body |
|---|---|---|---|
| `0x01` | `PAIR_HELLO_REQ` | I → A | empty |
| `0x02` | `PAIR_HELLO` | A → I | `A_pub(65) || name(utf-8)` |
| `0x03` | `PAIR_REQUEST` | I → A | `mode(1) || I_pub(65) || Ei_pub(65) || name(utf-8)` |
| `0x04` | `PASS_COMMIT` | both | `round(1) || commit(32)` |
| `0x05` | `PASS_REVEAL` | both | `round(1) || nonce(16)` |
| `0x06` | `PAIR_PROOF` | I → A | `proof(32)` |
| `0x07` | `PAIR_ACCEPT` | A → I | `proof(32) || name(utf-8)` |
| `0x10` | `SESSION_HELLO` | I → A | `key_id(8) || Ei_pub(65)` |
| `0x11` | `SESSION_ACCEPT` | A → I | `Ea_pub(65)` |
| `0x20` | `DATA` | both | `counter(8) || ciphertext || tag(16)` |
| `0x21` | `AUDIO` | both | `counter(8) || ciphertext || tag(16)`, section 8 |
| `0x7F` | `ERROR` | both | `code(1) || reason(utf-8)` |

Error codes: `1 NOT_PAIRED`, `2 PAIRING_CLOSED`, `3 BAD_PROOF`, `4 TOO_MANY_ATTEMPTS`, `5 BUSY` (reserved), `6 BAD_PACKET`, `7 VERSION`. The sender disconnects after an `ERROR`.

## 3. Keys

| Key | Owner | Kept in |
|---|---|---|
| `A` static P-256 | Android | Android Keystore, `PURPOSE_AGREE_KEY` (API 31+), never exported |
| `I` static P-256 | iPhone | Secure Enclave (`SecureEnclave.P256.KeyAgreement`); Keychain software key on the Simulator |
| `Ea`, `Ei` ephemeral P-256 | per handshake | memory only |

After pairing, A stores `I_pub` and I stores `A_pub`. `key_id = SHA-256(I_pub)[0..8]`.

Primitives: ECDH P-256 (raw 32-byte X coordinate), HKDF-SHA256 (RFC 5869), HMAC-SHA256, AES-256-GCM with 12-byte nonce and 16-byte tag.
Strings inside hashes are ASCII without a terminator. A receiver rejects a public key that is not on the curve (`BAD_PACKET`).

## 4. Pairing

The pairing sheet opens a **pairing window** on A. A window lasts **5 minutes** and has:
- `token`: 16 random bytes, one-time, for the QR path;
- `code`: 6 characters from `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (Crockford base32, 30 bits), one-time, shown as `K7Q 29M`.

When the window ends, or one pairing succeeds, both are replaced. A failed code attempt replaces the code at once. After 5 failed attempts in a window A closes it (`TOO_MANY_ATTEMPTS`) until the user reopens the sheet. Outside a window, A answers pairing packets with `PAIRING_CLOSED`.

### 4.1 QR payload

```
ibridge://pair?v=1&k=<A_pub compressed, 33 bytes, base64url>&t=<token, base64url>&n=<A's display name, percent-encoded>
```

The QR is black on white, error-correction level M. The fallback code is shown under it and is **not** in the QR (it is the path for when the camera cannot read the QR).

### 4.2 QR path (`mode = 0x01`)

I already holds an authentic `A_pub` and the secret `token`.

1. I → `PAIR_REQUEST(0x01, I_pub, Ei_pub, name)`.
2. Both: `k_pair = HKDF(ikm = ECDH(Ei, A), salt = token, info = "ibridge-pair-v1" || A_pub || I_pub || Ei_pub, L = 32)`.
3. I → `PAIR_PROOF(HMAC(k_pair, "I"))`. A checks it; a mismatch is `BAD_PROOF`.
4. A → `PAIR_ACCEPT(HMAC(k_pair, "A"), name)`. I checks it.

A stranger cannot compute the proof without the 128-bit token, and cannot test guesses offline because `k_pair` also needs `ECDH(Ei, A)`.

### 4.3 Typed-code path (`mode = 0x02`)

A 30-bit code is too short to key an HMAC that an active attacker could brute-force offline, so the code is checked in **5 rounds of 6 bits**, each committed before it is revealed (like Bluetooth passkey entry). In each round A, which owns the code, reveals before I:
- A fake Android must reveal bits it does not know before I reveals anything, so a failed try teaches it nothing.
- A fake iPhone can learn one round's bits from A's reveal, but must already have committed to them; its wrong reveal, or hanging up before revealing, counts as a failed attempt on A, and **every failed or abandoned attempt replaces the code**. Success chance per attempt: 2⁻³⁰.

1. I → `PAIR_HELLO_REQ`. A → `PAIR_HELLO(A_pub, name)`.
2. I → `PAIR_REQUEST(0x02, I_pub, Ei_pub, name)`.
3. For `r = 0 … 4`, with `b_r` = bits `6r … 6r+5` of the code (first character = most significant). `r` and `b_r` enter the HMAC as one byte each:
   - I picks `Ni_r` (16 random bytes) → `PASS_COMMIT(r, HMAC(Ni_r, "ibridge-pass-v1" || "I" || A_pub || I_pub || Ei_pub || r || b_r))`
   - A picks `Na_r` → `PASS_COMMIT(r, HMAC(Na_r, "ibridge-pass-v1" || "A" || A_pub || I_pub || Ei_pub || r || b_r))`, then at once `PASS_REVEAL(r, Na_r)`.
   - I recomputes A's commit with its own `b_r`; mismatch → I aborts without revealing and shows "code didn't match".
   - I → `PASS_REVEAL(r, Ni_r)`. A recomputes I's commit; mismatch → `BAD_PROOF`, code replaced.
4. `k_pair = HKDF(ikm = ECDH(Ei, A), salt = SHA-256(Ni_0 || Na_0 || … || Ni_4 || Na_4), info = "ibridge-pair-v1" || A_pub || I_pub || Ei_pub, L = 32)`.
5. `PAIR_PROOF` and `PAIR_ACCEPT` as in 4.2.

### 4.4 After pairing

Both sides store the peer's static public key and display name, then I immediately runs a session handshake on the same connection. Pairing a new iPhone replaces the old one; `unpair` (section 6) or "Unlink" on A deletes the stored key on that side.

## 5. Session

Every connection starts here (after pairing, on the same connection).

1. I → `SESSION_HELLO(key_id, Ei_pub)`. A answers `NOT_PAIRED` if `key_id` is not its paired iPhone.
2. A → `SESSION_ACCEPT(Ea_pub)`.
3. Both compute (a Noise-KK-style triple DH: forward secrecy from the ephemerals, mutual authentication from the statics):
   ```
   ikm  = ECDH(Ei, Ea) || ECDH(Ei, A) || ECDH(I, Ea)
   salt = SHA-256("ibridge-session-v1" || A_pub || I_pub || Ei_pub || Ea_pub)
   okm  = HKDF(ikm, salt, info = "ibridge-app-v1", L = 64)
   k_ia = okm[0..32]   (I → A)
   k_ai = okm[32..64]  (A → I)
   ```
4. I sends `hello` (section 6) as its first `DATA`. A accepts no other message first and sends nothing encrypted until that `hello` decrypts. A answers with `status`; I treats the link as live only once that first record from A decrypts. Together this confirms both keys.

### 5.1 Records

```
DATA     = 0x20 || counter(8) || AES-256-GCM(key, nonce, plaintext, aad) || tag(16)
nonce    = dir(4) || counter(8)     dir = "i2a\0" for I → A, "a2i\0" for A → I
aad      = 0x20 || counter(8)
```

- **Counters**: per direction, start at 0 for each session, +1 per `DATA`.
- **Replay rejection**: the receiver accepts only `counter == expected` and then increments `expected`. A repeat, a gap or a failed tag ends the session and drops the connection. GATT is ordered, so an honest link never trips this.
- **Limits**: after 2³² records in one direction the sender disconnects and a fresh handshake rekeys.

- **`AUDIO` records** (section 8) use the same keys with their own counter per direction, starting at 0 for each session, and their own nonce: `dir = "I2A\0"` / `"A2I\0"`, `aad = 0x21 || counter(8)`. The receiver accepts any counter **greater** than the last `AUDIO` counter it accepted (a sender may drop frames before sealing them); anything else, or a failed tag, ends the session. `AUDIO` records are not counted against `DATA`'s counter, so a sender may put them ahead of queued `DATA`.

## 6. Messages

The plaintext of a `DATA` record is one UTF-8 JSON object with a type field `t`. Unknown types and unknown fields are ignored (forward compatibility). Times are Unix milliseconds. `sim` is the 1-based SIM slot.

### iPhone → Android

| `t` | Fields | Answer |
|---|---|---|
| `hello` | `app`, `ver`, `lang` | `status` |
| `sync` | `kind`: `sms` \| `calls` \| `contacts`; `cursor`: string or null; `limit` (max 200) | `page` |
| `send` | `req` (client id), `to`, `body`, `sim`? | `sent` |
| `read` | `ids`: [sms ids] | — (A marks them read where Android lets it, see "Read state") |
| `call.decline` | `call` | `call.end` |
| `call.answer` | `call` | `call.state` `active` and audio (section 8), or `call.end` if it no longer rings |
| `call.dial` | `req` (client id), `to`, `sim`? | `call.state` `dialing` with `req`, then audio; `call.end` `failed` with `req` if it could not be placed |
| `call.hangup` | `call` | `call.end` `ended` |
| `call.dtmf` | `call`, `digit` (`0-9 * #`) | — (A plays the tone into the call, 160 ms) |
| `bench` | `n`, `size` | debug: A sends `n` `AUDIO` records of stream 0 with `size` payload bytes, as fast as flow control allows; I then sends `n` back and A answers `bench.done` |
| `ping` | — | `pong` |
| `unpair` | — | A forgets `I_pub`, then disconnects. When the user unlinks while A is out of reach, I keeps `A_pub` only to send this at the next link (it keeps nothing A sends meanwhile) (even when the link looked live, since `send` only queues) and forgets it on `NOT_PAIRED` from that same phone or a new pairing |

### Android → iPhone

| `t` | Fields | When |
|---|---|---|
| `status` | `battery` 0–100, `charging`, `sims`: [{`sim`, `carrier`, `signal` 0–4}], `perms`: {`sms`, `calls`, `contacts`, `notifications`, `battery`}, `name`, `audio` (section 8) | after `hello`, then on any change, at most every 30 s |
| `page` | `kind`, `items`, `cursor`, `more` | answer to `sync` |
| `sms` | `id`, `thread`, `addr`, `name`?, `body`, `ts`, `dir`: `in` \| `out`, `read`, `sim`, `state`: `ok` \| `pending` \| `failed`, `code`? (one-time code found in an incoming text) | each text received or sent on A, and again when a stored text's `state` changes |
| `sms.read` | `ids`: [sms ids] | texts that became read on A (by any app) |
| `sms.gone` | `ids`: [sms ids] | texts deleted on A; I deletes them |
| `otp` | `sms` (its `id`), `code`, `from`, `ts` | right after an `sms` with a one-time code |
| `call` | `id`, `addr`, `name`?, `kind`: `in` \| `out` \| `missed` \| `rejected`, `ts`, `dur` (s), `sim` | each new call-log row |
| `calls.gone` | `ids`: [call ids] | call-log rows deleted on A (by the user or Android's own trimming); I deletes them |
| `call.ring` | `call` (event id), `addr`, `name`?, `sim`, `ts` | an incoming call starts ringing, and again after `hello` while it still rings |
| `call.end` | `call`, `how`: `answered` \| `missed` \| `declined` \| `ended` \| `failed`, `req`? | that call stops ringing (`missed` also covers a call declined on A; the `call` row's `kind` tells them apart, and I alerts on `kind: missed`); `answered` means answered **on A**. For a relayed call (section 8): `ended` when it ends on either side, `failed` when a `call.dial` could not be placed |
| `call.state` | `call`, `state`: `dialing` \| `alerting` \| `active` \| `held`, `dir`: `in` \| `out`, `addr`, `name`?, `sim`, `stream` (1–255), `req`? (echoes `call.dial`) | each state change of a relayed call (section 8), and again after `hello` while it lasts |
| `bench.done` | `n`, `ms` | debug: A received I's `n` bench records in `ms` |
| `contacts.changed` | — | A's contacts changed (checked at most every 10 s); I re-syncs contacts from `null` |
| `sent` | `req`, `ok`, `err`? | after a `send` finishes or fails |
| `pong` | `ts` | answer to `ping` |
| `unpair` | — | A was unlinked; I forgets `A_pub` |

### Items

- `sms` page items have the same fields as the `sms` message (without `t`).
- `calls` page items have the same fields as the `call` message.
- `contacts` page items: `id`, `name`, `phones`: [{`n`, `label`}].

### Sync and cursors

- The cursor is opaque to I. `null` means "from the start": the **full history** of texts, calls and contacts.
- A returns items oldest first and sets `more` while items remain. I asks again with the returned cursor. A page holds at most `limit` items and about **40 KB** of JSON, so a record stays well under the packet limit.
- A record that would still exceed the packet limit is not sent (A logs it).
- On every `hello` I re-syncs `sms` and `calls` from its stored cursors, so a text received while the link was down still arrives. Live `sms`/`call` messages and page items share ids, so I de-duplicates by `id`.
- A cursor is `"v2:<last id>"`; A treats any other cursor as `null`. I moves it past each live item's numeric `id` while no paged sync of that kind is running, so a reconnect only fetches what it missed.
- Contacts are re-synced from `null` when the user pulls to refresh, `status.perms.contacts` turns true, or `contacts.changed` arrives.

### While the link is down

Neither side drops what happened while the other was away.
- **A** keeps, across restarts, the ids of texts it saw change or go while I was away, and whether contacts changed (a change to texts or the call log made while A's service was not running is missed; contacts are compared with the last version I was told about). Right after its `status` answer to `hello` it sends an `sms` for each text whose `state` changed and that still exists, then `sms.read`, `sms.gone` and `calls.gone` (at most 500 ids per message), and `contacts.changed`. `push` only queues a message, so changes sent in the 15 s before a link drops (or before I starts over on the same link) are kept in memory and sent again at the next catch-up; every one of these messages is idempotent, and I matches a pending text only with an `sms` row it did not have before. Android's text store reuses the highest id after a delete: A then drops that id from its deleted set and sends the new row at catch-up, since I's cursor is already past it. New texts and calls come through the cursor sync instead. A `status` is also sent at once when `perms` change.
- **I** keeps an outbox of `send` and `read` messages and sends them in order once live. A `send` written to a live link stays in the outbox, shown as a pending text, until A's own `sms` for it arrives (same number, same body, dated at most 5 minutes before it was written); `sent` with `ok: false` shows it as failed. Once the next session's `sms` sync finishes, one still without its `sms` goes if A had answered `sent` ok, and is shown as failed otherwise, for the user to retry; it is never re-sent on its own: a duplicate text is worse than a retry.

### Read state

- I marks the incoming texts of a conversation read when the user opens it with the app in the foreground, and sends their ids in `read` (via the outbox).
- A sets `read = 1` on those rows. Android ignores writes to the text store by an app that is not the default SMS app unless the `WRITE_SMS` app-op is allowed (`adb shell appops set app.ibridge.android WRITE_SMS allow`); A then logs that nothing changed.
- When texts become read on A (by any app, "mark all read" included), A sends their ids in `sms.read`. On I, `read` only goes from false to true: a re-sent text never turns unread again.

### One-time codes

A detects a code in each incoming text: the text matches `code|کد|رمز|pin|otp|password|verification` (case-insensitive) and holds one run of 4 to 8 digits (Persian and Arabic digits are mapped to ASCII). It sends `sms` then `otp`. I shows the newest `otp` from the last 10 minutes as the code card.

## 7. Versioning

`v=1` in the QR, the `"ibridge-…-v1"` labels and `hello.ver` carry the version. A side that cannot speak the peer's version sends `ERROR VERSION`.

## 8. Calls with audio

A call is **relayed** when the iPhone answers it (`call.answer`) or places it (`call.dial`). Its sound then flows both ways as `AUDIO` records while A keeps the Galaxy out of the call: its microphone muted, the audio mode `MODE_CALL_REDIRECT`, the voice-call volume at its minimum, all restored when the call ends. A call answered or placed on A itself stays on A and is not relayed.

**Availability.** `status.audio` tells I whether relaying works now:
| Value | Meaning | I does |
|---|---|---|
| `ready` | A's audio helper runs and the phone allows call-audio interception | rings with CallKit, offers dialing |
| `setup` | Wireless debugging was never paired with iBridge | phase-1 notification only |
| `off` | the helper is not running and A cannot start it now (no Wi-Fi, Wireless debugging refused, Auto Blocker) | phase-1 notification only |
| `unsupported` | the phone refuses call-audio interception (`isPstnCallAudioInterceptable` false) | phase-1 notification only |

A missing `audio` means `setup`. A sends a new `status` as soon as `audio` changes.

**Flow.** Incoming: A sends `call.ring` as before; I with `audio: ready` reports it to CallKit; the user answers on the iPhone → `call.answer` → A answers, sends `call.state active`, and audio starts. Outgoing: `call.dial` → A places the call, sends `call.state dialing` (audio starts at once, so the iPhone hears the ringback), `alerting`, `active`. Either side ends it: `call.hangup` from I, or `call.end ended` from A when the far side or A's user hangs up. If the link drops during a relayed call, A keeps the call going for 20 s waiting for I to relink; after that it ends the call (a call nobody can hear is worse than a dropped one).

**`AUDIO` plaintext.**
```
stream(1) || seq(2) || opus(1 .. 400)
```
- `stream`: the relayed call, from `call.state` (A picks 1–255, a new one per call). `0` is the debug `bench` stream. A receiver drops frames of any other stream.
- `seq`: per stream and direction, +1 per 20 ms frame, wraps at 65 536. A gap means the sender dropped frames; the receiver conceals them.
- `opus`: one Opus packet, 16 kHz mono, 20 ms, VBR ~24 kbit/s, no FEC (GATT is lossless), no DTX.

**Timing.** Each side sends one frame per 20 ms. A sender that falls behind keeps at most 3 frames queued and drops the oldest. I's jitter buffer targets 60 ms and conceals a missing frame; when the clocks drift it drops or adds one frame of silence at a quiet moment. While audio flows A caps a `page` at 4 KB of JSON, so one `DATA` packet holds the link for at most a few connection events.
