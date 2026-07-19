/**
 * The one thing a backend must be: something that answers a protocol request.
 *
 * The tool surface (see tools.ts) is written entirely in terms of `send`, so a
 * backend is free to satisfy it however it likes. Two do: {@link
 * ./app-link.js#AppLink} carries sealed frames to the on-device app over a
 * loopback tunnel, and {@link ./adb-link.js#AdbLink} synthesizes the same
 * responses out of raw `adb` commands. The tools cannot tell them apart, which
 * is the point — the model gets one consistent device whether or not the app is
 * installed.
 *
 * `send` never rejects for an ordinary device-level refusal; those come back as
 * `{ ok: false, error }` so the model can reason about them. It rejects only for
 * a genuine transport failure the host should surface.
 */
export interface Link {
  send(request: unknown, timeoutMs?: number): Promise<any>;
  close(): void;
}
