import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { homedir, platform } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync, spawn } from "node:child_process";
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
  /**
   * Whether this host can connect to a remote MCP server by URL with custom
   * headers. Those get a native `url` + `Authorization` entry for the direct
   * device path; the rest are bridged through the `mcp-remote` stdio shim.
   */
  directNative?: boolean;
  note?: string;
}

const SERVER_NAME = "mcpserved";

/** A non-null, non-array object — the only shape we'll merge a server into. */
function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

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
    directNative: true,
    path: () => join(homedir(), ".cursor", "mcp.json"),
    note: "Reload Cursor; check Settings → MCP for a green dot.",
  },
  {
    id: "vscode",
    label: "VS Code",
    key: "servers",
    entry: vscodeEntry,
    directNative: true,
    path: () => join(codeUserDir("Code"), "mcp.json"),
    note: "Requires GitHub Copilot with MCP (Agent mode). Start the server from the MCP view.",
  },
  {
    id: "vscode-insiders",
    label: "VS Code Insiders",
    key: "servers",
    entry: vscodeEntry,
    directNative: true,
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
    let parsed: unknown;
    try {
      parsed = JSON.parse(readFileSync(path, "utf8"));
    } catch {
      return "blocked";
    }
    // Merge only into a JSON object. If the file parsed to an array, string, or
    // number, it isn't a shape we understand — rewriting it would lose data, so
    // treat it as blocked and let the caller print a snippet instead.
    if (!isPlainObject(parsed)) return "blocked";
    root = parsed;
    existed = true;
  }

  // typeof null and typeof [] are both "object"; guard against both. An array
  // here would accept the assignment but drop it on JSON.stringify.
  const current = root[key];
  const bucket = isPlainObject(current) ? current : {};
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

  // On Windows the global CLI is claude.cmd/.ps1; execFile resolves that name,
  // whereas a bare "claude" fails with ENOENT. Elsewhere the binary is on PATH.
  const binary = platform() === "win32" ? "claude.cmd" : "claude";
  try {
    execFileSync(binary, cmd, { stdio: "ignore" });
    console.log("  Claude Code       ✓ added via `claude mcp add` (user scope)");
    return true;
  } catch (err) {
    const notFound = (err as NodeJS.ErrnoException)?.code === "ENOENT";
    const why = notFound ? "`claude` not found on PATH" : "`claude mcp add` failed";
    console.log(`  Claude Code       ! ${why}. Run this yourself:`);
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

  try {
    console.log("\nWhich MCP host(s) should launch mcpserved?\n");
    menu.forEach((id, i) => {
      const label = id === "claude-code" ? "Claude Code" : TARGETS.find((t) => t.id === id)!.label;
      console.log(`  ${i + 1}) ${label}`);
    });
    console.log("");

    const answer = (await rl.question("Numbers (comma-separated), or 'all': ")).trim();

    if (answer.toLowerCase() === "all") return menu;
    return answer
      .split(",")
      .map((s) => Number(s.trim()))
      .filter((n) => Number.isInteger(n) && n >= 1 && n <= menu.length)
      .map((n) => menu[n - 1]);
  } finally {
    rl.close();
  }
}

// ===========================================================================
// connect — wire a host straight to the device's own MCP-over-HTTP endpoint.
//
// `install` registers the desktop bridge (a stdio server on this machine).
// `connect` skips the bridge entirely: the phone is the MCP server, and this
// sets a host up to talk to it directly. It brings up the `adb forward` tunnel,
// then writes each host's config — a native `url` + `Authorization` header where
// the host supports it, or the `mcp-remote` stdio↔HTTP shim where it does not.
// ===========================================================================

const DEFAULT_MCP_HTTP_PORT = 8791;

interface ConnectOpts {
  token?: string;
  port: number;
  host?: string;
  serial?: string;
  noForward: boolean;
  print: boolean;
  all: boolean;
  list: boolean;
  help: boolean;
  names: string[];
}

function parseConnectArgs(argv: string[]): ConnectOpts {
  const o: ConnectOpts = {
    port: DEFAULT_MCP_HTTP_PORT,
    noForward: false,
    print: false,
    all: false,
    list: false,
    help: false,
    names: [],
  };
  // Consume the next arg as a flag's value, but only if it is not itself a flag.
  // Without this, `--token --print` would take `--print` as the token and then
  // never process it.
  let i = 0;
  const takeValue = (): string | undefined => {
    const next = argv[i + 1];
    if (next === undefined || next.startsWith("--")) return undefined;
    i++;
    return next;
  };

  for (; i < argv.length; i++) {
    const a = argv[i];
    switch (a) {
      case "--token": o.token = takeValue(); break;
      case "--port": {
        const n = Number(takeValue());
        if (Number.isInteger(n) && n > 0 && n < 65536) o.port = n;
        break;
      }
      case "--host": o.host = takeValue(); break;
      case "--serial": o.serial = takeValue(); break;
      case "--no-forward": o.noForward = true; break;
      case "--print": o.print = true; break;
      case "--all": o.all = true; break;
      case "--list": o.list = true; break;
      case "--help":
      case "-h": o.help = true; break;
      default:
        if (!a.startsWith("--")) o.names.push(a);
    }
  }
  return o;
}

/** The mcp-remote invocation that bridges a stdio-only host to the HTTP endpoint. */
function shimArgs(url: string, token: string): string[] {
  return ["-y", "mcp-remote", url, "--header", `Authorization: Bearer ${token}`];
}

/** Direct entry for a host that speaks HTTP with headers natively. */
function directNativeEntry(
  target: Target,
  url: string,
  token: string,
): Record<string, unknown> {
  const headers = { Authorization: `Bearer ${token}` };
  // VS Code (key "servers") wants an explicit transport type.
  return target.key === "servers" ? { type: "http", url, headers } : { url, headers };
}

/** Direct entry for a stdio-only host: launch the mcp-remote shim. */
function shimEntry(url: string, token: string): Record<string, unknown> {
  return { command: "npx", args: shimArgs(url, token) };
}

/** Sets up `adb forward tcp:port tcp:port`, honoring an explicit --serial. */
function forwardPort(port: number, serial?: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const adb = process.env.MCPSERVED_ADB || "adb";
    const args = [...(serial ? ["-s", serial] : []), "forward", `tcp:${port}`, `tcp:${port}`];
    const child = spawn(adb, args);
    let err = "";
    child.stderr.on("data", (d: Buffer) => (err += d.toString()));
    child.on("error", reject);
    child.on("close", (code) =>
      code === 0 ? resolve() : reject(new Error(err.trim() || `adb forward exited ${code}`)),
    );
  });
}

/** Registers the mcp-remote shim with Claude Code via its CLI. */
function connectClaudeCode(url: string, token: string): void {
  const cmd = ["mcp", "add", SERVER_NAME, "-s", "user", "--", "npx", ...shimArgs(url, token)];
  const binary = platform() === "win32" ? "claude.cmd" : "claude";
  try {
    execFileSync(binary, cmd, { stdio: "ignore" });
    console.log("  Claude Code       ✓ added via `claude mcp add` (mcp-remote shim)");
  } catch (err) {
    const notFound = (err as NodeJS.ErrnoException)?.code === "ENOENT";
    const why = notFound ? "`claude` not found on PATH" : "`claude mcp add` failed";
    console.log(`  Claude Code       ! ${why}. Run this yourself:`);
    console.log(`      claude ${cmd.map((c) => (c.includes(" ") ? `"${c}"` : c)).join(" ")}`);
  }
}

async function promptToken(): Promise<string> {
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try {
    console.log("\nPaste the bearer token from the app's Pair screen (Copy token only).");
    return (await rl.question("token: ")).trim();
  } finally {
    rl.close();
  }
}

function connectUsage(): void {
  console.log(`\nmcpserved connect — wire a host to the device's own MCP server.\n`);
  console.log(`Usage:`);
  console.log(`  mcpserved connect [clients...] --token <t> [--port 8791]`);
  console.log(`  mcpserved connect --all --token <t>`);
  console.log(`  mcpserved connect [clients...] --host <ip:port> --token <t>   # LAN, no adb\n`);
  console.log(`Clients: claude-desktop, claude-code, cursor, vscode, vscode-insiders, windsurf, cline\n`);
  console.log(`Flags:`);
  console.log(`  --token <t>    bearer token from the app (else \$MCPSERVED_TOKEN, else prompt)`);
  console.log(`  --port <n>     device MCP port (default 8791; sets up adb forward)`);
  console.log(`  --host <h:p>   connect over the network to this address; skips adb forward`);
  console.log(`  --serial <s>   target this adb device for the forward`);
  console.log(`  --no-forward   don't run adb forward (you'll set the tunnel up yourself)`);
  console.log(`  --all          every supported client found`);
  console.log(`  --print        show the JSON to paste; write nothing`);
  console.log(`  --list         list clients and exit\n`);
}

export async function connect(argv: string[]): Promise<void> {
  const o = parseConnectArgs(argv);
  if (o.help) return connectUsage();
  if (o.list) {
    console.log("\nSupported clients:");
    console.log("  claude-code (via `claude mcp add` + mcp-remote)");
    for (const t of TARGETS) {
      const how = t.directNative ? "native url+headers" : "mcp-remote shim";
      console.log(`  ${t.id.padEnd(16)} ${how}`);
    }
    console.log("");
    return;
  }

  // Endpoint: a --host connects over the network directly; otherwise it is the
  // device's loopback port reached through an adb-forward tunnel.
  const url = o.host ? `http://${o.host}/mcp` : `http://127.0.0.1:${o.port}/mcp`;
  const doForward = !o.host && !o.noForward;

  const token = (o.token ?? process.env.MCPSERVED_TOKEN ?? "").trim() || (await promptToken());
  if (!token) {
    console.log("A bearer token is required — copy it from the app's Pair screen.");
    return;
  }

  const chosen = o.all
    ? ["claude-code", ...TARGETS.map((t) => t.id)]
    : o.names.length > 0
      ? o.names
      : await pick();
  if (chosen.length === 0) {
    console.log("Nothing selected.");
    return;
  }

  if (doForward && !o.print) {
    try {
      await forwardPort(o.port, o.serial);
      console.log(`\nadb forward tcp:${o.port} → device tcp:${o.port}  ✓`);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      console.log(`\nadb forward failed: ${msg}`);
      console.log(`Set it up yourself:  adb forward tcp:${o.port} tcp:${o.port}`);
    }
  }

  console.log(`\nEndpoint: ${url}\n`);

  for (const id of chosen) {
    if (id === "claude-code") {
      if (o.print) {
        console.log("  Claude Code       run: claude mcp add mcpserved -s user -- npx " + shimArgs(url, token).join(" "));
      } else {
        connectClaudeCode(url, token);
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

    const entry = target.directNative
      ? directNativeEntry(target, url, token)
      : shimEntry(url, token);

    if (o.print) {
      printSnippet(target, entry);
      continue;
    }

    const result = writeConfig(path, target.key, entry);
    if (result === "blocked") {
      console.log(`  ${target.label.padEnd(18)}! ${path} is not plain JSON — not rewriting.`);
      printSnippet(target, entry);
    } else {
      const verb = result === "updated" ? "updated" : "added to";
      const how = target.directNative ? "" : " (via mcp-remote)";
      console.log(`  ${target.label.padEnd(18)}✓ ${verb} ${path}${how}`);
      if (target.note) console.log(`  ${" ".repeat(18)}  ${target.note}`);
    }
  }

  console.log(
    "\nDone. Make sure the app is installed, armed, and reachable by adb, then " +
      "reload the host.\nThe token is the boundary — a request without it gets 401. " +
      "Rotate it in the app to revoke.\n",
  );
}
