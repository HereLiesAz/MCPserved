/**
 * The MCPserved relay: a public (or self-hosted) rendezvous point that pairs
 * one device connection and one host connection by a shared room token, then
 * forwards bytes between them verbatim.
 *
 * It is deliberately dumb. It never parses, logs, or decrypts a payload — the
 * bytes it forwards are already sealed under MCPserved's device-pairing
 * secret (see `docs/guide/security.md` and `app/.../transport/FrameSession.kt`),
 * so a relay operator, whether that's you on your own VPS or a third party
 * fronting this with Cloudflare Tunnel/ngrok for a public HTTPS URL, sees only
 * opaque ciphertext. Room tokens are reachability config, not key material —
 * see `app/.../crypto/RelayToken.kt` for what a leaked one does and doesn't expose.
 */
import { createServer, type IncomingMessage } from "node:http";
import type { Duplex } from "node:stream";
import { WebSocketServer, type WebSocket } from "ws";
import { RoomRegistry, type PeerSocket, type Role } from "./rooms.js";

const CLOSE_ROLE_OCCUPIED = 4009;

function wrap(ws: WebSocket): PeerSocket {
  return {
    send: (data) => {
      if (ws.readyState === ws.OPEN) ws.send(data);
    },
    close: (code, reason) => ws.close(code, reason),
    onMessage: (cb) => ws.on("message", (data) => cb(data as Buffer)),
    onClose: (cb) => ws.on("close", cb),
  };
}

export interface RelayOptions {
  port: number;
  /** Rooms with no traffic for this long are closed and forgotten. */
  idleTimeoutMs: number;
}

export interface Relay {
  readonly port: number;
  readonly registry: RoomRegistry;
  close(): Promise<void>;
}

/**
 * Starts the relay and resolves once it is actually accepting connections.
 *
 * Async, rather than "fire and forget the listen() call," so a caller — a
 * test spinning up an ephemeral instance on port 0, or the CLI entrypoint
 * logging the bound port — can rely on [Relay.port] being the real one and
 * on connections made right after this resolves actually landing.
 */
export function startRelay(opts: RelayOptions): Promise<Relay> {
  const registry = new RoomRegistry();

  const http = createServer((_req, res) => {
    res.writeHead(200, { "content-type": "text/plain" });
    res.end("mcpserved relay: blind byte pipe, nothing to see here\n");
  });

  const wss = new WebSocketServer({ noServer: true });

  http.on("upgrade", (req: IncomingMessage, socket: Duplex, head: Buffer) => {
    const url = new URL(req.url ?? "", "http://relay.invalid");
    const roomToken = url.searchParams.get("room");
    const role = url.searchParams.get("role");

    if (url.pathname !== "/connect" || !roomToken || (role !== "device" && role !== "host")) {
      socket.destroy();
      return;
    }

    wss.handleUpgrade(req, socket, head, (ws) => {
      const result = registry.join(roomToken, role as Role, wrap(ws));
      if (!result.ok) {
        ws.close(CLOSE_ROLE_OCCUPIED, "room role already occupied");
      }
    });
  });

  // Sweep on a cadence bounded above by the timeout itself, so a very short
  // test-configured idleTimeoutMs still gets swept promptly.
  const sweepIntervalMs = Math.max(1_000, Math.min(opts.idleTimeoutMs, 60_000));
  const sweepTimer = setInterval(() => registry.sweep(opts.idleTimeoutMs), sweepIntervalMs);
  sweepTimer.unref();

  return new Promise((resolve) => {
    http.listen(opts.port, () => {
      const address = http.address();
      const boundPort = typeof address === "object" && address !== null ? address.port : opts.port;
      resolve({
        port: boundPort,
        registry,
        close: () =>
          new Promise((resolveClose) => {
            clearInterval(sweepTimer);
            wss.close(() => http.close(() => resolveClose()));
          }),
      });
    });
  });
}

function isMain(): boolean {
  return process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`;
}

if (isMain()) {
  const port = Number(process.env.RELAY_PORT ?? 8787);
  const idleTimeoutMs = Number(process.env.RELAY_IDLE_TIMEOUT_MS ?? 10 * 60_000);
  const relay = await startRelay({ port, idleTimeoutMs });
  console.log(`mcpserved relay listening on :${relay.port} (idle timeout ${idleTimeoutMs}ms)`);

  const shutdown = () => {
    relay.close().then(() => process.exit(0));
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}
