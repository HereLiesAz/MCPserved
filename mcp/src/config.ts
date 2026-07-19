import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

/**
 * Persisted pairing state for the desktop MCP server.
 *
 * Stored under the user's home directory rather than alongside the code, so that
 * a checkout of the repository never contains key material and a stray `git add`
 * cannot publish it.
 *
 * Only the raw keys are kept. The directional frame keys are derived per
 * connection from a fresh salt (see crypto.ts), so there is nothing durable to
 * store for them.
 */
export interface Config {
  deviceId: string;
  /** Loopback port on the desktop that `adb forward` bridges to the device. */
  port: number;
  serverPrivateKey: Buffer;
  devicePublicKey: Buffer;
}

interface StoredConfig {
  deviceId: string;
  serverPrivateKey: string;
  devicePublicKey: string;
}

const CONFIG_PATH = join(homedir(), ".config", "mcpserved", "pairing.json");

/** Default loopback port; must match LocalServer.DEFAULT_PORT on the device. */
const DEFAULT_PORT = 8790;

function resolvePort(): number {
  const raw = process.env.MCPSERVED_PORT;
  const n = raw ? Number(raw) : DEFAULT_PORT;
  return Number.isInteger(n) && n > 0 && n < 65536 ? n : DEFAULT_PORT;
}

/** Reads the stored pairing, or throws with a pointer to `pair` if absent. */
export function loadConfig(): Config {
  let stored: StoredConfig;
  try {
    stored = JSON.parse(readFileSync(CONFIG_PATH, "utf8"));
  } catch {
    throw new Error(
      `no pairing found at ${CONFIG_PATH} — run \`npx mcpserved pair\` first`,
    );
  }

  return {
    deviceId: stored.deviceId,
    port: resolvePort(),
    serverPrivateKey: Buffer.from(stored.serverPrivateKey, "base64"),
    devicePublicKey: Buffer.from(stored.devicePublicKey, "base64"),
  };
}

/** Like {@link loadConfig} but returns null instead of throwing when unpaired. */
export function tryLoadConfig(): Config | null {
  try {
    return loadConfig();
  } catch {
    return null;
  }
}

/** Writes the pairing, creating the directory and restricting permissions. */
export function saveConfig(stored: StoredConfig): void {
  mkdirSync(dirname(CONFIG_PATH), { recursive: true, mode: 0o700 });
  writeFileSync(CONFIG_PATH, JSON.stringify(stored, null, 2), { mode: 0o600 });
}

export { CONFIG_PATH };
