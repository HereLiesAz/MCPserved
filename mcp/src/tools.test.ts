import test from "node:test";
import assert from "node:assert/strict";
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
