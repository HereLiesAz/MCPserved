/**
 * Cloudflare Worker fronting the MCPserved relay.
 *
 * The relay is a dumb pipe. It routes frames between a device and an MCP server
 * on the strength of a device id and nothing else. It holds no keys, performs no
 * decryption, and cannot be made to leak plaintext because it never possesses
 * any — every frame arriving here is already sealed under a key derived at
 * pairing between the two endpoints.
 *
 * That is not paranoia about the operator. It is that infrastructure you own and
 * forget you are running should be incapable of becoming a liability, and the
 * cheapest way to guarantee that is to make it structurally unable to read.
 *
 * One Durable Object per device. The DO is a natural mutex: exactly one device
 * connection and one controller connection may be live at a time, so two MCP
 * clients cannot both drive a phone and produce interleaved actions neither of
 * them ordered.
 */

export interface Env {
  DEVICE_ROOM: DurableObjectNamespace;
  /** Firebase server key, for the wake path. Never touches frame content. */
  FCM_SERVER_KEY: string;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    const deviceId =
      request.headers.get("X-Device-Id") ?? url.searchParams.get("device");

    if (!deviceId) {
      return new Response("missing device id", { status: 400 });
    }

    // Device id is opaque to the relay and used only for routing. It is a random
    // UUID minted at pairing, not a hardware identifier, so a leaked routing key
    // reveals nothing about the handset and is revoked by re-pairing.
    const id = env.DEVICE_ROOM.idFromName(deviceId);
    const room = env.DEVICE_ROOM.get(id);

    return room.fetch(request);
  },
};

export { DeviceRoom } from "./DeviceRoom";
