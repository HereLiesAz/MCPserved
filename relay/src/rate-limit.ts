/**
 * A minimal sliding-window rate limiter, keyed by whatever the caller wants
 * to throttle (here, a remote IP). Exists so a single source can't burn
 * relay capacity by opening connection attempts in a tight loop — the room
 * cap in `rooms.ts` bounds total *rooms*, this bounds the *rate* of new
 * attempts from one place, which the room cap alone doesn't touch.
 */
export class RateLimiter {
  private readonly hits = new Map<string, number[]>();

  constructor(
    private readonly maxPerWindow: number,
    private readonly windowMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  /** Records an attempt from `key`; returns false once the window is full. */
  allow(key: string): boolean {
    const cutoff = this.now() - this.windowMs;
    const recent = (this.hits.get(key) ?? []).filter((t) => t > cutoff);
    if (recent.length >= this.maxPerWindow) {
      this.hits.set(key, recent);
      return false;
    }
    recent.push(this.now());
    this.hits.set(key, recent);
    return true;
  }

  /** Drops keys with no attempts inside the window — bounds memory on a long-running process. */
  sweep(): void {
    const cutoff = this.now() - this.windowMs;
    for (const [key, timestamps] of this.hits) {
      const recent = timestamps.filter((t) => t > cutoff);
      if (recent.length === 0) this.hits.delete(key);
      else this.hits.set(key, recent);
    }
  }
}
