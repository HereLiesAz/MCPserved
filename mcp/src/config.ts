import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { deriveKeys, type FrameKeys } from "./crypto.js";

/**
 * Persisted pairing state for the MCP server.
 *
 * Stored under the user's home directory rather than alongside the code, so that
 * a checkout of the repository never contains key material and a stray `git add`
 * cannot publish it.
 */
export interface Config {
  deviceId: string;
  relayUrl: string;
  keys: FrameKeys;
}

interface StoredConfig {
  deviceId: string;
  relayUrl: string;
  serverPrivateKey: string;
  devicePublicKey: string;
}

const CONFIG_PATH = join(homedir(), ".config", "mcpserved", "pairing.json");

/** Reads and expands the stored pairing, deriving frame keys on load. */
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
    relayUrl: stored.relayUrl,
    keys: deriveKeys(
      Buffer.from(stored.serverPrivateKey, "base64"),
      Buffer.from(stored.devicePublicKey, "base64"),
    ),
  };
}

/** Writes the pairing, creating the directory and restricting permissions. */
export function saveConfig(stored: StoredConfig): void {
  mkdirSync(dirname(CONFIG_PATH), { recursive: true, mode: 0o700 });
  writeFileSync(CONFIG_PATH, JSON.stringify(stored, null, 2), { mode: 0o600 });
}

export { CONFIG_PATH };
