import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { homedir, platform } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";
import { createInterface } from "node:readline/promises";

/**
 * `mcpserved install` — register this server with an MCP host in one command.
 *
 * Every host that speaks MCP over stdio needs the same three facts: a name, a
 * command to launch, and its arguments. What differs between hosts is only where
 * that lives on disk and the exact JSON key it hangs under. This command knows
 * those differences so the operator does not have to hand-edit a config file and
 * get the path, the key, and the JSON shape all right.
 *
 * It is deliberately conservative with files it did not write: it merges a single
 * entry into the host's existing config and leaves every other server untouched,
 * and if a file is present but not parseable as plain JSON (a hand-commented
 * settings file, say) it refuses to rewrite it and prints the snippet to paste
 * instead — corrupting someone's editor config is a worse outcome than one manual
 * step.
 */

/** How a host should launch this server. */
interface Launch {
  command: string;
  args: string[];
  env: Record<string, string>;
}

/** A host we can write config for. */
interface Target {
  id: string;
  label: string;
  /** Config file for this OS, or null when the host has no known path here. */
  path: () => string | null;
  /** Top-level key the host reads servers from. */
  key: "mcpServers" | "servers";
  /** One server entry, in this host's expected shape. */
  entry: (launch: Launch) => Record<string, unknown>;
  note?: string;
}

const SERVER_NAME = "mcpserved";

function appData(): string {
  // %APPDATA% on Windows; the closest equivalents elsewhere.
  if (process.env.APPDATA) return process.env.APPDATA;
  if (platform() === "darwin") return join(homedir(), "Library", "Application Support");
  return join(homedir(), ".config");
}

/** VS Code / Code-family user-config directory, per OS. */
function codeUserDir(app: "Code" | "Code - Insiders"): string {
  if (platform() === "darwin") return join(homedir(), "Library", "Application Support", app, "User");
  if (platform() === "win32") return join(appData(), app, "User");
  return join(homedir(), ".config", app, "User");
}

/**
 * The mcpServers-shaped entry most hosts use.
 *
 * `env` is emitted only when non-empty so an unpaired single-device setup gets a
 * clean two-line config rather than an empty object that reads like a mistake.
 */
function mcpServersEntry(launch: Launch): Record<string, unknown> {
  const e: Record<string, unknown> = { command: launch.command, args: launch.args };
  if (Object.keys(launch.env).length > 0) e.env = launch.env;
  return e;
}

/** VS Code wants an explicit transport type alongside the command. */
function vscodeEntry(launch: Launch): Record<string, unknown> {
  const e: Record<string, unknown> = { type: "stdio", command: launch.command, args: launch.args };
  if (Object.keys(launch.env).length > 0) e.env = launch.env;
  return e;
}

const TARGETS: Target[] = [
  {
    id: "claude-desktop",
    label: "Claude Desktop",
    key: "mcpServers",
    entry: mcpServersEntry,
    path: () => {
      if (platform() === "darwin")
        return join(homedir(), "Library", "Application Support", "Claude", "claude_desktop_config.json");
      if (platform() === "win32") return join(appData(), "Claude", "claude_desktop_config.json");
      return join(homedir(), ".config", "Claude", "claude_desktop_config.json");
    },
    note: "Restart Claude Desktop after installing.",
  },
  {
    id: "cursor",
    label: "Cursor",
    key: "mcpServers",
    entry: mcpServersEntry,
    path: () => join(homedir(), ".cursor", "mcp.json"),
    note: "Reload Cursor; check Settings → MCP for a green dot.",
  },
  {
    id: "vscode",
    label: "VS Code",
    key: "servers",
    entry: vscodeEntry,
    path: () => join(codeUserDir("Code"), "mcp.json"),
    note: "Requires GitHub Copilot with MCP (Agent mode). Start the server from the MCP view.",
  },
  {
    id: "vscode-insiders",
    label: "VS Code Insiders",
    key: "servers",
    entry: vscodeEntry,
    path: () => join(codeUserDir("Code - Insiders"), "mcp.json"),
  },
  {
    id: "windsurf",
    label: "Windsurf",
    key: "mcpServers",
    entry: mcpServersEntry,
    path: () => join(homedir(), ".codeium", "windsurf", "mcp_config.json"),
  },
  {
    id: "cline",
    label: "Cline (VS Code)",
    key: "mcpServers",
    entry: mcpServersEntry,
    path: () =>
      join(
        codeUserDir("Code"),
        "globalStorage",
        "saoudrizwan.claude-dev",
        "settings",
        "cline_mcp_settings.json",
      ),
  },
];

/**
 * Resolve how a host should launch this server.
 *
 * Default is the absolute node binary plus the absolute path to this build's
 * entry point — that works from a plain `git clone` with no publish and survives
 * the host running with a different working directory or PATH. `--npx` instead
 * emits `npx -y mcpserved`, which is shorter and self-updating but assumes the
 * package is installed or fetchable from a registry.
 *
 * Any MCPSERVED_* variables already set in this shell are baked in, so a
 * multi-device operator who ran `MCPSERVED_ADB_SERIAL=… mcpserved install` gets
 * that serial carried into the host config rather than losing it.
 */
function resolveLaunch(useNpx: boolean): Launch {
  const env: Record<string, string> = {};
  for (const k of ["MCPSERVED_MODE", "MCPSERVED_ADB_SERIAL", "MCPSERVED_ADB", "MCPSERVED_PORT"]) {
    const v = process.env[k];
    if (v) env[k] = v;
  }

  if (useNpx) return { command: "npx", args: ["-y", "mcpserved"], env };

  const entry = join(dirname(fileURLToPath(import.meta.url)), "index.js");
  return { command: process.execPath, args: [entry], env };
}

/**
 * Merge one server entry into a host config file.
 *
 * Returns "created", "updated" (replaced our own prior entry), or "blocked" when
 * the file exists but is not plain JSON — in which case nothing is written.
 */
function writeConfig(
  path: string,
  key: "mcpServers" | "servers",
  entry: Record<string, unknown>,
): "created" | "updated" | "blocked" {
  let root: Record<string, unknown> = {};
  let existed = false;

  if (existsSync(path)) {
    try {
      root = JSON.parse(readFileSync(path, "utf8")) as Record<string, unknown>;
    } catch {
      return "blocked";
    }
    existed = true;
  }

  const bucket =
    root[key] && typeof root[key] === "object"
      ? (root[key] as Record<string, unknown>)
      : {};
  const replacing = SERVER_NAME in bucket;
  bucket[SERVER_NAME] = entry;
  root[key] = bucket;

  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(root, null, 2) + "\n");

  return existed && replacing ? "updated" : "created";
}

/**
 * Claude Code has a first-class CLI for this; use it rather than guessing at its
 * config file, which is project-scoped and easy to corrupt. Falls back to
 * printing the command when the `claude` binary is not on PATH.
 */
function installClaudeCode(launch: Launch): boolean {
  const scope = ["-s", "user"];
  const cmd = ["mcp", "add", SERVER_NAME, ...scope];
  for (const [k, v] of Object.entries(launch.env)) cmd.push("-e", `${k}=${v}`);
  cmd.push("--", launch.command, ...launch.args);

  try {
    execFileSync("claude", cmd, { stdio: "ignore" });
    console.log("  Claude Code       ✓ added via `claude mcp add` (user scope)");
    return true;
  } catch {
    console.log("  Claude Code       ! `claude` not found on PATH. Run this yourself:");
    console.log(`      claude ${cmd.map((c) => (c.includes(" ") ? `"${c}"` : c)).join(" ")}`);
    return false;
  }
}

function printSnippet(target: Target, entry: Record<string, unknown>): void {
  const snippet = { [target.key]: { [SERVER_NAME]: entry } };
  console.log(`\n  Paste into ${target.label} config:\n`);
  console.log(
    JSON.stringify(snippet, null, 2)
      .split("\n")
      .map((l) => "    " + l)
      .join("\n"),
  );
}

function usage(): void {
  console.log(`\nmcpserved install — register this server with an MCP host.\n`);
  console.log(`Usage:`);
  console.log(`  mcpserved install [clients...] [--npx] [--print]`);
  console.log(`  mcpserved install --all`);
  console.log(`  mcpserved install --list\n`);
  console.log(`Clients:`);
  console.log(`  claude-desktop, claude-code, cursor, vscode, vscode-insiders,`);
  console.log(`  windsurf, cline\n`);
  console.log(`Flags:`);
  console.log(`  --all     write config for every supported client found`);
  console.log(`  --npx     launch via \`npx -y mcpserved\` instead of this build's path`);
  console.log(`  --print   show the JSON to paste; write nothing`);
  console.log(`  --list    list clients and exit\n`);
}

export async function install(argv: string[]): Promise<void> {
  const flags = new Set(argv.filter((a) => a.startsWith("--")));
  const names = argv.filter((a) => !a.startsWith("--"));

  if (flags.has("--help") || flags.has("-h")) return usage();
  if (flags.has("--list")) {
    console.log("\nSupported clients:");
    console.log("  claude-code (via `claude mcp add`)");
    for (const t of TARGETS) console.log(`  ${t.id.padEnd(16)} ${t.path() ?? "(unavailable on this OS)"}`);
    console.log("");
    return;
  }

  const useNpx = flags.has("--npx");
  const printOnly = flags.has("--print");
  const launch = resolveLaunch(useNpx);

  // Resolve which clients to target: explicit names, --all, or an interactive pick.
  let chosen: string[];
  if (flags.has("--all")) {
    chosen = ["claude-code", ...TARGETS.map((t) => t.id)];
  } else if (names.length > 0) {
    chosen = names;
  } else {
    chosen = await pick();
    if (chosen.length === 0) {
      console.log("Nothing selected.");
      return;
    }
  }

  console.log(
    `\nLaunch: ${launch.command} ${launch.args.join(" ")}` +
      (Object.keys(launch.env).length ? `  (env: ${Object.keys(launch.env).join(", ")})` : ""),
  );
  console.log("");

  for (const id of chosen) {
    if (id === "claude-code") {
      if (printOnly) {
        console.log("  Claude Code       run: claude mcp add mcpserved -s user -- " + launch.command + " " + launch.args.join(" "));
      } else {
        installClaudeCode(launch);
      }
      continue;
    }

    const target = TARGETS.find((t) => t.id === id);
    if (!target) {
      console.log(`  ${id.padEnd(18)}? unknown client (see --list)`);
      continue;
    }

    const path = target.path();
    if (!path) {
      console.log(`  ${target.label.padEnd(18)}- not available on this OS`);
      continue;
    }

    const entry = target.entry(launch);
    if (printOnly) {
      printSnippet(target, entry);
      continue;
    }

    const result = writeConfig(path, target.key, entry);
    if (result === "blocked") {
      console.log(`  ${target.label.padEnd(18)}! ${path} is not plain JSON — not rewriting.`);
      printSnippet(target, entry);
    } else {
      const verb = result === "updated" ? "updated" : "added to";
      console.log(`  ${target.label.padEnd(18)}✓ ${verb} ${path}`);
      if (target.note) console.log(`  ${" ".repeat(18)}  ${target.note}`);
    }
  }

  console.log(
    "\nDone. Before the model can act, make sure `adb devices` shows your phone " +
      "in state `device`\n(enable USB debugging, or `adb connect <ip>:5555` over Wi-Fi). " +
      "For per-app grants,\ninstall the app and run `mcpserved pair`.\n",
  );
}

/** Simple numbered multi-select for when no client was named. */
async function pick(): Promise<string[]> {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const menu = ["claude-code", ...TARGETS.map((t) => t.id)];

  console.log("\nWhich MCP host(s) should launch mcpserved?\n");
  menu.forEach((id, i) => {
    const label = id === "claude-code" ? "Claude Code" : TARGETS.find((t) => t.id === id)!.label;
    console.log(`  ${i + 1}) ${label}`);
  });
  console.log("");

  const answer = (await rl.question("Numbers (comma-separated), or 'all': ")).trim();
  rl.close();

  if (answer.toLowerCase() === "all") return menu;
  return answer
    .split(",")
    .map((s) => Number(s.trim()))
    .filter((n) => Number.isInteger(n) && n >= 1 && n <= menu.length)
    .map((n) => menu[n - 1]);
}
