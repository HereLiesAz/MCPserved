import WebSocket from "ws";
import { FrameCodec, InvalidFrame } from "./crypto.js";
import type { Config } from "./config.js";

/**
 * Connection to the device, through the relay.
 *
 * Requests are strictly single-flight. The wire protocol carries a sequence
 * number but no correlation id, so responses are matched to requests by
 * ordering — which holds only while exactly one request is outstanding. The
 * device dispatches serially regardless, so a pipeline would gain nothing and
 * cost the one property the matching depends on.
 *
 * A request that goes unanswered fails rather than waiting indefinitely. The
 * device may have locked, lost its accessibility binding, or simply gone; a call
 * that hangs forever turns a recoverable failure into a stalled conversation.
 */
export class RelayLink {
  private ws: WebSocket | null = null;
  private codec: FrameCodec;
  private aad: Buffer;
  private queue: Promise<unknown> = Promise.resolve();
  private pending: ((value: unknown) => void) | null = null;

  constructor(private readonly config: Config) {
    this.codec = new FrameCodec(
      config.keys.serverToDevice,
      config.keys.deviceToServer,
    );
    this.aad = Buffer.from(config.deviceId, "utf8");
  }

  /**
   * Sends a request and resolves with the device's response.
   *
   * Serialized through an internal queue so that concurrent tool calls from the
   * model — which the MCP host is free to issue — cannot interleave on the wire.
   */
  async send(request: unknown, timeoutMs = 30_000): Promise<any> {
    const run = async () => {
      await this.ensureConnected();
      return this.exchange(request, timeoutMs);
    };

    // Chain onto the queue, and keep the queue alive through failures so one
    // rejected call does not poison every subsequent one.
    const result = this.queue.then(run, run);
    this.queue = result.catch(() => undefined);
    return result;
  }

  /** Closes the socket. The device treats this as an ordinary disconnect. */
  close(): void {
    this.ws?.close(1000, "closing");
    this.ws = null;
  }

  private async ensureConnected(timeoutMs = 15_000): Promise<void> {
    if (this.ws?.readyState === WebSocket.OPEN) return;

    // A device that has been asleep will not have a live socket at the relay.
    // Waking it first costs a couple of seconds; not waking it costs the whole
    // request, silently, because the relay drops frames with no peer.
    await this.wake();

    await new Promise<void>((resolve, reject) => {
      const url = `${this.config.relayUrl}/controller`;
      const ws = new WebSocket(url, {
        headers: { "X-Device-Id": this.config.deviceId },
      });

      // Every request is serialized through this.queue, so a connect that never
      // settles wedges all later calls behind it. Bound the attempt and tear the
      // half-open socket down if it lapses.
      const timer = setTimeout(() => {
        ws.removeAllListeners();
        ws.terminate();
        reject(new Error(`connection timed out after ${timeoutMs}ms`));
      }, timeoutMs);

      const onError = (err: Error) => {
        clearTimeout(timer);
        ws.removeAllListeners();
        reject(err);
      };

      ws.once("open", () => {
        clearTimeout(timer);
        ws.removeListener("error", onError);
        this.ws = ws;
        this.attach(ws);
        resolve();
      });

      ws.once("error", onError);
    });
  }

  private attach(ws: WebSocket): void {
    ws.on("message", (raw) => {
      let env: { deviceId: string; seq: string; payload: string };
      try {
        env = JSON.parse(raw.toString());
      } catch {
        return;
      }
      if (env.deviceId !== this.config.deviceId) return;

      let plaintext: Buffer;
      try {
        plaintext = this.codec.open(BigInt(env.seq), env.payload, this.aad);
      } catch (e) {
        // An unopenable frame is not answered and not surfaced. Only the paired
        // device can produce a valid one, so anything else is noise or an
        // attempt, and replying would confirm the device is here.
        if (!(e instanceof InvalidFrame)) throw e;
        return;
      }

      const resolve = this.pending;
      this.pending = null;
      resolve?.(JSON.parse(plaintext.toString()));
    });

    ws.on("close", () => {
      this.ws = null;
      const resolve = this.pending;
      this.pending = null;
      resolve?.({ ok: false, error: "connection closed" });
    });
  }

  private exchange(request: unknown, timeoutMs: number): Promise<any> {
    const ws = this.ws;
    if (!ws) throw new Error("not connected");

    const sealed = this.codec.seal(
      Buffer.from(JSON.stringify(request), "utf8"),
      this.aad,
    );

    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending = null;
        resolve({
          ok: false,
          error: `device did not respond within ${timeoutMs}ms`,
        });
      }, timeoutMs);

      this.pending = (value) => {
        clearTimeout(timer);
        resolve(value);
      };

      ws.send(
        JSON.stringify({
          deviceId: this.config.deviceId,
          seq: sealed.seq,
          payload: sealed.payload,
        }),
      );
    });
  }

  /**
   * Asks the relay to push a wake notification.
   *
   * Best-effort. A device already connected ignores it, and a relay without a
   * stored token returns 404 — neither is a reason to fail the request, since
   * the socket may come up on its own.
   */
  private async wake(): Promise<void> {
    const httpUrl = this.config.relayUrl
      .replace(/^wss:/, "https:")
      .replace(/^ws:/, "http:");

    try {
      await fetch(`${httpUrl}/wake`, {
        method: "POST",
        headers: { "X-Device-Id": this.config.deviceId },
      });
      // The device needs a moment to redial after a cold wake.
      await new Promise((r) => setTimeout(r, 2_000));
    } catch {
      // Relay unreachable. The connect attempt that follows will report it.
    }
  }
}
