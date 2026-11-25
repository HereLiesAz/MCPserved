import test from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { resolveWindowsClaudeCommand } from "./install.js";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { tryLoadConfig } from "./config.js";
import { buildTools } from "./tools.js";
import { AdbLink } from "./adb-link.js";
import type { Link } from "./link.js";

test("ADB descriptions must not promise app-side filtering or grants", async () => {
  const tools = buildTools({
    caps: ["NOTIFICATIONS", "SHELL_SHIZUKU"],
    root: false,
    shizuku: true,
    a11y: false,
    adb: true,
  });
  const notice = tools.find((tool) => tool.name === "notifications");
  const grants = tools.find((tool) => tool.name === "grants_list");
  const shell = tools.find((tool) => tool.name === "shell");
  assert.ok(notice && grants && shell);
  assert.match(notice.description, /ALL packages/);
  assert.match(grants.description, /no per-app grants/);
  assert.match(shell.description, /unrestricted ADB/);
  const link: Link = {
    close() {},
    async send() {
      return {
        ok: true,
        items: [
          { pkg: "com.example.alpha", title: "A", text: "secret A" },
          { pkg: "com.example.beta", title: "B", text: "secret B" },
        ],
      };
    },
  };
  assert.equal(await notice.handler({}, link),
    "com.example.alpha: A — secret A\ncom.example.beta: B — secret B");
});

test("paired descriptions do not claim unrestricted ADB", () => {
  const tools = buildTools({
    caps: ["NOTIFICATIONS"], root: false, shizuku: false, a11y: true,
  });
  const notice = tools.find((tool) => tool.name === "notifications");
  assert.ok(notice);
  assert.doesNotMatch(notice.description, /ALL packages/);
});

test("ADB rejects shell metacharacters in numeric gesture arguments before invoking adb", async () => {
  const link = new AdbLink();
  const swipe = await link.send({
    op: "swipe", x1: "1; id", y1: 2, x2: 3, y2: 4,
  });
  const hold = await link.send({
    op: "long_press", x: 10, y: 20, ms: "1; id",
  });
  assert.equal(swipe.ok, false);
  assert.equal(hold.ok, false);
  assert.match(swipe.error, /invalid/);
  assert.match(hold.error, /invalid/);
});

test("pairing state distinguishes absent, malformed and valid files", () => {
  const dir = mkdtempSync(join(tmpdir(), "mcpserved-pairing-"));
  const path = join(dir, "pairing.json");
  try {
    assert.equal(tryLoadConfig(path), null);
    writeFileSync(path, "{");
    assert.throws(() => tryLoadConfig(path), /unreadable or invalid/);
    writeFileSync(path, JSON.stringify({ deviceId: "test", serverPrivateKey: "AA==", devicePublicKey: "AA==" }));
    assert.throws(() => tryLoadConfig(path), /unreadable or invalid/);
    writeFileSync(path, JSON.stringify({ deviceId: "test", serverPrivateKey: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", devicePublicKey: "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=" }));
    const config = tryLoadConfig(path);
    assert.ok(config);
    assert.equal(config.deviceId, "test");
    assert.deepEqual(config.serverPrivateKey, Buffer.alloc(32, 0));
    assert.deepEqual(config.devicePublicKey, Buffer.alloc(32, 1));
    assert.throws(() => tryLoadConfig(dir), /unreadable or invalid/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("auto ADB remains blocked after pairing or a pairing read failure", async () => {
  let unpaired = true;
  const link = new AdbLink(() => unpaired);
  assert.equal((await link.send({ op: "session_end" })).ok, true);
  unpaired = false;
  assert.equal((await link.send({ op: "session_end" })).ok, false);
  unpaired = true;
  assert.equal((await link.send({ op: "session_end" })).ok, false);
  const unreadable = new AdbLink(() => { throw new Error("unreadable"); });
  assert.equal((await unreadable.send({ op: "session_end" })).ok, false);
  assert.equal((await new AdbLink().send({ op: "session_end" })).ok, true);
});

test("Windows Claude registration resolves native and npm executables without a shell", () => {
  const dir = mkdtempSync(join(tmpdir(), "mcpserved-claude-"));
  try {
    const node = join(dir, "node.exe");
    const native = join(dir, "claude.exe");
    assert.equal(resolveWindowsClaudeCommand([dir], node), null);
    writeFileSync(native, "");
    assert.deepEqual(resolveWindowsClaudeCommand([dir], node), [native]);
    rmSync(native);
    writeFileSync(join(dir, "claude.cmd"), "not executed");
    const root = join(dir, "node_modules", "@anthropic-ai", "claude-code");
    mkdirSync(root, { recursive: true });
    const manifest = join(root, "package.json");
    const entry = join(root, "cli.js");
    writeFileSync(entry, "// not executed");
    writeFileSync(manifest, '{"bin":{"claude":"cli.js"}}');
    assert.deepEqual(resolveWindowsClaudeCommand([dir], node), [node, entry]);
    writeFileSync(manifest, '{"bin":{"claude":"../../outside.js"}}');
    writeFileSync(join(dir, "node_modules", "outside.js"), "");
    assert.equal(resolveWindowsClaudeCommand([dir], node), null);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
