# Agent-neutral full-duplex MCP

`mcpserved-duplex` is a **separate, opt-in** MCP Streamable HTTP server. It brokers a conversation between exactly two independent MCP peers. Neither peer is a privileged host; both may send messages, requests, responses, events and cancellations at any time.

This is not the phone-control MCP server. It never inherits ADB authority, Android grants or application sessions. Running it does not arm or connect a phone.

## Start

From `mcp/`:

```sh
npm ci
npm run build
node dist/duplex-server.js tokens
MCP_DUPLEX_A_TOKEN='<first generated token>' MCP_DUPLEX_B_TOKEN='<second generated token>' npm run duplex
```

Both values above are independent secrets, generated locally. Supply each credential separately to its own agent host. Never put either in a committed file, query string, shared log, or prompt.

The default address is `http://127.0.0.1:8794`. Configure the two MCP clients with Streamable HTTP endpoints `http://127.0.0.1:8794/mcp/a` and `http://127.0.0.1:8794/mcp/b`, respectively. Each must send `Authorization: Bearer <its own token>`. Use `MCP_DUPLEX_PORT` to change the port or `MCP_DUPLEX_HOST` to change the bind interface. **Do not expose raw HTTP beyond loopback**; put any remote deployment behind a trusted TLS terminator and separate transport authorization. The server rejects browser Origin requests.

## Semantics

| Tool | Arguments | Effect |
| --- | --- | --- |
| `duplex_send` | `kind`, `body`, optional `replyTo` | Queues one envelope for the opposite peer. |
| `duplex_receive` | optional `after`, `limit` | Reads unacknowledged incoming envelopes. |
| `duplex_ack` | `through` | Frees delivered inbox entries through that sequence. |
| `duplex_status` | none | Reports queue state and whether the opposite peer has an SSE stream. |

`kind` is `message`, `event`, `request`, `response`, or `cancel`. The returned envelope has `seq`, `id`, `from`, `kind`, `body`, `time`, and optional `replyTo`. A request's `id` must be used as `replyTo` for the opposite peer's response, or for its sender's cancellation. Replays and wrong-side responses are rejected.

An authenticated `GET /mcp/a` or `GET /mcp/b` opens a live SSE stream. It emits MCP `notifications/message` with the envelope at `params.data`. A stream is advisory, not the durable inbox: reconnecting agents must call `duplex_receive` and acknowledge only when their host has processed each item. Both peers' POST and GET connections are independent, so speaking and listening can overlap.

Each inbound inbox is limited to 128 unacknowledged envelopes; bodies are limited to 32,768 UTF-8 bytes; outstanding requests to 256 with 10-minute expiry. Overflow rejects the send rather than discarding another agent's input. Queues are **in-memory only** and lost on process restart; delivery is at-least-once *while the process survives*, not exactly-once.

The broker is model-, agent-, and provider-independent. Aive may use its `DuplexMcpPeer` and session gateway adapter with these endpoints, subject to Aive's task and memory permissions. A tool reply only proves delivery by the broker: it does not prove that another agent acted. Neither side gains permissions by receiving content. Do not execute remote instructions automatically.

## Verification

```sh
cd mcp
npm test
```

`duplex-server.test.ts` covers simultaneous communication, correlation and cancellation ownership, backpressure, bearer separation, SSE notifications and authorization failures. A cross-process acceptance run with live Aive providers is separate; this release installs no default Aive connection or background listener.
