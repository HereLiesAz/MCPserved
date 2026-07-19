import { spawn } from "node:child_process";

/**
 * Thin wrapper over the `adb` binary.
 *
 * Everything here is stateless: each call spawns adb afresh. That is slower than
 * holding a shell open, but it is robust against the device dropping off Wi-Fi
 * mid-session, and it keeps the desktop server from owning any long-lived handle
 * to the phone — which matters for a tool whose whole pitch is that the user is
 * in control of the connection.
 *
 * Target selection: `MCPSERVED_ADB_SERIAL` picks a specific device (a USB serial
 * or, for adb-over-Wi-Fi, an `ip:port` such as `192.168.1.5:5555`). With it
 * unset, adb's own default applies — fine when exactly one device is attached.
 * `MCPSERVED_ADB` overrides the binary path.
 */

const ADB = process.env.MCPSERVED_ADB || "adb";
const SERIAL = process.env.MCPSERVED_ADB_SERIAL;

function baseArgs(): string[] {
  return SERIAL ? ["-s", SERIAL] : [];
}

export interface AdbResult {
  stdout: Buffer;
  stderr: string;
  code: number;
}

/** Runs `adb <args>` and collects its output. Rejects only if adb cannot start. */
export function adbExec(
  args: string[],
  opts: { timeoutMs?: number } = {},
): Promise<AdbResult> {
  return new Promise((resolve, reject) => {
    const child = spawn(ADB, [...baseArgs(), ...args]);
    const out: Buffer[] = [];
    let err = "";

    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      reject(new Error(`adb ${args.join(" ")} timed out`));
    }, opts.timeoutMs ?? 30_000);

    child.stdout.on("data", (d: Buffer) => out.push(d));
    child.stderr.on("data", (d: Buffer) => (err += d.toString()));
    child.on("error", (e) => {
      clearTimeout(timer);
      // Most commonly ENOENT — adb is not on PATH.
      reject(e);
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      resolve({ stdout: Buffer.concat(out), stderr: err, code: code ?? 0 });
    });
  });
}

/**
 * Runs a shell command on the device.
 *
 * Text commands go through `shell`; binary output (screencap) goes through
 * `exec-out`, which does not translate `\n` to `\r\n` and so leaves a PNG
 * byte-exact. The command is passed as a single argument, so callers that
 * interpolate untrusted text must quote it with {@link shellQuote}.
 */
export function adbShell(
  cmd: string,
  opts: { binary?: boolean; timeoutMs?: number } = {},
): Promise<AdbResult> {
  const verb = opts.binary ? "exec-out" : "shell";
  return adbExec([verb, cmd], opts);
}

/** Runs a shell command and returns trimmed stdout as text, throwing on nonzero exit. */
export async function adbShellText(
  cmd: string,
  opts: { timeoutMs?: number } = {},
): Promise<string> {
  const r = await adbShell(cmd, opts);
  if (r.code !== 0) {
    throw new Error((r.stderr || r.stdout.toString()).trim() || `adb shell exited ${r.code}`);
  }
  return r.stdout.toString();
}

/** Bridges a local TCP port to the device. Idempotent; safe to call every connect. */
export async function adbForward(local: number, remote: number): Promise<void> {
  const r = await adbExec(["forward", `tcp:${local}`, `tcp:${remote}`], {
    timeoutMs: 10_000,
  });
  if (r.code !== 0) {
    throw new Error(r.stderr.trim() || `adb forward failed (${r.code})`);
  }
}

/** True when exactly one thing is reachable and it reports state `device`. */
export async function adbReady(): Promise<boolean> {
  try {
    const r = await adbExec(["get-state"], { timeoutMs: 8_000 });
    return r.code === 0 && r.stdout.toString().trim() === "device";
  } catch {
    return false;
  }
}

/**
 * Quotes a string for a single-quoted position in the device's shell.
 *
 * Wraps in single quotes and rewrites embedded single quotes as the usual
 * `'\''` dance. Everything else is literal inside single quotes, so this is
 * enough to make arbitrary text a safe argument.
 */
export function shellQuote(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`;
}
