import { adbReady, adbShell, adbShellText, shellQuote } from "./adb.js";
import type { Link } from "./link.js";

/**
 * Controls the device purely through `adb`, no on-device app required.
 *
 * This is the quick-connect path: enable USB debugging (or adb-over-Wi-Fi),
 * plug in, and a model can drive the phone. Every protocol op is answered by
 * shelling out — `input` for gestures and keys, `uiautomator dump` for the tree,
 * `screencap` for pixels — and shaped into the same response the on-device app
 * would return, so the tool layer never learns which backend it is talking to.
 *
 * What adb cannot honestly do, it says so about rather than faking. There is no
 * per-app grant model here: adb holds shell-level authority over the whole
 * device, and that is disclosed in the capability report and the session notice
 * rather than dressed up as something narrower.
 */
export class AdbLink implements Link {
  /** Centres of the nodes from the last ui_tree, so taps can address by id. */
  private nodeCenters = new Map<string, { x: number; y: number }>();
  private nodeBounds = new Map<string, Bounds>();

  close(): void {
    // Nothing persistent to close; every call spawns adb afresh.
  }

  async send(request: any, _timeoutMs = 30_000): Promise<any> {
    try {
      switch (request?.op) {
        case "capabilities":
          return await this.capabilities();
        case "session_begin":
          return await this.sessionBegin(request.ttlSec ?? 300);
        case "session_end":
          return { ok: true, foregroundChanged: false };
        case "grants_list":
          // adb has no per-app grant model; the whole device is reachable.
          return { ok: true, grants: [] };
        case "apps_list":
          return await this.appsList(request.grantedOnly ?? true);
        case "ui_tree":
          return await this.uiTree();
        case "screenshot":
          return await this.screenshot();
        case "notifications":
          return await this.notifications();
        case "tap":
          return await this.tap(request);
        case "long_press":
          return await this.longPress(request);
        case "swipe":
          return await this.swipe(request);
        case "scroll":
          return await this.scroll(request);
        case "type":
          return await this.type(request);
        case "key":
          return await this.key(request.key);
        case "launch":
          return await this.launch(request.pkg);
        case "shell":
          return await this.shell(request.cmd);
        case "clipboard_get":
        case "clipboard_set":
          return {
            ok: false,
            error:
              "clipboard is not available over adb — pair the on-device app for clipboard access",
          };
        default:
          return { ok: false, error: `unsupported op: ${request?.op}` };
      }
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      // ENOENT means adb itself is missing; make that actionable.
      if (message.includes("ENOENT")) {
        return {
          ok: false,
          error:
            "adb not found on PATH — install platform-tools, or set MCPSERVED_ADB to the adb binary",
        };
      }
      return { ok: false, error: message };
    }
  }

  // ---- capabilities & session -------------------------------------------

  private async capabilities(): Promise<any> {
    if (!(await adbReady())) {
      return {
        ok: false,
        error:
          "no adb device — attach over USB and run `adb devices`, or `adb connect <ip>:5555` for Wi-Fi",
      };
    }

    let root = false;
    try {
      const id = await adbShellText("su -c id", { timeoutMs: 5_000 });
      root = /uid=0/.test(id);
    } catch {
      root = false;
    }

    // adb shell is itself a shell-level backend; report it as such so the shell
    // tool is offered. "shizuku" here stands for that ADB-level authority.
    const caps = [
      "TREE",
      "GESTURE",
      "TEXT_INPUT",
      "GLOBAL_KEYS",
      "CAPTURE_SILENT",
      "NOTIFICATIONS",
      "SHELL_SHIZUKU",
    ];
    if (root) caps.push("SHELL_ROOT");

    return { ok: true, caps, root, shizuku: true, a11y: false };
  }

  private async sessionBegin(ttlSec: number): Promise<any> {
    // Best-effort wake so the screen is on and unlocked enough to act on.
    try {
      await adbShell("input keyevent 224", { timeoutMs: 5_000 }); // WAKEUP
      await adbShell("wm dismiss-keyguard", { timeoutMs: 5_000 });
    } catch {
      // A device that will not wake is still worth trying to drive.
    }
    return {
      ok: true,
      sessionId: "adb",
      expiresAtEpochMs: Date.now() + ttlSec * 1000,
      foregroundChanged: false,
    };
  }

  // ---- reading ----------------------------------------------------------

  private async appsList(grantedOnly: boolean): Promise<any> {
    // No grant model over adb, so "granted" is universal. When grantedOnly is
    // set, narrow to third-party packages to keep the list to what a task
    // usually means rather than dumping every system component.
    const flag = grantedOnly ? " -3" : "";
    const out = await adbShellText(`pm list packages${flag}`);
    const apps = out
      .split("\n")
      .map((l) => l.trim())
      .filter((l) => l.startsWith("package:"))
      .map((l) => l.slice("package:".length))
      .filter(Boolean)
      .map((pkg) => ({ pkg, label: pkg, granted: true }));
    return { ok: true, apps };
  }

  private async uiTree(): Promise<any> {
    // uiautomator writes to a file; read it back byte-exact via exec-out.
    let dumpPath = "/sdcard/window_dump.xml";
    try {
      const out = await adbShellText("uiautomator dump", { timeoutMs: 15_000 });
      const m = out.match(/dumped to:\s*(\S+)/);
      if (m) dumpPath = m[1];
    } catch (e) {
      return { ok: false, error: `uiautomator dump failed: ${(e as Error).message}` };
    }

    const xmlRes = await adbShell(`cat ${dumpPath}`, { binary: true, timeoutMs: 15_000 });
    if (xmlRes.code !== 0) {
      return { ok: false, error: "could not read the ui dump" };
    }
    const xml = xmlRes.stdout.toString("utf8");

    const parsed = parseUiAutomator(xml);
    this.nodeCenters.clear();
    this.nodeBounds.clear();
    for (const n of parsed.nodes) {
      this.nodeCenters.set(n.id, {
        x: Math.round((n.bounds.l + n.bounds.r) / 2),
        y: Math.round((n.bounds.t + n.bounds.b) / 2),
      });
      this.nodeBounds.set(n.id, n.bounds);
    }

    return {
      ok: true,
      pkg: parsed.pkg,
      activity: null,
      nodes: parsed.nodes,
      pruned: parsed.pruned,
      foregroundChanged: false,
    };
  }

  private async screenshot(): Promise<any> {
    const res = await adbShell("screencap -p", { binary: true, timeoutMs: 20_000 });
    if (res.code !== 0 || res.stdout.length === 0) {
      return { ok: false, error: "screencap failed" };
    }
    const png = res.stdout;
    const { w, h } = pngSize(png);
    // maxPx is not honoured: adb has no image scaler on the host side. The raw
    // frame is returned; the tool description already steers toward ui_tree.
    return {
      ok: true,
      mime: "image/png",
      b64: png.toString("base64"),
      w,
      h,
      foregroundChanged: false,
    };
  }

  private async notifications(): Promise<any> {
    // Best-effort parse of the notification dump. adb exposes no clean list, so
    // this is heuristic; the on-device app's NotificationListener is the honest
    // path and is offered as the upgrade.
    let out = "";
    try {
      out = await adbShellText("dumpsys notification --noredact", { timeoutMs: 10_000 });
    } catch {
      return { ok: true, items: [] };
    }

    const items: Array<{ pkg: string; key: string; title: string | null; text: string | null; postedAtEpochMs: number }> = [];
    const blocks = out.split(/NotificationRecord\(/).slice(1);
    for (const block of blocks) {
      const pkg = block.match(/pkg=(\S+)/)?.[1] ?? "";
      const title = block.match(/android\.title=String \(([^)]*)\)/)?.[1] ?? null;
      const text = block.match(/android\.text=String \(([^)]*)\)/)?.[1] ?? null;
      if (!pkg) continue;
      items.push({ pkg, key: pkg, title, text, postedAtEpochMs: 0 });
    }
    return { ok: true, items };
  }

  // ---- acting -----------------------------------------------------------

  private resolvePoint(req: { nodeId?: string; x?: number; y?: number }): { x: number; y: number } | null {
    if (req.nodeId) return this.nodeCenters.get(req.nodeId) ?? null;
    if (typeof req.x === "number" && typeof req.y === "number") return { x: req.x, y: req.y };
    return null;
  }

  private async tap(req: any): Promise<any> {
    const p = this.resolvePoint(req);
    if (!p) return { ok: false, error: "no node id (call ui_tree first) or x,y given" };
    await adbShellText(`input tap ${p.x} ${p.y}`);
    return { ok: true, foregroundChanged: false };
  }

  private async longPress(req: any): Promise<any> {
    const p = this.resolvePoint(req);
    if (!p) return { ok: false, error: "no node id or x,y given" };
    const ms = req.ms ?? 500;
    await adbShellText(`input swipe ${p.x} ${p.y} ${p.x} ${p.y} ${ms}`);
    return { ok: true, foregroundChanged: false };
  }

  private async swipe(req: any): Promise<any> {
    const ms = req.ms ?? 300;
    await adbShellText(`input swipe ${req.x1} ${req.y1} ${req.x2} ${req.y2} ${ms}`);
    return { ok: true, foregroundChanged: false };
  }

  private async scroll(req: any): Promise<any> {
    const b = req.nodeId ? this.nodeBounds.get(req.nodeId) : undefined;
    if (!b) return { ok: false, error: "unknown node id — call ui_tree first" };
    const cx = Math.round((b.l + b.r) / 2);
    const cy = Math.round((b.t + b.b) / 2);
    const w = b.r - b.l;
    const h = b.b - b.t;
    // A swipe opposite the reading direction of travel: scrolling DOWN reveals
    // content below, which is a swipe upward.
    let x1 = cx, y1 = cy, x2 = cx, y2 = cy;
    switch (req.dir) {
      case "DOWN": y1 = b.t + Math.round(h * 0.7); y2 = b.t + Math.round(h * 0.3); break;
      case "UP": y1 = b.t + Math.round(h * 0.3); y2 = b.t + Math.round(h * 0.7); break;
      case "RIGHT": x1 = b.l + Math.round(w * 0.7); x2 = b.l + Math.round(w * 0.3); break;
      case "LEFT": x1 = b.l + Math.round(w * 0.3); x2 = b.l + Math.round(w * 0.7); break;
      default: return { ok: false, error: `unknown direction: ${req.dir}` };
    }
    await adbShellText(`input swipe ${x1} ${y1} ${x2} ${y2} 300`);
    return { ok: true, foregroundChanged: false };
  }

  private async type(req: any): Promise<any> {
    if (req.nodeId) {
      const p = this.nodeCenters.get(req.nodeId);
      if (p) await adbShellText(`input tap ${p.x} ${p.y}`);
    }
    // `input text` takes spaces as %s and cannot express newlines or most
    // non-ASCII. Good enough for field entry; the app path handles the rest.
    const encoded = String(req.text ?? "").replace(/ /g, "%s");
    await adbShellText(`input text ${shellQuote(encoded)}`);
    return { ok: true, foregroundChanged: false };
  }

  private async key(key: string): Promise<any> {
    const map: Record<string, string> = {
      BACK: "4",
      HOME: "3",
      RECENTS: "187",
      ENTER: "66",
      DELETE: "67",
    };
    if (key === "NOTIFICATIONS") {
      await adbShellText("cmd statusbar expand-notifications");
      return { ok: true, foregroundChanged: false };
    }
    const code = map[key];
    if (!code) return { ok: false, error: `unknown key: ${key}` };
    await adbShellText(`input keyevent ${code}`);
    return { ok: true, foregroundChanged: false };
  }

  private async launch(pkg: string): Promise<any> {
    const r = await adbShell(
      `monkey -p ${shellQuote(pkg)} -c android.intent.category.LAUNCHER 1`,
      { timeoutMs: 10_000 },
    );
    if (r.code !== 0) {
      return { ok: false, error: `could not launch ${pkg}` };
    }
    return { ok: true, foregroundChanged: true };
  }

  private async shell(cmd: string): Promise<any> {
    const r = await adbShell(cmd, { timeoutMs: 30_000 });
    const text = (r.stdout.toString("utf8") + (r.stderr ? `\n${r.stderr}` : "")).trim();
    return { ok: r.code === 0, error: r.code === 0 ? null : text || `exited ${r.code}`, text };
  }
}

// ---- parsing helpers ------------------------------------------------------

interface Bounds {
  l: number;
  t: number;
  r: number;
  b: number;
}

interface AdbNode {
  id: string;
  cls: string;
  bounds: Bounds;
  depth: number;
  text?: string;
  desc?: string;
  clickable: boolean;
  editable: boolean;
  scrollable: boolean;
  checked?: boolean;
  enabled: boolean;
}

function decodeEntities(s: string): string {
  return s
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&apos;/g, "'");
}

function parseBounds(s: string | undefined): Bounds | null {
  const m = s?.match(/\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/);
  if (!m) return null;
  return { l: +m[1], t: +m[2], r: +m[3], b: +m[4] };
}

/**
 * Parses a uiautomator hierarchy dump into the flat node list the tools expect.
 *
 * Pure layout containers — nodes with no text, no content description, and no
 * interactive flag — are dropped and counted, matching what the on-device
 * Pruner does, so the tree the model reads is interaction, not scaffolding.
 */
function parseUiAutomator(xml: string): { pkg: string; nodes: AdbNode[]; pruned: number } {
  const nodes: AdbNode[] = [];
  let pruned = 0;
  let pkg = "";
  let depth = 0;
  let seq = 0;

  const tag = /<node\b([^>]*?)(\/?)>|<\/node>/g;
  let m: RegExpExecArray | null;

  while ((m = tag.exec(xml)) !== null) {
    if (m[0] === "</node>") {
      depth = Math.max(0, depth - 1);
      continue;
    }

    const attrsRaw = m[1];
    const selfClosing = m[2] === "/";
    const attrs: Record<string, string> = {};
    const attrRe = /([\w-]+)="([^"]*)"/g;
    let a: RegExpExecArray | null;
    while ((a = attrRe.exec(attrsRaw)) !== null) {
      attrs[a[1]] = decodeEntities(a[2]);
    }

    const bounds = parseBounds(attrs["bounds"]);
    const thisDepth = depth;
    if (!selfClosing) depth += 1;
    if (!bounds) continue;
    if (!pkg && attrs["package"]) pkg = attrs["package"];

    const text = attrs["text"] || undefined;
    const desc = attrs["content-desc"] || undefined;
    const clickable = attrs["clickable"] === "true";
    const longClickable = attrs["long-clickable"] === "true";
    const scrollable = attrs["scrollable"] === "true";
    const checkable = attrs["checkable"] === "true";
    const cls = (attrs["class"] || "").split(".").pop() || attrs["class"] || "View";
    const editable = /EditText/i.test(attrs["class"] || "");

    const interactive = clickable || longClickable || scrollable || checkable || editable;
    if (!interactive && !text && !desc) {
      pruned += 1;
      continue;
    }
    if (bounds.r - bounds.l <= 0 || bounds.b - bounds.t <= 0) {
      pruned += 1;
      continue;
    }

    const rid = attrs["resource-id"];
    const idBase = rid ? rid.split("/").pop() || rid : cls.toLowerCase();
    const id = `${idBase}#${seq++}`;

    nodes.push({
      id,
      cls,
      bounds,
      depth: thisDepth,
      text,
      desc,
      clickable,
      editable,
      scrollable,
      checked: checkable ? attrs["checked"] === "true" : undefined,
      enabled: attrs["enabled"] !== "false",
    });
  }

  return { pkg, nodes, pruned };
}

/** Reads width and height from a PNG's IHDR chunk. */
function pngSize(png: Buffer): { w: number; h: number } {
  // IHDR width/height are the two big-endian uint32s at offset 16.
  if (png.length >= 24 && png.toString("ascii", 12, 16) === "IHDR") {
    return { w: png.readUInt32BE(16), h: png.readUInt32BE(20) };
  }
  return { w: 0, h: 0 };
}
