import assert from "node:assert/strict";
import { test } from "node:test";
import { JOIN_REJECTED_OCCUPIED, RoomRegistry, type PeerSocket } from "../src/rooms.js";

/** A fake peer with no real transport underneath, for pure logic tests. */
function fakeSocket() {
  const received: (string | Buffer)[] = [];
  const closeCalls: { code?: number; reason?: string }[] = [];
  let messageCb: ((data: string | Buffer) => void) | undefined;
  let closeCb: (() => void) | undefined;

  const socket: PeerSocket = {
    send: (data) => received.push(data),
    close: (code, reason) => {
      closeCalls.push({ code, reason });
      closeCb?.();
    },
    onMessage: (cb) => {
      messageCb = cb;
    },
    onClose: (cb) => {
      closeCb = cb;
    },
  };

  return {
    socket,
    received,
    closeCalls,
    /** Simulates the remote peer sending `data` — drives the registered onMessage callback. */
    emit: (data: string | Buffer) => messageCb?.(data),
    /** Simulates the underlying transport closing (not a call to close()). */
    disconnect: () => closeCb?.(),
  };
}

test("forwards a message from device to host", () => {
  const registry = new RoomRegistry();
  const device = fakeSocket();
  const host = fakeSocket();

  assert.equal(registry.join("room1", "device", device.socket).ok, true);
  assert.equal(registry.join("room1", "host", host.socket).ok, true);

  device.emit("hello from device");

  assert.deepEqual(host.received, ["hello from device"]);
  assert.deepEqual(device.received, []);
});

test("forwards a message from host to device", () => {
  const registry = new RoomRegistry();
  const device = fakeSocket();
  const host = fakeSocket();
  registry.join("room1", "device", device.socket);
  registry.join("room1", "host", host.socket);

  host.emit("hello from host");

  assert.deepEqual(device.received, ["hello from host"]);
});

test("a message before both roles have joined is dropped, not queued", () => {
  const registry = new RoomRegistry();
  const device = fakeSocket();
  registry.join("room1", "device", device.socket);

  device.emit("nobody is listening yet");

  const host = fakeSocket();
  registry.join("room1", "host", host.socket);
  assert.deepEqual(host.received, []);
});

test("rejects a second claimant of an already-occupied role rather than displacing it", () => {
  const registry = new RoomRegistry();
  const first = fakeSocket();
  const second = fakeSocket();

  assert.equal(registry.join("room1", "device", first.socket).ok, true);
  const result = registry.join("room1", "device", second.socket);
  assert.equal(result.ok, false);
  assert.equal((result as { reason: string }).reason, JOIN_REJECTED_OCCUPIED);

  // The first connection is undisturbed: it still receives forwarded traffic.
  const host = fakeSocket();
  registry.join("room1", "host", host.socket);
  host.emit("still going to the original device");
  assert.deepEqual(first.received, ["still going to the original device"]);
  assert.deepEqual(second.received, []);
});

test("a role frees on disconnect and can be reclaimed by a reconnect", () => {
  const registry = new RoomRegistry();
  const device = fakeSocket();
  registry.join("room1", "device", device.socket);

  device.disconnect();

  const reconnected = fakeSocket();
  const result = registry.join("room1", "device", reconnected.socket);
  assert.equal(result.ok, true);
});

test("an empty room (both roles gone) is forgotten", () => {
  const registry = new RoomRegistry();
  const device = fakeSocket();
  const host = fakeSocket();
  registry.join("room1", "device", device.socket);
  registry.join("room1", "host", host.socket);

  device.disconnect();
  host.disconnect();

  assert.equal(registry.roomCount(), 0);
});

test("sweep evicts and closes rooms idle past the timeout, leaves fresh rooms alone", () => {
  let now = 0;
  const registry = new RoomRegistry(() => now);

  const idleDevice = fakeSocket();
  const idleHost = fakeSocket();
  registry.join("idle-room", "device", idleDevice.socket);
  registry.join("idle-room", "host", idleHost.socket);

  now = 8_000;
  const freshDevice = fakeSocket();
  registry.join("fresh-room", "device", freshDevice.socket);

  now = 10_000;
  registry.sweep(5_000);

  assert.equal(idleDevice.closeCalls.length, 1);
  assert.equal(idleHost.closeCalls.length, 1);
  assert.equal(registry.roomCount(), 1);

  // The fresh room's device slot survived the sweep and is still occupied —
  // rejoining it should be rejected, not silently accepted.
  const impersonator = fakeSocket();
  assert.equal(registry.join("fresh-room", "device", impersonator.socket).ok, false);
});

test("traffic resets a room's idle clock", () => {
  let now = 0;
  const registry = new RoomRegistry(() => now);
  const device = fakeSocket();
  const host = fakeSocket();
  registry.join("room1", "device", device.socket);
  registry.join("room1", "host", host.socket);

  now = 4_000;
  device.emit("keepalive");

  now = 8_000;
  registry.sweep(5_000); // 8_000 - 4_000 = 4_000 < 5_000, should survive

  assert.equal(registry.roomCount(), 1);
});
