/**
 * Pure in-memory room pairing. No decryption, no payload parsing, no
 * persistence — the relay only ever needs to know which two sockets belong
 * together and to shovel bytes between them.
 *
 * `PeerSocket` is a thin abstraction over a live connection so this module
 * has zero dependency on `ws` (or any transport) and is unit-testable with
 * fakes, driven by an injectable clock so idle-eviction tests don't need to
 * wait in real time.
 */

export type Role = "device" | "host";

export interface PeerSocket {
  send(data: string | Buffer): void;
  close(code?: number, reason?: string): void;
  onMessage(cb: (data: string | Buffer) => void): void;
  onClose(cb: () => void): void;
}

interface Room {
  device?: PeerSocket;
  host?: PeerSocket;
  lastActive: number;
}

/** A role slot was already occupied by a live peer; the caller should reject, not displace. */
export const JOIN_REJECTED_OCCUPIED = "occupied";

/** The relay is already holding as many rooms as it's configured to allow. */
export const JOIN_REJECTED_CAPACITY = "capacity";

export type JoinResult =
  | { ok: true }
  | { ok: false; reason: typeof JOIN_REJECTED_OCCUPIED | typeof JOIN_REJECTED_CAPACITY };

/** Default cap on concurrent rooms — bounds memory from spam room creation on a public relay. */
export const DEFAULT_MAX_ROOMS = 2_000;

export class RoomRegistry {
  private readonly rooms = new Map<string, Room>();

  constructor(
    private readonly now: () => number = Date.now,
    private readonly maxRooms: number = DEFAULT_MAX_ROOMS,
  ) {}

  /**
   * Attempts to occupy `role` in `roomToken` with `socket`.
   *
   * Rejects rather than displacing when the slot is already live — a party
   * that merely knows (or guesses) a room token must not be able to bump the
   * legitimate peer off it. The rejected caller should close its own socket;
   * this method does not do that for it, since the caller owns delivering the
   * close reason over its own transport (e.g. a WebSocket close code).
   *
   * Also rejects creating a *new* room once [maxRooms] is already in use —
   * joining an *existing* room is never blocked by the cap, since that peer
   * is completing a pairing already in progress, not adding load.
   */
  join(roomToken: string, role: Role, socket: PeerSocket): JoinResult {
    let room = this.rooms.get(roomToken);
    if (!room) {
      if (this.rooms.size >= this.maxRooms) {
        return { ok: false, reason: JOIN_REJECTED_CAPACITY };
      }
      room = { lastActive: this.now() };
      this.rooms.set(roomToken, room);
    }
    if (room[role] !== undefined) {
      return { ok: false, reason: JOIN_REJECTED_OCCUPIED };
    }

    room[role] = socket;
    room.lastActive = this.now();
    const activeRoom = room;

    socket.onMessage((data) => {
      activeRoom.lastActive = this.now();
      const other = role === "device" ? activeRoom.host : activeRoom.device;
      other?.send(data);
    });

    socket.onClose(() => {
      if (activeRoom[role] === socket) activeRoom[role] = undefined;
      activeRoom.lastActive = this.now();
      this.evictIfEmpty(roomToken, activeRoom);
    });

    return { ok: true };
  }

  private evictIfEmpty(roomToken: string, room: Room): void {
    if (!room.device && !room.host) this.rooms.delete(roomToken);
  }

  /** Closes and forgets any room untouched for longer than `maxIdleMs`. */
  sweep(maxIdleMs: number): void {
    const cutoff = this.now() - maxIdleMs;
    for (const [token, room] of this.rooms) {
      if (room.lastActive < cutoff) {
        room.device?.close(1001, "idle timeout");
        room.host?.close(1001, "idle timeout");
        this.rooms.delete(token);
      }
    }
  }

  /** Ops/test visibility only — never used for pairing decisions. */
  roomCount(): number {
    return this.rooms.size;
  }
}
