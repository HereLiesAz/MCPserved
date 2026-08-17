# Security model

MCPserved's security rests on a small set of decisions: enforcement lives on the
device, not the server; the transport is sealed and authenticated even over a
local link; and the loopback binding plus pairing key mean nothing off-device — and
no unpaired process on-device — can drive the service. This page describes the
app-backend security model. The pure-adb path has a very different, device-wide
authority profile, covered at the end.

## Where authority lives

Enforcement lives **on the device**, in `Enforcer`, and not in the MCP server. The
server is downstream of a language model's output and is therefore not a trust
boundary — it can be persuaded, and a permission check that can be talked out of is
decoration. The server carries sealed frames; the device decides what to permit.

## Pairing — X25519, out of band, both directions

Pairing is a one-time out-of-band exchange (`crypto/Pairing.kt`, `mcp/src/pair.ts`):

1. The device generates an X25519 keypair and renders its public half and its
   device id as a QR code: `mcpserved:2:<deviceId>:<b64url pubkey>`.
2. The desktop server scans it, generates **its own** X25519 keypair, and shows
   its public half back for the device to scan.
3. Both sides compute the same X25519 shared secret and never transmit it.

Both public keys travel by QR, **in both directions**, so no third party ever sits
in the exchange that establishes trust. The payload is versioned (`2`) so a future
format change fails to parse loudly rather than being misread; a wrong-length key
(not 32 bytes) is rejected rather than padded, since a key that silently becomes a
different key produces a shared secret that differs only on one side.

**Pairing confers no authority.** It establishes only that two endpoints share a
secret. What may actually be done is decided afterwards, per package, in the grants
screen.

## Per-connection salt → fresh directional keys

The shared secret is never used directly as a frame key. On each connection the
desktop server picks **fresh random salt** and sends it in the opening `Hello`;
both sides fold that salt into HKDF-SHA256 to derive **two directional 256-bit
keys** (`deriveKeys`):

- `mcpserved d2s v1` → device-to-server key
- `mcpserved s2d v1` → server-to-device key

Two keys, not one, and here is why it matters. With a single shared key both
directions would draw nonces from the same space, and a request and a response
carrying the same sequence number would reuse a nonce. Under ChaCha20-Poly1305
nonce reuse is not a weakening but a **break**: the keystream repeats and the
Poly1305 authenticator becomes forgeable. Separate keys let each direction run its
own counter with no coordination.

Folding the fresh salt into the KDF means **every connection derives distinct
keys**, so each connection's sequence counter may safely restart at zero. This is
what lets an MCP host relaunch the desktop server — which resets its counter —
without the device rejecting the first frame as a replay under the old key.

## Sealed frames — ChaCha20-Poly1305

Every protocol frame is sealed with ChaCha20-Poly1305 (`crypto/Frame.kt`,
`mcp/src/crypto.ts`):

- **Nonce:** 96-bit, a big-endian monotonic counter in the low 8 bytes with 4
  leading zero bytes. Byte-identical on both sides.
- **Counter ownership:** the counter is owned by the codec and cannot be set from
  outside, so nonce reuse is structurally impossible rather than merely
  discouraged. It resets only when keys are re-derived (re-pair / new connection),
  never on mid-connection reconnect — a socket that drops and returns is the same
  peer under the same key, and a rewound counter would replay nonces on the very
  failure path the transport exists to survive.
- **AAD = device id.** The additional authenticated data is the device id, so a
  frame cannot be replayed against a different device even by a peer that forwards
  it there.
- **Replay / monotonic sequence:** `open()` rejects any sequence `<=` the highest
  accepted (`throw Invalid`/`InvalidFrame`), closing replay entirely. The high-water
  mark advances **only after the tag verifies** — advancing on receipt would let
  anyone reaching the socket burn sequence numbers with garbage and stall the real
  peer.

The two crypto implementations (Kotlin, TypeScript) are duplicated rather than
shared because the runtimes cannot import from each other; every constant — HKDF
info strings, nonce layout, tag length, AAD — must match exactly, or frames fail
authentication on arrival with no indication of which side is wrong.

## Wildcard binding, reached via adb-forward, the LAN, IPv6, UPnP, or a relay

The app's control server (`LocalServer`) binds the **IPv4 wildcard
(`0.0.0.0`)**, port `8790` by default — not loopback. This is what lets a
desktop reach it two ways: through an `adb forward tcp:8790 tcp:8790` tunnel
onto its own `127.0.0.1` (the `AppLink` sets that up itself on connect), or
directly over the LAN at an address the device advertised over mDNS (see
`LanAdvertiser`). Three more ways exist only on explicit opt-in — listening
directly on the device's own global IPv6 address (`setIpv6Enabled`),
requesting a UPnP IGD port mapping (`UpnpPortMapper`), and dialing out to a
relay (`RemoteRelayClient`) — covered separately below and in
[remote-access](remote-access.md). All five carry the identical sealed-frame
protocol; the bind address and the transport widen who can *reach the socket*,
never who can *do anything with it*.

The bind is **not, by itself, an authorization boundary** — any app on the
device, and now any host on the LAN, can open the port. Authorization is the
**pairing key**: a connection that cannot produce frames sealed under the
shared secret gets no answer and no acknowledgement that anything is listening
(unopenable frames are dropped silently, so an unauthenticated sender cannot
even confirm the device is here or which device it is). The grant table then
decides, per package, what an authenticated peer may actually do.

Each of the five listens on its own socket and serves that socket's
connections one at a time, strictly serial — but with both the IPv4 and the
opt-in IPv6 socket live, two connections can be in flight at once, one on
each. `Dispatcher.handle` guards its own state with a mutex specifically so
that stays correct rather than relying on there being only one accept loop.

### The IPv6 path — opt-in, no NAT to defeat

`LocalServer.setIpv6Enabled` binds a second `ServerSocket` to the device's
current global IPv6 unicast address — not the IPv6 wildcard `::`, which would
contend with the IPv4 socket already bound to `0.0.0.0` above for the same
port on most stacks; a concrete address sidesteps that ambiguity entirely.
Off by default and gated behind the same disclosure as the relay path.

This is the one opt-in path with no intermediary of any kind, not even a
router: IPv6 addresses are globally routable without NAT, so a global address
is, definitionally, already reachable from anywhere that isn't specifically
firewalled off. That "isn't specifically firewalled off" is doing real work,
though — home routers commonly pass IPv6 straight through (nothing to
configure, since there is no NAT translation step to insert a rule into), but
cellular carriers vary, and some run a stateful firewall on IPv6 for the exact
reason a phone listening on a public address is normally a bad idea. The app
has no way to detect or influence that from either side.

The address itself is not stable: Android's RFC 4941 privacy addresses rotate
periodically, and any network change replaces it outright. A supervisory loop
rechecks every few minutes and rebinds when it changes, tearing down the old
socket first — see `LocalServer`'s KDoc for why running the IPv4 and IPv6
accept loops concurrently is safe despite each looking, on its own, like it
assumes strict single-connection serialization.

### The relay path — opt-in, blind by construction

`RemoteRelayClient` dials a relay URL the operator configures, off by default
and gated behind its own prominent disclosure (see
[android-app](android-app.md)). It carries the **same sealed frames** as every
path above — `FrameSession` is the identical handshake and dispatch logic all
of them share (extracted from what used to be `LocalServer`'s own inline
connection handler) — so the relay operator, whoever
runs it, only ever forwards ChaCha20-Poly1305 ciphertext it holds no key for.
See `relay/README.md` for the relay's own (deliberately dumb) protocol.

This is a materially different security posture from the direct MCP endpoint's
loopback default below: that endpoint's only protection, ever, is the bearer
token over plaintext HTTP, safe specifically because loopback keeps it off any
wire an eavesdropper could be on. The relay path is never offered for that
endpoint — only for this already end-to-end-encrypted one — for exactly that
reason.

### The UPnP path — opt-in, direct, no third party

`UpnpPortMapper` asks a UPnP IGD-capable router to forward an external port
straight to this device's `LocalServer` port, off by default and gated behind
the same disclosure as the relay path. Unlike the relay, there is no third
party in the data path at all once the mapping is live — but for exactly that
reason it is only ever offered for this already end-to-end-encrypted port,
never the direct MCP endpoint below: a live mapping is reachable by anyone who
finds it (the open internet gets port-scanned routinely), and only a protocol
whose authorization boundary is the pairing key rather than the network being
trusted is safe to expose that way. It only works on a Wi-Fi network whose
router supports and permits UPnP — never on cellular, which has no router to
ask — and many home routers ship with UPnP disabled. Discovery and mapping use
a plain unicast `DatagramSocket` request/response (the `weupnp` library never
joins a multicast group), so no additional Android permission beyond
`INTERNET` is needed. The mapping is re-requested every few minutes rather
than cached, since the external address or port can move under router DHCP
churn or lease renewal.

## The direct MCP endpoint — bearer token over loopback HTTP

The app also runs an MCP server directly (`transport/McpServer.kt`), so a host can
connect to the phone without the desktop bridge. It speaks MCP's Streamable HTTP
and binds to **`127.0.0.1`** by default, port `8791` — the classic loopback
posture, reached through an `adb forward tcp:8791 tcp:8791` tunnel. Nothing
off-device can connect by default.

The bind host is configurable, opt-in and off by default, gated behind the same
prominent disclosure as the relay path: `RemoteAccessStore.wildcardMcpBind`
widens it to `0.0.0.0`, intended for use behind a private mesh (Tailscale,
WireGuard) the operator installs and joins separately — this device makes no
attempt to find or join one itself. This endpoint never gets a relay path (see
above); its only protection is the bearer token below, which is fine inside a
mesh that already encrypts the transport end to end, and is exactly the
tradeoff to weigh before enabling it on anything else.

Loopback is again **not** the authorization boundary; the **bearer token** is
(`crypto/McpToken.kt`). Every request must carry `Authorization: Bearer <token>`;
one that does not gets `401` and no other signal. The token is 256 bits of
`SecureRandom`, generated on the device, kept in `EncryptedSharedPreferences`, and
shown only in the app for the operator to copy. It is compared in constant time
(`MessageDigest.isEqual`) so a wrong guess leaks nothing through timing.
**Rotating the token** invalidates any host still configured with the old one —
the HTTP path's equivalent of rotating the pairing identity.

This endpoint carries the **same [Dispatcher](protocol.md)** as the sealed path:
the session gate, the grant table, and `Enforcer.guard` bracketing apply
identically. Only the transport and its authenticator differ — sealed frames under
a paired X25519 secret for the desktop bridge, a bearer token over loopback HTTP
for a direct host. Both are transports into one enforcement; neither decides
anything.

Unlike the sealed-frame transport, the HTTP endpoint is not itself encrypted — it
relies on the loopback binding (traffic never leaves the device) and the token.
Reaching it over adb-over-Wi-Fi inherits adb's own transport security, exactly as
the sealed path's adb tunnel does.

## Key storage

Pairing keys live in `EncryptedSharedPreferences` on the device (AES256-SIV keys /
AES256-GCM values, master key AES256-GCM) and never leave it. On the desktop, the
pairing is stored at `~/.config/mcpserved/pairing.json` (directory `0700`, file
`0600`), under the home directory rather than alongside the code so a checkout
never contains key material. Only raw keys are stored; directional frame keys are
derived per connection and never persisted.

## Grant bracketing (defense in depth)

Every mutating operation is bracketed on the device (`Enforcer.guard`):

1. read the foreground package,
2. refuse unless a live grant confers the required scope,
3. perform the action,
4. read the foreground package **again**.

Step 4 exists because the window can change between check and act — a dialog, a
notification, another app coming forward. Without the second read, a tap aimed at a
granted screen can land on the confirmation button of something never granted, and
return success. A detected change rolls nothing back (nothing here is reversible);
it flags the response so the caller discards stale node ids and re-observes.

## Trust boundaries

| Boundary | Trusted? | Why |
| --- | --- | --- |
| The language model / MCP host | **No** | Can be persuaded. Never enforces authority. |
| The desktop MCP server | **No** | Downstream of the model; carries frames, decides nothing. |
| The loopback/wildcard socket | **No** by location | Any on-device app, and — if wildcard-bind is opted into — any host on the LAN, can dial it. |
| The relay (opt-in) | **No**, and structurally can't be | Forwards sealed frames it holds no key for; see "The relay path" above. Untrusted whether self-hosted or third-party. |
| The pairing key | **Yes** | Only a peer holding the shared secret can produce an openable frame. |
| The grant table (on device) | **Yes** | The actual policy: per package, per scope, revocable, expiring. |
| The device / its owner | **Yes** | Enables the service, pairs, arms, grants. The only party that says yes. |

## Revocation

- **Empty the grant table** (or use the notification's **Revoke all**): the peer
  can connect but every operation is denied.
- **Rotate identity** (Pair screen): mints a new key and forgets the old peer —
  the **only complete revocation**, because the peer can no longer produce a valid
  frame and cannot arrive at all.

## The pure-adb path's device-wide authority caveat

The quick-connect `AdbLink` backend has **none** of the above per-package model.
`adb` holds **shell-level authority over the whole device** — which is exactly what
enabling USB debugging conferred. There is no grant table, no per-app scoping, and
the "session" is a thin convenience. This is **disclosed** in the capability report
(`grants_list` returns empty) and in the session notice, rather than dressed up as
something narrower. When per-app authority matters, use the paired app.

Note also that the sealed-frame transport applies only to the **app** backend;
the adb backend's security *is* adb's own (the USB-debugging authorization and the
transport `adb` itself provides). See [backends](backends.md).
