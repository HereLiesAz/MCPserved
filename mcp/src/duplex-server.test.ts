import { test } from "node:test";
import assert from "node:assert/strict";
import { DuplexHub, startDuplexServer } from "./duplex-server.js";

const A = "a".repeat(43);
const B = "b".repeat(43);

test("independent peers send simultaneous requests, reply across them, and acknowledge separately", () => {
  const hub = new DuplexHub(() => 1700000000000);
  const a = hub.send("a", "request", "A asks");
  const b = hub.send("b", "request", "B asks");
  assert.equal(hub.read("b")[0].body, "A asks");
  assert.equal(hub.read("a")[0].body, "B asks");
  hub.send("b", "response", "B answers", a.id);
  hub.send("a", "response", "A answers", b.id);
  assert.deepEqual(hub.read("a").map(x => x.kind), ["request", "response"]);
  assert.deepEqual(hub.read("b").map(x => x.kind), ["request", "response"]);
  assert.equal(hub.ack("a", 1), 1);
  assert.equal(hub.read("a")[0].body, "B answers");
  assert.throws(() => hub.read("a", 0), /cursor/);
  assert.equal(hub.read("b").length, 2);
});

test("request ownership, response replay, cancellation and bounded queues are enforced", () => {
  const hub = new DuplexHub();
  const request = hub.send("a", "request", "do something");
  assert.throws(() => hub.send("a", "response", "fake", request.id), /other peer/);
  assert.throws(() => hub.send("b", "cancel", "fake", request.id), /this peer/);
  hub.send("a", "cancel", "never mind", request.id);
  assert.throws(() => hub.send("b", "response", "late", request.id), /outstanding/);
  assert.throws(() => hub.send("a", "message", "hello", "not-a-reply"), /replyTo/);
  for (let i = 0; i < 126; i++) hub.send("a", "message", `${i}`);
  assert.equal(hub.status("b").queued, 128);
  assert.throws(() => hub.send("a", "message", "overflow"), /inbox full/);
  assert.equal(hub.ack("b", 128), 128);
  assert.equal(hub.status("b").queued, 0);
  assert.throws(() => hub.ack("b", 129), /range/);
});

async function rpc(port: number, peer: "a" | "b", token: string, method: string, params: object = {}) {
  const response = await fetch(`http://127.0.0.1:${port}/mcp/${peer}`, {
    method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 7, method, params }),
  });
  const content = await response.text();
  return { status: response.status, body: content ? JSON.parse(content) as any : null };
}
function toolBody(response: any): any {
  return JSON.parse(response.body.result.content[0].text);
}

test("HTTP endpoints are agent-neutral MCP servers with independent credentials", async () => {
  const server = await startDuplexServer({ tokenA: A, tokenB: B, port: 0 });
  try {
    assert.equal((await rpc(server.port, "a", B, "tools/list")).status, 401);
    assert.equal((await rpc(server.port, "a", A, "initialize")).body.result.serverInfo.name, "mcpserved-duplex");
    const tools = await rpc(server.port, "b", B, "tools/list");
    assert.deepEqual(tools.body.result.tools.map((x: any) => x.name), ["duplex_send", "duplex_receive", "duplex_ack", "duplex_status"]);
    const sentA = toolBody(await rpc(server.port, "a", A, "tools/call", { name: "duplex_send", arguments: { kind: "message", body: "hello B" } }));
    const sentB = toolBody(await rpc(server.port, "b", B, "tools/call", { name: "duplex_send", arguments: { kind: "message", body: "hello A" } }));
    const inboxA = toolBody(await rpc(server.port, "a", A, "tools/call", { name: "duplex_receive", arguments: {} }));
    const inboxB = toolBody(await rpc(server.port, "b", B, "tools/call", { name: "duplex_receive", arguments: {} }));
    assert.equal(inboxA[0].id, sentB.id);
    assert.equal(inboxB[0].id, sentA.id);
    assert.equal(inboxA[0].body, "hello A");
    assert.equal(inboxB[0].body, "hello B");
    assert.equal(toolBody(await rpc(server.port, "a", A, "tools/call", { name: "duplex_ack", arguments: { through: 1 } })).acknowledged, 1);
    assert.equal(toolBody(await rpc(server.port, "a", A, "tools/call", { name: "duplex_receive", arguments: {} })).length, 0);
  } finally { await server.close(); }
});

test("SSE sends server-initiated messages without blocking concurrent reverse sends", async () => {
  const server = await startDuplexServer({ tokenA: A, tokenB: B, port: 0 });
  const controller = new AbortController();
  try {
    const stream = await fetch(`http://127.0.0.1:${server.port}/mcp/b`, {
      headers: { authorization: `Bearer ${B}`, accept: "text/event-stream" }, signal: controller.signal,
    });
    assert.equal(stream.status, 200);
    assert.equal(toolBody(await rpc(server.port, "a", A, "tools/call", { name: "duplex_status", arguments: {} })).peerStreamOnline, true);
    const reader = stream.body!.getReader();
    await rpc(server.port, "a", A, "tools/call", { name: "duplex_send", arguments: { kind: "event", body: "ready" } });
    await rpc(server.port, "b", B, "tools/call", { name: "duplex_send", arguments: { kind: "message", body: "back" } });
    let received = "";
    for (let i=0; i<8 && !received.includes("ready"); i++) received += new TextDecoder().decode((await reader.read()).value);
    assert.match(received, /notifications\/message/);
    assert.match(received, /ready/);
    assert.equal(toolBody(await rpc(server.port, "a", A, "tools/call", { name: "duplex_receive", arguments: {} }))[0].body, "back");
  } finally { controller.abort(); await server.close(); }
});

test("unauthorized and browser-origin requests never reach the tool surface", async () => {
  const server = await startDuplexServer({ tokenA: A, tokenB: B, port: 0 });
  try {
    const uri = `http://127.0.0.1:${server.port}/mcp/a`;
    const wrong = await fetch(uri, { method: "POST", headers: { authorization: `Bearer ${B}` }, body: "{}" });
    assert.equal(wrong.status, 401);
    const web = await fetch(uri, { method: "POST", headers: { authorization: `Bearer ${A}`, origin: "https://evil.example" }, body: "{}" });
    assert.equal(web.status, 403);
    const broken = await fetch(uri, { method: "POST", headers: { authorization: `Bearer ${A}` }, body: "malformed" });
    assert.equal(broken.status, 400);
  } finally { await server.close(); }
});
