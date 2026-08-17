import { randomBytes } from "node:crypto";
import WebSocket from "ws";
import { FrameCodec, InvalidFrame, deriveKeys } from "./crypto.js";
import type { Config } from "./config.js";
import type { Link } from "./link.js";

const PROTO_VERSION = 2;

/**
 * Connection to the on-device app over the opt-in relay, dialed as the "host"
 * role — the counterpart to the device's own relay client (role "device") on
 * the other end of the same room. See `relay/README.md`: the relay forwards
 * bytes verbatim and never decrypts anything, since what travels here is
 * MCPserved's already sealed-frame protocol, byte-for-byte identical to what
 * {@link ./app-link.js#AppLink} speaks over a loopback tunnel.
 *
 * Requires no local network path to the device at all — unlike {@link
 * ./app-link.js#AppLink}, this never calls `adb forward`. `MCPSERVED_MODE=relay`
 * plus `MCPSERVED_RELAY_URL` and `MCPSERVED_RELAY_ROOM` (the same room the
 * device's "Remote access" screen shows) select this backend; see index.ts.
 */
export class RelayLink implements Link {
  private ws: WebSocket | null = null;
  private codec: FrameCodec | null = null;
  private readonly aad: Buffer;
  private queue: Promise<unknown> = Promise.resolve();
  private pending: ((value: unknown) => void) | null = null;

  constructor(
    private readonly config: Config,
    private readonly relayUrl: string,
    private readonly room: string,
  ) {
    this.aad = Buffer.from(config.deviceId, "utf8");
  }

  async send(request: unknown, timeoutMs = 30_000): Promise<any> {
    const run = async () => {
      await this.ensureConnected();
      return this.exchange(request, timeoutMs);
    };

    // Chain onto the queue, keeping it alive through failures so one rejected
    // call does not poison every subsequent one.
    const result = this.queue.then(run, run);
    this.queue = result.catch(() => undefined);
    return result;
  }

  close(): void {
    this.ws?.close();
    this.ws = null;
    this.codec = null;
  }

  private async ensureConnected(timeoutMs = 15_000): Promise<void> {
    if (this.ws && this.ws.readyState === WebSocket.OPEN && this.codec) return;

    // Fresh per-connection salt and keys. The device folds the same salt in when
    // it reads the hello, so both sides land on the same directional keys.
    const salt = randomBytes(16);
    const keys = deriveKeys(this.config.serverPrivateKey, this.config.devicePublicKey, salt);
    const codec = new FrameCodec(keys.serverToDevice, keys.deviceToServer);

    const base = this.relayUrl.replace(/\/+$/, "");
    const url = `${base}/connect?room=${encodeURIComponent(this.room)}&role=host`;

    await new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(url);

      const timer = setTimeout(() => {
        ws.terminate();
        reject(new Error(`relay connection timed out after ${timeoutMs}ms`));
      }, timeoutMs);

      ws.once("error", (err) => {
        clearTimeout(timer);
        reject(err);
      });

      ws.once("open", () => {
        clearTimeout(timer);
        ws.send(JSON.stringify({ v: PROTO_VERSION, salt: salt.toString("base64url") }));
        this.ws = ws;
        this.codec = codec;
        this.attach(ws);
        resolve();
      });
    });
  }

  private attach(ws: WebSocket): void {
    ws.on("message", (data: Buffer) => {
      const line = data.toString("utf8");
      if (!line) return;

      let env: { deviceId: string; seq: number | string; payload: string };
      try {
        env = JSON.parse(line);
      } catch {
        return;
      }
      if (env.deviceId !== this.config.deviceId) return;

      let plaintext: Buffer;
      try {
        plaintext = this.codec!.open(BigInt(env.seq), env.payload, this.aad);
      } catch (e) {
        // Unopenable frames are noise or an attempt; do not answer, do not
        // surface. Only the paired device can produce a valid one.
        if (!(e instanceof InvalidFrame)) throw e;
        return;
      }

      const resolve = this.pending;
      this.pending = null;
      resolve?.(JSON.parse(plaintext.toString()));
    });

    ws.on("close", () => {
      this.ws = null;
      this.codec = null;
      const resolve = this.pending;
      this.pending = null;
      resolve?.({ ok: false, error: "relay connection closed" });
    });

    // Errors surface through 'close'; swallow so they do not become unhandled.
    ws.on("error", () => undefined);
  }

  private exchange(request: unknown, timeoutMs: number): Promise<any> {
    const ws = this.ws;
    const codec = this.codec;
    if (!ws || !codec) throw new Error("not connected");

    const sealed = codec.seal(Buffer.from(JSON.stringify(request), "utf8"), this.aad);

    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending = null;
        resolve({ ok: false, error: `device did not respond within ${timeoutMs}ms` });
      }, timeoutMs);

      this.pending = (value) => {
        clearTimeout(timer);
        resolve(value);
      };

      // seq as a JSON number: the device decodes it straight into a Long.
      ws.send(
        JSON.stringify({
          deviceId: this.config.deviceId,
          seq: Number(sealed.seq),
          payload: sealed.payload,
        }),
      );
    });
  }
}
