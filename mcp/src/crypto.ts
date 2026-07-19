import {
  createCipheriv,
  createDecipheriv,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
  hkdfSync,
  KeyObject,
} from "node:crypto";

/**
 * Server-side mirror of the device's `Pairing` and `FrameCodec`.
 *
 * Every constant here — the HKDF info strings, the nonce layout, the tag length,
 * the AAD — must match `crypto/Pairing.kt` and `crypto/Frame.kt` exactly. A
 * mismatch does not degrade gracefully; it produces frames that fail
 * authentication on arrival with no indication of which side is wrong. They are
 * duplicated rather than shared because the two runtimes cannot import from each
 * other, which makes this file the one place where a silent divergence can hide.
 */

const INFO_D2S = "mcpserved d2s v1";
const INFO_S2D = "mcpserved s2d v1";
const NONCE_BYTES = 12;
const TAG_BYTES = 16;

/** Raw 32-byte X25519 keys, as they appear in the QR payload. */
export interface RawKeyPair {
  privateKey: Buffer;
  publicKey: Buffer;
}

/** Directional 256-bit ChaCha20-Poly1305 keys. */
export interface FrameKeys {
  deviceToServer: Buffer;
  serverToDevice: Buffer;
}

/** Generates an X25519 keypair and returns both halves in raw form. */
export function generateKeyPair(): RawKeyPair {
  const { privateKey, publicKey } = generateKeyPairSync("x25519");
  return {
    privateKey: rawPrivate(privateKey),
    publicKey: rawPublic(publicKey),
  };
}

/**
 * Extracts the 32 raw bytes from a DER-encoded X25519 private key.
 *
 * Node offers no direct raw export, and the PKCS#8 wrapper is a fixed 16-byte
 * prefix for this curve. Slicing is safe precisely because the length is
 * invariant for X25519 — it would not be for a curve with variable parameters.
 */
function rawPrivate(key: KeyObject): Buffer {
  const der = key.export({ type: "pkcs8", format: "der" });
  return Buffer.from(der.subarray(der.length - 32));
}

/** Extracts the 32 raw bytes from a DER-encoded X25519 public key. */
function rawPublic(key: KeyObject): Buffer {
  const der = key.export({ type: "spki", format: "der" });
  return Buffer.from(der.subarray(der.length - 32));
}

/** Rebuilds a KeyObject from raw private bytes by re-applying the PKCS#8 prefix. */
function privateFromRaw(raw: Buffer): KeyObject {
  const prefix = Buffer.from("302e020100300506032b656e04220420", "hex");
  return createPrivateKey({
    key: Buffer.concat([prefix, raw]),
    format: "der",
    type: "pkcs8",
  });
}

/** Rebuilds a KeyObject from raw public bytes by re-applying the SPKI prefix. */
function publicFromRaw(raw: Buffer): KeyObject {
  const prefix = Buffer.from("302a300506032b656e032100", "hex");
  return createPublicKey({
    key: Buffer.concat([prefix, raw]),
    format: "der",
    type: "spki",
  });
}

/**
 * Derives the two directional frame keys from an X25519 agreement.
 *
 * Two keys rather than one, for the same reason the device derives two: with a
 * shared key both directions would draw nonces from the same space, and a
 * request and a response bearing the same sequence number would reuse a nonce.
 * Under ChaCha20-Poly1305 that is not a weakening — it exposes the keystream and
 * makes the authenticator forgeable.
 */
export function deriveKeys(
  serverPrivateRaw: Buffer,
  devicePublicRaw: Buffer,
): FrameKeys {
  const shared = diffieHellman({
    privateKey: privateFromRaw(serverPrivateRaw),
    publicKey: publicFromRaw(devicePublicRaw),
  });

  const kdf = (info: string): Buffer =>
    Buffer.from(hkdfSync("sha256", shared, Buffer.alloc(0), info, 32));

  return {
    deviceToServer: kdf(INFO_D2S),
    serverToDevice: kdf(INFO_S2D),
  };
}

/** Thrown on replay, authentication failure, or malformed frames. */
export class InvalidFrame extends Error {}

/**
 * Seals and opens protocol frames.
 *
 * The counter is owned here and cannot be set from outside, so nonce reuse is
 * structurally impossible rather than merely discouraged. It survives reconnects
 * for the same reason the device's does: a socket that drops and returns is the
 * same peer under the same key, and a counter that rewound would replay nonces
 * on the precise failure path the transport exists to absorb.
 */
export class FrameCodec {
  private outbound = 0n;
  private inboundHigh = -1n;

  constructor(
    private readonly sealKey: Buffer,
    private readonly openKey: Buffer,
  ) {}

  /** Encrypts a payload under the next outbound sequence number. */
  seal(plaintext: Buffer, aad: Buffer): { seq: string; payload: string } {
    const seq = this.outbound++;
    const cipher = createCipheriv("chacha20-poly1305", this.sealKey, nonce(seq), {
      authTagLength: TAG_BYTES,
    });
    cipher.setAAD(aad, { plaintextLength: plaintext.length });

    const body = Buffer.concat([cipher.update(plaintext), cipher.final()]);
    const sealed = Buffer.concat([body, cipher.getAuthTag()]);

    return { seq: seq.toString(), payload: sealed.toString("base64") };
  }

  /**
   * Decrypts a frame, enforcing strictly increasing sequence.
   *
   * The counter advances only after the tag verifies. Advancing on receipt would
   * let anyone able to reach the relay burn sequence numbers with garbage and
   * stall the legitimate peer.
   */
  open(seq: bigint, payloadB64: string, aad: Buffer): Buffer {
    if (seq <= this.inboundHigh) {
      throw new InvalidFrame(`replayed or out-of-order sequence ${seq}`);
    }

    const sealed = Buffer.from(payloadB64, "base64");
    if (sealed.length < TAG_BYTES) {
      throw new InvalidFrame("frame shorter than authentication tag");
    }

    const body = sealed.subarray(0, sealed.length - TAG_BYTES);
    const tag = sealed.subarray(sealed.length - TAG_BYTES);

    const decipher = createDecipheriv("chacha20-poly1305", this.openKey, nonce(seq), {
      authTagLength: TAG_BYTES,
    });
    decipher.setAAD(aad, { plaintextLength: body.length });
    decipher.setAuthTag(tag);

    let plaintext: Buffer;
    try {
      plaintext = Buffer.concat([decipher.update(body), decipher.final()]);
    } catch {
      throw new InvalidFrame("authentication failed");
    }

    this.inboundHigh = seq;
    return plaintext;
  }
}

/**
 * Builds the 96-bit nonce for a sequence number.
 *
 * Big-endian counter in the low eight bytes, four leading zeros — byte-identical
 * to the device's construction, which is the only thing that matters here.
 */
function nonce(seq: bigint): Buffer {
  const n = Buffer.alloc(NONCE_BYTES);
  n.writeBigUInt64BE(seq, NONCE_BYTES - 8);
  return n;
}
