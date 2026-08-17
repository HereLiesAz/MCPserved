import assert from "node:assert/strict";
import { once } from "node:events";
import { test } from "node:test";
import WebSocket from "ws";
import { startRelay } from "../src/server.js";

async function connect(port: number, room: string, role: "device" | "host"): Promise<WebSocket> {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/connect?room=${room}&role=${role}`);
  await once(ws, "open");
  return ws;
}

test("real WebSocket round trip: device -> relay -> host and back", async () => {
  const relay = await startRelay({ port: 0, idleTimeoutMs: 60_000 });
  try {
    const device = await connect(relay.port, "e2e-room", "device");
    const host = await connect(relay.port, "e2e-room", "host");

    const gotOnHost = once(host, "message");
    device.send("ping from device");
    const [msg] = await gotOnHost;
    assert.equal(msg.toString(), "ping from device");

    const gotOnDevice = once(device, "message");
    host.send("pong from host");
    const [reply] = await gotOnDevice;
    assert.equal(reply.toString(), "pong from host");

    device.close();
    host.close();
  } finally {
    await relay.close();
  }
});

test("a second connection claiming an occupied role is closed, not accepted", async () => {
  const relay = await startRelay({ port: 0, idleTimeoutMs: 60_000 });
  try {
    const first = await connect(relay.port, "conflict-room", "device");
    const second = new WebSocket(`ws://127.0.0.1:${relay.port}/connect?room=conflict-room&role=device`);

    const [code] = (await once(second, "close")) as [number];
    assert.equal(code, 4009);
    first.close();
  } finally {
    await relay.close();
  }
});

test("a malformed connect request is rejected at the TCP level", async () => {
  const relay = await startRelay({ port: 0, idleTimeoutMs: 60_000 });
  try {
    const bad = new WebSocket(`ws://127.0.0.1:${relay.port}/connect?room=only-a-room`); // no role
    const [err] = await once(bad, "error");
    assert.ok(err);
  } finally {
    await relay.close();
  }
});
