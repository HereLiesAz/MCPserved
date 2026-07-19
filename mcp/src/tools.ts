import type { RelayLink } from "./relay.js";

/**
 * Tool surface exposed to the model.
 *
 * The list is built after the device reports its capabilities, so an operation
 * the hardware cannot perform is *absent* rather than present-and-failing. A
 * disabled tool invites the model to try it, read the refusal, and look for a
 * way around it; a tool that was never listed is simply not part of the world.
 *
 * Results are returned as content, never as protocol errors. A denied grant or a
 * lost accessibility binding is information the model needs in order to change
 * course, and an exception thrown at the host discards it.
 */

/** Capability report from the device, used to shape the tool list. */
export interface Capabilities {
  caps: string[];
  root: boolean;
  shizuku: boolean;
  a11y: boolean;
}

export interface ToolDef {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  handler: (args: any, link: RelayLink) => Promise<string>;
}

const obj = (
  properties: Record<string, unknown>,
  required: string[] = [],
): Record<string, unknown> => ({
  type: "object",
  properties,
  required,
  additionalProperties: false,
});

/**
 * Renders a node tree as indented text.
 *
 * JSON would cost roughly three times the tokens for the same information, and
 * the model reads structure from indentation more readily than from punctuation.
 * Bounds are emitted only as a centre point, since every interaction addresses
 * nodes by id and the exact rectangle is never the thing being acted upon.
 */
function renderTree(res: any): string {
  if (!res.ok) return `error: ${res.error}`;

  const head = [
    `package: ${res.pkg}`,
    res.activity ? `activity: ${res.activity}` : null,
    `${res.nodes.length} nodes (${res.pruned} pruned)`,
  ]
    .filter(Boolean)
    .join("\n");

  if (res.nodes.length === 0) {
    return (
      `${head}\n\n(no addressable nodes — the screen is probably canvas-drawn; ` +
      `use screenshot instead)`
    );
  }

  const body = res.nodes
    .map((n: any) => {
      const indent = "  ".repeat(Math.min(n.depth, 12));
      const label = n.text ?? n.desc ?? "";
      const flags = [
        n.clickable ? "tap" : null,
        n.editable ? "edit" : null,
        n.scrollable ? "scroll" : null,
        n.checked === true ? "checked" : null,
        n.checked === false ? "unchecked" : null,
        n.enabled === false ? "disabled" : null,
      ]
        .filter(Boolean)
        .join(",");

      const cx = Math.round((n.bounds.l + n.bounds.r) / 2);
      const cy = Math.round((n.bounds.t + n.bounds.b) / 2);

      return `${indent}${n.id} ${n.cls}${label ? ` "${label}"` : ""}` +
        `${flags ? ` [${flags}]` : ""} @${cx},${cy}`;
    })
    .join("\n");

  return `${head}\n\n${body}${changedSuffix(res)}`;
}

/**
 * Appends the foreground-change warning.
 *
 * This is the single most load-bearing line in any response. It means the window
 * moved between the permission check and the action, so every node id the model
 * is holding now refers to a layout that is gone. Without it, the next tap lands
 * on whatever happens to occupy those coordinates.
 */
function changedSuffix(res: any): string {
  if (!res.foregroundChanged) return "";
  return (
    `\n\nWARNING: the foreground app changed during this action. ` +
    `All node ids are stale — call ui_tree before doing anything else.`
  );
}

function ack(res: any, success: string): string {
  if (!res.ok) return `error: ${res.error}`;
  return `${success}${changedSuffix(res)}`;
}

const ALWAYS: ToolDef[] = [
  {
    name: "capabilities",
    description:
      "Report which control backends the device has available. Call this first " +
      "if anything behaves unexpectedly — a missing accessibility binding " +
      "explains most silent failures.",
    inputSchema: obj({}),
    handler: async (_args, link) => {
      const r = await link.send({ op: "capabilities" });
      if (!r.ok) return `error: ${r.error}`;
      return [
        `accessibility: ${r.a11y ? "connected" : "NOT CONNECTED"}`,
        `root: ${r.root}`,
        `shizuku: ${r.shizuku}`,
        `capabilities: ${r.caps.join(", ")}`,
      ].join("\n");
    },
  },
  {
    name: "session_begin",
    description:
      "Open a control session. Required before any other operation. The device " +
      "holds its screen awake for the duration, so keep the TTL short and let " +
      "it lapse rather than holding it open across idle periods.",
    inputSchema: obj({
      ttlSec: {
        type: "integer",
        description: "Session lifetime in seconds (30-1800, default 300).",
        minimum: 30,
        maximum: 1800,
      },
    }),
    handler: async (args, link) => {
      const r = await link.send({ op: "session_begin", ttlSec: args.ttlSec ?? 300 });
      if (!r.ok) return `error: ${r.error}`;
      const secs = Math.round((r.expiresAtEpochMs - Date.now()) / 1000);
      return `session ${r.sessionId} open, expires in ${secs}s`;
    },
  },
  {
    name: "session_end",
    description:
      "Close the session and release the device's screen. Call this when the " +
      "task is done rather than leaving it to expire.",
    inputSchema: obj({}),
    handler: async (_args, link) =>
      ack(await link.send({ op: "session_end" }), "session closed"),
  },
  {
    name: "grants_list",
    description:
      "List which packages the user has authorized and with what scopes. " +
      "Nothing outside this list can be observed or touched.",
    inputSchema: obj({}),
    handler: async (_args, link) => {
      const r = await link.send({ op: "grants_list" });
      if (!r.ok) return `error: ${r.error}`;
      if (r.grants.length === 0) {
        return "no grants — the user has not authorized any package";
      }
      return r.grants
        .map((g: any) => {
          const exp = g.expiresAtEpochMs
            ? ` (expires in ${Math.round((g.expiresAtEpochMs - Date.now()) / 1000)}s)`
            : "";
          return `${g.pkg}: ${g.scopes.join(", ")}${exp}`;
        })
        .join("\n");
    },
  },
  {
    name: "apps_list",
    description:
      "List installed applications. Defaults to authorized packages only.",
    inputSchema: obj({
      grantedOnly: {
        type: "boolean",
        description:
          "When false, lists every launchable app. Prefer the default — a full " +
          "inventory is disclosure the task rarely needs.",
      },
    }),
    handler: async (args, link) => {
      const r = await link.send({
        op: "apps_list",
        grantedOnly: args.grantedOnly ?? true,
      });
      if (!r.ok) return `error: ${r.error}`;
      return r.apps
        .map((a: any) => `${a.pkg} — ${a.label}${a.granted ? " [granted]" : ""}`)
        .join("\n");
    },
  },
  {
    name: "ui_tree",
    description:
      "Read the current screen as a node tree. This is the primary way to see " +
      "the device — prefer it over screenshot, which costs far more and conveys " +
      "less. Node ids survive scrolling but not layout changes.",
    inputSchema: obj({
      maxDepth: {
        type: "integer",
        description: "Maximum tree depth to walk (default 40).",
        minimum: 1,
        maximum: 100,
      },
    }),
    handler: async (args, link) =>
      renderTree(await link.send({ op: "ui_tree", maxDepth: args.maxDepth ?? 40 })),
  },
  {
    name: "screenshot",
    description:
      "Capture the screen as an image. Use only when ui_tree returns no " +
      "addressable nodes — games, canvas-drawn UI, and some WebViews.",
    inputSchema: obj({
      maxPx: {
        type: "integer",
        description: "Longest edge in pixels (default 768).",
        minimum: 256,
        maximum: 2048,
      },
    }),
    handler: async (args, link) => {
      const r = await link.send({ op: "screenshot", maxPx: args.maxPx ?? 768 });
      if (!r.ok) return `error: ${r.error}`;
      return `[image ${r.w}x${r.h} ${r.mime}]\n${r.b64}${changedSuffix(r)}`;
    },
  },
  {
    name: "notifications",
    description:
      "Read the notification shade, filtered to authorized packages.",
    inputSchema: obj({}),
    handler: async (_args, link) => {
      const r = await link.send({ op: "notifications" });
      if (!r.ok) return `error: ${r.error}`;
      if (r.items.length === 0) return "no notifications from authorized packages";
      return r.items
        .map((n: any) => `${n.pkg}: ${n.title ?? ""} — ${n.text ?? ""}`)
        .join("\n");
    },
  },
  {
    name: "tap",
    description:
      "Tap a node by id, or a raw coordinate. Prefer the node id; coordinates " +
      "computed from an older tree will miss after any scroll.",
    inputSchema: obj({
      nodeId: { type: "string", description: "Node id from ui_tree." },
      x: { type: "integer" },
      y: { type: "integer" },
    }),
    handler: async (args, link) =>
      ack(await link.send({ op: "tap", ...args }), "tapped"),
  },
  {
    name: "long_press",
    description: "Press and hold a node or coordinate.",
    inputSchema: obj({
      nodeId: { type: "string" },
      x: { type: "integer" },
      y: { type: "integer" },
      ms: { type: "integer", description: "Hold duration (default 500)." },
    }),
    handler: async (args, link) =>
      ack(await link.send({ op: "long_press", ...args }), "held"),
  },
  {
    name: "swipe",
    description:
      "Swipe between two coordinates. For scrolling a list, prefer the scroll " +
      "tool — it uses the view's own scroll action and respects nesting.",
    inputSchema: obj(
      {
        x1: { type: "integer" },
        y1: { type: "integer" },
        x2: { type: "integer" },
        y2: { type: "integer" },
        ms: { type: "integer", description: "Duration (default 300)." },
      },
      ["x1", "y1", "x2", "y2"],
    ),
    handler: async (args, link) =>
      ack(await link.send({ op: "swipe", ...args }), "swiped"),
  },
  {
    name: "scroll",
    description: "Scroll a scrollable node in a direction.",
    inputSchema: obj(
      {
        nodeId: { type: "string", description: "A node marked [scroll]." },
        dir: { type: "string", enum: ["UP", "DOWN", "LEFT", "RIGHT"] },
      },
      ["nodeId", "dir"],
    ),
    handler: async (args, link) =>
      ack(await link.send({ op: "scroll", ...args }), "scrolled"),
  },
  {
    name: "type",
    description:
      "Type text into an editable field. Targets the focused field when no " +
      "node id is given.",
    inputSchema: obj(
      {
        text: { type: "string" },
        nodeId: { type: "string", description: "A node marked [edit]." },
      },
      ["text"],
    ),
    handler: async (args, link) =>
      ack(await link.send({ op: "type", ...args }), "typed"),
  },
  {
    name: "key",
    description: "Press a global key.",
    inputSchema: obj(
      {
        key: {
          type: "string",
          enum: ["BACK", "HOME", "RECENTS", "ENTER", "DELETE", "NOTIFICATIONS"],
        },
      },
      ["key"],
    ),
    handler: async (args, link) =>
      ack(await link.send({ op: "key", key: args.key }), `pressed ${args.key}`),
  },
  {
    name: "launch",
    description:
      "Bring an application to the foreground. Requires a LAUNCH grant for the " +
      "target, not for the current app.",
    inputSchema: obj({ pkg: { type: "string" } }, ["pkg"]),
    handler: async (args, link) =>
      ack(await link.send({ op: "launch", pkg: args.pkg }), `launched ${args.pkg}`),
  },
  {
    name: "clipboard_get",
    description:
      "Read the clipboard. Requires root or Shizuku — Android forbids " +
      "background clipboard reads outright.",
    inputSchema: obj({}),
    handler: async (_args, link) => {
      const r = await link.send({ op: "clipboard_get" });
      if (!r.ok) return `error: ${r.error}`;
      return `${r.text}${changedSuffix(r)}`;
    },
  },
  {
    name: "clipboard_set",
    description: "Write the clipboard.",
    inputSchema: obj({ text: { type: "string" } }, ["text"]),
    handler: async (args, link) =>
      ack(await link.send({ op: "clipboard_set", text: args.text }), "clipboard set"),
  },
];

/**
 * Shell, listed only when a privileged backend exists.
 *
 * Separated from [ALWAYS] because its absence is the point. On an unprivileged
 * device this tool never appears in the manifest at all, and no amount of
 * reasoning about it will produce a call.
 */
const PRIVILEGED: ToolDef[] = [
  {
    name: "shell",
    description:
      "Run a shell command. Reaches every package at once, so it is logged and " +
      "bracketed like any other action. Requires a SHELL grant.",
    inputSchema: obj({ cmd: { type: "string" } }, ["cmd"]),
    handler: async (args, link) => {
      const r = await link.send({ op: "shell", cmd: args.cmd });
      if (!r.ok) return `error: ${r.error}`;
      return `${r.text}${changedSuffix(r)}`;
    },
  },
];

/** Builds the tool list appropriate to a device's reported capabilities. */
export function buildTools(caps: Capabilities): ToolDef[] {
  const privileged = caps.root || caps.shizuku;
  return privileged ? [...ALWAYS, ...PRIVILEGED] : ALWAYS;
}
