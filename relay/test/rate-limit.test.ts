import assert from "node:assert/strict";
import { test } from "node:test";
import { RateLimiter } from "../src/rate-limit.js";

test("allows up to the configured count within the window", () => {
  const limiter = new RateLimiter(3, 60_000, () => 0);
  assert.equal(limiter.allow("1.2.3.4"), true);
  assert.equal(limiter.allow("1.2.3.4"), true);
  assert.equal(limiter.allow("1.2.3.4"), true);
  assert.equal(limiter.allow("1.2.3.4"), false);
});

test("tracks each key independently", () => {
  const limiter = new RateLimiter(1, 60_000, () => 0);
  assert.equal(limiter.allow("a"), true);
  assert.equal(limiter.allow("b"), true);
  assert.equal(limiter.allow("a"), false);
  assert.equal(limiter.allow("b"), false);
});

test("attempts age out of the window and free up capacity", () => {
  let now = 0;
  const limiter = new RateLimiter(2, 1_000, () => now);
  assert.equal(limiter.allow("x"), true);
  assert.equal(limiter.allow("x"), true);
  assert.equal(limiter.allow("x"), false);

  now = 1_500; // past the 1s window
  assert.equal(limiter.allow("x"), true);
});

test("sweep drops keys with no recent attempts, keeps active ones", () => {
  let now = 0;
  const limiter = new RateLimiter(5, 1_000, () => now);
  limiter.allow("stale");

  now = 5_000;
  limiter.allow("fresh");
  limiter.sweep();

  // "stale" aged out and was swept; it should behave as a brand-new key —
  // i.e. still allowed up to the full quota again, not still counted.
  for (let i = 0; i < 5; i++) assert.equal(limiter.allow("stale"), true);
  assert.equal(limiter.allow("stale"), false);
});
