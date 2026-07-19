package com.hereliesaz.mcpserved.crypto

import android.util.Base64
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.util.concurrent.atomic.AtomicLong

/**
 * Seals and opens individual protocol frames.
 *
 * ChaCha20-Poly1305 with a 96-bit nonce derived from a monotonic counter. The
 * counter is per-direction, and each direction has its own key (see
 * [Pairing.deriveKeys]), so a device frame and a server frame may share a
 * sequence number without the nonces ever colliding under one key.
 *
 * Nonce reuse under ChaCha20-Poly1305 is not a degradation, it is a break: the
 * keystream repeats and the Poly1305 authenticator becomes forgeable. Every
 * design choice here exists to make reuse structurally impossible rather than
 * merely unlikely, which is why the counter is owned by this class and cannot be
 * set from outside it.
 *
 * The counter resets when keys are re-derived — that is, on re-pair. It does not
 * reset on reconnect, because a socket that drops and returns is the same peer
 * with the same key and a counter that rewound would replay nonces on the very
 * failure path the transport is designed to survive.
 */
class FrameCodec(
    private val sealKey: ByteArray,
    private val openKey: ByteArray
) {

    private val outbound = AtomicLong(0)

    /**
     * Highest inbound sequence accepted so far.
     *
     * Frames at or below it are rejected. A strictly-increasing requirement costs
     * nothing over a reliable ordered transport and closes replay entirely; a
     * sliding window would tolerate reordering the transport never produces.
     */
    private val inboundHigh = AtomicLong(-1)

    /** Sealed frame plus the sequence number it was sealed under. */
    data class Sealed(val seq: Long, val payloadB64: String)

    /** Raised when a frame fails authentication, replays, or is malformed. */
    class Invalid(message: String) : SecurityException(message)

    /**
     * Encrypts [plaintext] under the next outbound sequence number.
     *
     * @param plaintext serialized request or response
     * @param aad additional authenticated data — the device id, so that a frame
     *   cannot be replayed against a different device even by a relay that
     *   forwards it there
     */
    fun seal(plaintext: ByteArray, aad: ByteArray): Sealed {
        val seq = outbound.getAndIncrement()
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(sealKey), MAC_BITS, nonce(seq), aad))

        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var n = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        n += cipher.doFinal(out, n)

        return Sealed(seq, Base64.encodeToString(out, 0, n, Base64.NO_WRAP))
    }

    /**
     * Decrypts a frame, enforcing monotonic sequence.
     *
     * @param seq sequence number carried in the envelope
     * @param payloadB64 base64 ciphertext with appended Poly1305 tag
     * @param aad must match what the sender authenticated
     * @throws Invalid on replay, authentication failure, or malformed input
     */
    fun open(seq: Long, payloadB64: String, aad: ByteArray): ByteArray {
        if (seq <= inboundHigh.get()) {
            throw Invalid("replayed or out-of-order sequence $seq")
        }

        val ct = runCatching { Base64.decode(payloadB64, Base64.NO_WRAP) }
            .getOrElse { throw Invalid("payload is not valid base64") }

        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(openKey), MAC_BITS, nonce(seq), aad))

        val out = ByteArray(cipher.getOutputSize(ct.size))
        val plaintext = runCatching {
            var n = cipher.processBytes(ct, 0, ct.size, out, 0)
            n += cipher.doFinal(out, n)
            out.copyOf(n)
        }.getOrElse { throw Invalid("authentication failed") }

        // Advanced only after the tag verifies. Advancing on receipt would let an
        // attacker burn sequence numbers with garbage and stall the real peer.
        inboundHigh.set(seq)
        return plaintext
    }

    /**
     * Builds the 96-bit nonce for [seq].
     *
     * Big-endian counter in the low eight bytes, four leading zero bytes. The
     * space is exhausted at 2^64 frames, which at any plausible rate outlasts the
     * hardware by several orders of magnitude.
     */
    private fun nonce(seq: Long): ByteArray {
        val n = ByteArray(NONCE_BYTES)
        for (i in 0 until 8) {
            n[NONCE_BYTES - 1 - i] = ((seq ushr (8 * i)) and 0xff).toByte()
        }
        return n
    }

    private companion object {
        const val MAC_BITS = 128
        const val NONCE_BYTES = 12
    }
}
