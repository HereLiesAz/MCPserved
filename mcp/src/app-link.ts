import net from "node:net";
import { randomBytes } from "node:crypto";
import { FrameCodec, InvalidFrame, deriveKeys } from "./crypto.js";
import { adbForward } from "./adb.js";
import type { Config } from "./config.js";
import type { Link } from "./link.js";

const PROTO_VERSION = 2;

/**
 * Connection to the on-device app over a loopback tunnel.
 *
 * There is no relay and no cloud. The app listens on `127.0.0.1:<port>` on the
 * phone; `adb forward` maps a port on this machine onto it; this link dials that.
 * So the "network" is a USB cable or an adb-over-Wi-Fi session the user
 * established, and the sealed frames never leave the pair of machines.
 *
 * Requests are strictly single-flight. The wire protocol carries a sequence
 * number but no correlation id, so responses are matched to requests by
 * ordering, which holds only while exactly one request is outstanding. The
 * device dispatches serially regardless.
 *
 * Each connection derives fresh keys from a random salt sent in the opening
 * hello, so the sequence counter starts at zero every time without any risk of
 * replaying a nonce under a reused key.
 */
export class AppLink implements Link {
  private sock: net.Socket | null = null;
  private codec: FrameCodec | null = null;
  private readonly aad: Buffer;
  private queue: Promise<unknown> = Promise.resolve();
  private pending: ((value: unknown) => void) | null = null;
  private buf = "";

  constructor(private readonly config: Config) {
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
    this.sock?.destroy();
    this.sock = null;
    this.codec = null;
  }

  private async ensureConnected(timeoutMs = 15_000): Promise<void> {
    if (this.sock && !this.sock.destroyed && this.codec) return;

    // Bridge the device's loopback port to ours. Harmless when already mapped.
    await adbForward(this.config.port, this.config.port);

    // Fresh per-connection salt and keys. The device folds the same salt in when
    // it reads the hello, so both sides land on the same directional keys.
    const salt = randomBytes(16);
    const keys = deriveKeys(
      this.config.serverPrivateKey,
      this.config.devicePublicKey,
      salt,
    );
    const codec = new FrameCodec(keys.serverToDevice, keys.deviceToServer);

    await new Promise<void>((resolve, reject) => {
      const sock = net.connect({ host: "127.0.0.1", port: this.config.port });

      const timer = setTimeout(() => {
        sock.destroy();
        reject(new Error(`connection timed out after ${timeoutMs}ms`));
      }, timeoutMs);

      sock.once("error", (err) => {
        clearTimeout(timer);
        reject(err);
      });

      sock.once("connect", () => {
        clearTimeout(timer);
        sock.setNoDelay(true);
        sock.write(
          JSON.stringify({ v: PROTO_VERSION, salt: salt.toString("base64url") }) + "\n",
        );
        this.sock = sock;
        this.codec = codec;
        this.buf = "";
        this.attach(sock);
        resolve();
      });
    });
  }

  private attach(sock: net.Socket): void {
    sock.on("data", (chunk: Buffer) => {
      this.buf += chunk.toString("utf8");
      let idx: number;
      while ((idx = this.buf.indexOf("\n")) >= 0) {
        const line = this.buf.slice(0, idx);
        this.buf = this.buf.slice(idx + 1);
        if (!line) continue;

        let env: { deviceId: string; seq: number | string; payload: string };
        try {
          env = JSON.parse(line);
        } catch {
          continue;
        }
        if (env.deviceId !== this.config.deviceId) continue;

        let plaintext: Buffer;
        try {
          plaintext = this.codec!.open(BigInt(env.seq), env.payload, this.aad);
        } catch (e) {
          // Unopenable frames are noise or an attempt; do not answer, do not
          // surface. Only the paired device can produce a valid one.
          if (!(e instanceof InvalidFrame)) throw e;
          continue;
        }

        const resolve = this.pending;
        this.pending = null;
        resolve?.(JSON.parse(plaintext.toString()));
      }
    });

    sock.on("close", () => {
      this.sock = null;
      this.codec = null;
      this.buf = "";
      const resolve = this.pending;
      this.pending = null;
      resolve?.({ ok: false, error: "connection closed" });
    });

    // Errors surface through 'close'; swallow so they do not become unhandled.
    sock.on("error", () => undefined);
  }

  private exchange(request: unknown, timeoutMs: number): Promise<any> {
    const sock = this.sock;
    const codec = this.codec;
    if (!sock || !codec) throw new Error("not connected");

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
      sock.write(
        JSON.stringify({
          deviceId: this.config.deviceId,
          seq: Number(sealed.seq),
          payload: sealed.payload,
        }) + "\n",
      );
    });
  }
}
