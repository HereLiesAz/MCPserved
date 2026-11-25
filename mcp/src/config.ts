import { lstatSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";
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

/** Reads the stored pairing, or throws with a pointer to pair if absent. */
export function loadConfig(path = CONFIG_PATH): Config {
  const config = tryLoadConfig(path);
  if (!config) throw new Error(`no pairing found at ${path} — run npx mcpserved pair first`);
  return config;
}

/** Returns null only for a missing pairing; invalid or inaccessible files fail closed. */
export function tryLoadConfig(path = CONFIG_PATH): Config | null {
  const invalid = () => new Error(`Pairing at ${path} is unreadable or invalid; repair the pairing before connecting.`);
  try {
    if (!lstatSync(path).isFile()) throw invalid();
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw invalid();
  }
  try {
    const stored = JSON.parse(readFileSync(path, "utf8")) as StoredConfig;
    if (typeof stored.deviceId !== "string" || !stored.deviceId.trim()) throw invalid();
    const decodeKey = (key: string): Buffer => {
      if (typeof key !== "string") throw invalid();
      const bytes = Buffer.from(key, "base64");
      if (bytes.length !== 32 || bytes.toString("base64") !== key) throw invalid();
      return bytes;
    };
    return { deviceId: stored.deviceId, port: resolvePort(), serverPrivateKey: decodeKey(stored.serverPrivateKey), devicePublicKey: decodeKey(stored.devicePublicKey) };
  } catch {
    // Parsing exceptions may contain private key material.
    throw invalid();
  }
}

/** Writes the pairing, creating the directory and restricting permissions. */
export function saveConfig(stored: StoredConfig): void {
  mkdirSync(dirname(CONFIG_PATH), { recursive: true, mode: 0o700 });
  writeFileSync(CONFIG_PATH, JSON.stringify(stored, null, 2), { mode: 0o600 });
}

export { CONFIG_PATH };
