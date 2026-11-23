# iBridge link protocol, version 1

The Android phone (holds the SIM) and the iPhone talk over Bluetooth LE only. There is no server and no account.
Every byte after the handshake is encrypted end to end with keys that exist only on the two phones.

Roles: **A** = Android, the GATT **peripheral** (advertises, hosts the service). **I** = iPhone, the GATT **central** (scans, connects).
iOS keeps a central alive in the background (`bluetooth-central` mode + state restoration); it does not do that for a peripheral, so the roles are fixed.

Byte order is big-endian. `||` is concatenation. Public keys are P-256 in uncompressed X9.63 form (65 bytes, `04 || X || Y`) unless stated.

## 1. GATT layout

| Item | UUID | Properties |
|---|---|---|
| Service | `e38d0001-747f-44a1-b7fc-4a024772eb87` | primary, advertised |
| `RX` (I → A) | `e38d0002-747f-44a1-b7fc-4a024772eb87` | Write, Write Without Response |
| `TX` (A → I) | `e38d0003-747f-44a1-b7fc-4a024772eb87` | Notify (CCCD `2902`) |

- **Advertising**: A advertises the 128-bit service UUID, connectable, no name, `ADVERTISE_MODE_LOW_POWER` while linked or idle and `LOW_LATENCY` while the pairing sheet is open. iOS background scans must filter on this UUID.
- **Identity** is proven by the handshake, not by the address: Android rotates its address. I caches the peripheral identifier of the last good link and calls `connect` on it (pending connects never time out on iOS). If a handshake with some peripheral fails with `NOT_PAIRED`, I ignores that identifier for 15 minutes.
- **One link at a time**: A serves one paired iPhone. A second central that connects gets `ERROR BUSY` and is disconnected.
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
| `0x7F` | `ERROR` | both | `code(1) || reason(utf-8)` |

Error codes: `1 NOT_PAIRED`, `2 PAIRING_CLOSED`, `3 BAD_PROOF`, `4 TOO_MANY_ATTEMPTS`, `5 BUSY`, `6 BAD_PACKET`, `7 VERSION`. The sender disconnects after an `ERROR`.

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

A 30-bit code is too short to key an HMAC that an active attacker could brute-force offline, so the code is checked in **5 rounds of 6 bits**, each committed before it is revealed (the same construction as Bluetooth passkey entry). An attacker in the middle must guess each round's bits before seeing them: success chance 2⁻²⁴ per attempt at best, and every failure replaces the code.

1. I → `PAIR_HELLO_REQ`. A → `PAIR_HELLO(A_pub, name)`.
2. I → `PAIR_REQUEST(0x02, I_pub, Ei_pub, name)`.
3. For `r = 0 … 4`, with `b_r` = bits `6r … 6r+5` of the code (first character = most significant). `r` and `b_r` enter the HMAC as one byte each:
   - I picks `Ni_r` (16 random bytes) → `PASS_COMMIT(r, HMAC(Ni_r, "ibridge-pass-v1" || "I" || A_pub || I_pub || Ei_pub || r || b_r))`
   - A picks `Na_r` → `PASS_COMMIT(r, HMAC(Na_r, "ibridge-pass-v1" || "A" || A_pub || I_pub || Ei_pub || r || b_r))`
   - I → `PASS_REVEAL(r, Ni_r)`. A recomputes I's commit with its own `b_r`; mismatch → `BAD_PROOF`.
   - A → `PASS_REVEAL(r, Na_r)`. I recomputes; mismatch → abort and show "code didn't match".
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
4. I sends `hello` (section 6) as its first `DATA`. A accepts no other message first and sends nothing encrypted until that `hello` decrypts; this confirms both keys. No `hello` within 10 s → A disconnects.

### 5.1 Records

```
DATA     = 0x20 || counter(8) || AES-256-GCM(key, nonce, plaintext, aad) || tag(16)
nonce    = dir(4) || counter(8)     dir = "i2a\0" for I → A, "a2i\0" for A → I
aad      = 0x20 || counter(8)
```

- **Counters**: per direction, start at 0 for each session, +1 per `DATA`.
- **Replay rejection**: the receiver accepts only `counter == expected` and then increments `expected`. A repeat, a gap or a failed tag ends the session and drops the connection. GATT is ordered, so an honest link never trips this.
- **Limits**: after 2³² records in one direction the sender disconnects and a fresh handshake rekeys.

## 6. Messages

The plaintext of a `DATA` record is one UTF-8 JSON object with a type field `t`. Unknown types and unknown fields are ignored (forward compatibility). Times are Unix milliseconds. `sim` is the 1-based SIM slot.

### iPhone → Android

| `t` | Fields | Answer |
|---|---|---|
| `hello` | `app`, `ver`, `lang` | `status` |
| `sync` | `kind`: `sms` \| `calls` \| `contacts`; `cursor`: string or null; `limit` (max 200) | `page` |
| `send` | `req` (client id), `to`, `body`, `sim`? | `sent` |
| `call.decline` | `call` | `call.end` |
| `ping` | — | `pong` |
| `unpair` | — | A forgets `I_pub`, then disconnects |

### Android → iPhone

| `t` | Fields | When |
|---|---|---|
| `status` | `battery` 0–100, `charging`, `sims`: [{`sim`, `carrier`, `signal` 0–4}], `perms`: {`sms`, `calls`, `contacts`, `notifications`, `battery`}, `name` | after `hello`, then on any change, at most every 30 s |
| `page` | `kind`, `items`, `cursor`, `more` | answer to `sync` |
| `sms` | `id`, `thread`, `addr`, `name`?, `body`, `ts`, `dir`: `in` \| `out`, `read`, `sim`, `state`: `ok` \| `pending` \| `failed` | each text received or sent on A |
| `otp` | `sms` (its `id`), `code`, `from`, `ts` | right after an `sms` with a one-time code |
| `call` | `id`, `addr`, `name`?, `kind`: `in` \| `out` \| `missed` \| `rejected`, `ts`, `dur` (s), `sim` | each new call-log row |
| `call.ring` | `call` (event id), `addr`, `name`?, `sim`, `ts` | an incoming call starts ringing |
| `call.end` | `call`, `how`: `answered` \| `missed` \| `declined` \| `ended` | that call stops ringing |
| `sent` | `req`, `ok`, `sms`? (id of the stored text), `err`? | after a `send` finishes or fails |
| `pong` | `ts` | answer to `ping` |
| `unpair` | — | A was unlinked; I forgets `A_pub` |

### Items

- `sms` page items have the same fields as the `sms` message (without `t`).
- `calls` page items have the same fields as the `call` message.
- `contacts` page items: `id`, `name`, `phones`: [{`n`, `label`}].

### Sync and cursors

- The cursor is opaque to I. `null` means "from the start": texts and calls from the last **30 days**, contacts from the first one.
- A returns items oldest first (texts, calls) and sets `more` while items remain. I asks again with the returned cursor.
- On every `hello` I re-syncs `sms` and `calls` from its stored cursors, so a text received while the link was down still arrives. Live `sms`/`call` messages and page items share ids, so I de-duplicates by `id`.
- Contacts are re-synced from `null` when the user pulls to refresh or `status.perms.contacts` turns true.

### One-time codes

A detects a code in each incoming text: the text matches `code|کد|رمز|pin|otp|password|verification` (case-insensitive) and holds one run of 4 to 8 digits (Persian and Arabic digits are mapped to ASCII). It sends `sms` then `otp`. I shows the newest `otp` from the last 10 minutes as the code card.

## 7. Versioning

`v=1` in the QR, the `"ibridge-…-v1"` labels and `hello.ver` carry the version. A side that cannot speak the peer's version sends `ERROR VERSION`.
