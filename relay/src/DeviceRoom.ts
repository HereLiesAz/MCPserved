/**
 * One device's routing room.
 *
 * Holds at most one device socket and one controller socket, forwarding opaque
 * frames between them. Uses the WebSocket Hibernation API so an idle room costs
 * nothing: the object evicts from memory while its sockets stay open, and wakes
 * on the next frame. A phone is quiet for twenty-three hours of any given day,
 * and paying for a resident process to hold a silent socket is the reason
 * self-hosted relays get switched off and never switched back on.
 */

interface Env {
  FCM_SERVER_KEY: string;
}

type Role = "device" | "controller";

export class DeviceRoom {
  private state: DurableObjectState;
  private env: Env;

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    // Wake requests are plain HTTP, not WebSocket. The MCP server asks the relay
    // to nudge a sleeping device; the relay pushes a data-only FCM message and
    // the device redials. No frame content is involved on this path.
    if (url.pathname.endsWith("/wake")) {
      return this.wake(request);
    }

    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }

    const role: Role = url.pathname.endsWith("/controller")
      ? "controller"
      : "device";

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    // Displace any existing socket in this role. A stale connection the network
    // dropped without closing would otherwise hold the slot and silently swallow
    // every frame routed to it.
    for (const ws of this.state.getWebSockets(role)) {
      try {
        ws.close(1000, "displaced");
      } catch {
        // Already gone. Nothing to do, and nothing worth reporting.
      }
    }

    this.state.acceptWebSocket(server, [role]);

    return new Response(null, { status: 101, webSocket: client });
  }

  /**
   * Forwards a frame to the opposite role.
   *
   * The payload is passed through untouched and unparsed. Inspecting it would
   * require key material the relay deliberately does not have, and reformatting
   * it would break the AEAD tag computed over the exact bytes.
   */
  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    const tags = this.state.getTags(ws);
    const from: Role = tags.includes("controller") ? "controller" : "device";
    const to: Role = from === "controller" ? "device" : "controller";

    const targets = this.state.getWebSockets(to);
    if (targets.length === 0) {
      // The peer is absent. Frames are not queued: a request delivered minutes
      // late would act on a screen that has long since changed, which is worse
      // than a request that visibly failed.
      if (from === "controller") {
        await this.pushWake();
      }
      return;
    }

    for (const target of targets) {
      try {
        target.send(message);
      } catch {
        // Send failed on a socket the runtime still considers open. The next
        // frame will find it closed and displace it.
      }
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string) {
    try {
      ws.close(code, reason);
    } catch {
      // Closing an already-closed socket is not an error worth surfacing.
    }
  }

  async webSocketError() {
    // Errors here are network noise. The client redials on its own backoff.
  }

  /** Stores the device's current FCM token. Set by the device on registration. */
  private async wake(request: Request): Promise<Response> {
    if (request.method === "PUT") {
      const token = await request.text();
      await this.state.storage.put("fcm", token);
      return new Response("ok");
    }
    const ok = await this.pushWake();
    return new Response(ok ? "woken" : "no token", { status: ok ? 200 : 404 });
  }

  /**
   * Sends a high-priority data message so the device redials.
   *
   * Data-only, never a notification: a notification would surface in the shade
   * and hand the wake path a user-visible presence it has no business having.
   */
  private async pushWake(): Promise<boolean> {
    const token = await this.state.storage.get<string>("fcm");
    if (!token) return false;

    const res = await fetch("https://fcm.googleapis.com/fcm/send", {
      method: "POST",
      headers: {
        Authorization: `key=${this.env.FCM_SERVER_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        to: token,
        priority: "high",
        data: { type: "wake" },
      }),
    });

    return res.ok;
  }
}
