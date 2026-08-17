# mcpserved-relay

A blind byte-pipe relay for MCPserved's opt-in remote-access path. It pairs
one device connection with one host connection by a shared room token, then
forwards bytes between them — nothing more.

**It never decrypts anything.** The bytes it forwards are already sealed
under the device's X25519 pairing secret (the same protocol
`LocalServer`/`FrameSession` speak locally), so whoever runs this relay —
you, on your own VPS, or a third party fronting it with Cloudflare Tunnel or
ngrok for a free public HTTPS URL — sees only opaque ciphertext. It does not
log payloads, does not persist rooms to disk, and forgets a room the moment
both sides disconnect.

This is *not* required for MCPserved's default local operation (adb-forward
or LAN). It exists only for the opt-in remote-access path — see
`docs/guide/remote-access.md` in the repo root for when and why you'd deploy
one.

## Running it

```bash
npm install
npm run build
RELAY_PORT=8787 node dist/server.js
```

Or with Docker:

```bash
docker build -t mcpserved-relay .
docker run -p 8787:8787 -e RELAY_IDLE_TIMEOUT_MS=600000 mcpserved-relay
```

### Environment variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `RELAY_PORT` | `8787` | Port the relay listens on. |
| `RELAY_IDLE_TIMEOUT_MS` | `600000` (10 min) | A room with no traffic for this long is closed and forgotten. |

## Getting a public HTTPS URL

The relay itself speaks plain `ws://` — deploying it behind a TLS-terminating
front end is the supported path, not something this project builds:

- **Cloudflare Tunnel**: `cloudflared tunnel --url http://localhost:8787`
  gives you a public `https://…trycloudflare.com` URL with zero DNS/cert
  setup. Use the `wss://` form of that URL when configuring the device and
  host.
- **ngrok**: `ngrok http 8787` does the same.
- **Your own reverse proxy** (Caddy, nginx, Traefik) in front of a relay
  deployed on a VPS you control, if you'd rather not depend on a third party
  at all.

Any of these are equally valid — since the relay is blind either way, the
choice is about convenience and who you trust to keep the endpoint up, not
about what the relay can see.

## Protocol

`GET /connect?room=<token>&role=device|host`, upgraded to a WebSocket. Two
connections in the same room (one `device`, one `host`) get paired; every
message one sends is forwarded verbatim to the other. A second connection
claiming an already-occupied role is closed with code `4009` rather than
displacing the existing one — guessing or leaking a room token lets a third
party deny service to a room, never impersonate a peer, since sealed frames
still require the pairing secret the relay never sees.

## Development

```bash
npm test
```

`test/rooms.test.ts` exercises the pairing/eviction logic directly with fake
sockets and a fake clock; `test/server.test.ts` drives the same logic through
a real WebSocket server and real `ws` clients on localhost.
