package com.hereliesaz.mcpserved.desktop.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import java.util.Base64

/**
 * Server-side mirror of the device's `crypto/Pairing.kt` and `crypto/Frame.kt`.
 *
 * Every constant here — the HKDF info strings, the nonce layout, the tag length,
 * the AAD — must match the Android side exactly. A mismatch does not degrade
 * gracefully; it produces frames that fail authentication on arrival with no
 * indication of which side is wrong. The two runtimes cannot share code, so this
 * file is the one place a silent divergence could hide.
 *
 * BouncyCastle is used on both ends deliberately: the same implementation on the
 * phone and the desktop removes a class of interop bug that two different crypto
 * providers can introduce at the edges.
 */
object Crypto {

    private const val INFO_D2S = "mcpserved d2s v1"
    private const val INFO_S2D = "mcpserved s2d v1"

    private val stdEncoder = Base64.getEncoder()
    private val stdDecoder = Base64.getDecoder()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    /** Raw 32-byte X25519 keys, as they appear in the QR payload. */
    data class RawKeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    /** Directional 256-bit ChaCha20-Poly1305 keys. */
    data class FrameKeys(val deviceToServer: ByteArray, val serverToDevice: ByteArray)

    fun b64(bytes: ByteArray): String = stdEncoder.encodeToString(bytes)
    fun unb64(s: String): ByteArray = stdDecoder.decode(s)
    fun b64Url(bytes: ByteArray): String = urlEncoder.encodeToString(bytes)
    fun unb64Url(s: String): ByteArray = urlDecoder.decode(s)

    /** Generates an X25519 keypair and returns both halves in raw 32-byte form. */
    fun generateKeyPair(): RawKeyPair {
        val priv = X25519PrivateKeyParameters(SecureRandom())
        val pub = priv.generatePublicKey()
        return RawKeyPair(priv.encoded, pub.encoded)
    }

    /**
     * Derives the two directional frame keys from an X25519 agreement and a
     * per-connection salt.
     *
     * Two keys rather than one: with a shared key both directions would draw
     * nonces from the same space, and a request and a response bearing the same
     * sequence number would reuse a nonce — which under ChaCha20-Poly1305 exposes
     * the keystream and forges the authenticator. The salt is fresh random bytes
     * this side sends in its opening hello and the device folds into the same KDF,
     * so every connection derives distinct keys and each counter may restart at
     * zero without any replay risk.
     */
    fun deriveKeys(serverPrivateRaw: ByteArray, devicePublicRaw: ByteArray, salt: ByteArray): FrameKeys {
        val priv = X25519PrivateKeyParameters(serverPrivateRaw, 0)
        val peer = X25519PublicKeyParameters(devicePublicRaw, 0)
        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        X25519Agreement().apply {
            init(priv)
            calculateAgreement(peer, shared, 0)
        }
        return FrameKeys(
            deviceToServer = hkdf(shared, salt, INFO_D2S),
            serverToDevice = hkdf(shared, salt, INFO_S2D),
        )
    }

    private fun hkdf(secret: ByteArray, salt: ByteArray, info: String): ByteArray {
        val out = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(secret, salt, info.toByteArray()))
            generateBytes(out, 0, out.size)
        }
        return out
    }
}

/** Raised on replay, authentication failure, or malformed frames. */
class InvalidFrame(message: String) : Exception(message)

/**
 * Seals and opens protocol frames, the desktop counterpart to `FrameCodec` on
 * the device.
 *
 * The counter is owned here and cannot be set from outside, so nonce reuse is
 * structurally impossible. It survives reconnects because a socket that drops and
 * returns is the same peer under the same key; a counter that rewound would
 * replay nonces on the very failure path the transport exists to absorb.
 */
class FrameCodec(
    private val sealKey: ByteArray,
    private val openKey: ByteArray,
) {
    private var outbound = 0L
    private var inboundHigh = -1L

    private val stdEncoder = Base64.getEncoder()
    private val stdDecoder = Base64.getDecoder()

    data class Sealed(val seq: Long, val payloadB64: String)

    /** Encrypts [plaintext] under the next outbound sequence number. */
    @Synchronized
    fun seal(plaintext: ByteArray, aad: ByteArray): Sealed {
        val seq = outbound++
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(sealKey), MAC_BITS, nonce(seq), aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var n = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        n += cipher.doFinal(out, n)
        return Sealed(seq, stdEncoder.encodeToString(out.copyOf(n)))
    }

    /**
     * Decrypts a frame, enforcing strictly increasing sequence.
     *
     * The counter advances only after the tag verifies. Advancing on receipt
     * would let anyone reaching the socket burn sequence numbers with garbage and
     * stall the legitimate peer.
     */
    @Synchronized
    fun open(seq: Long, payloadB64: String, aad: ByteArray): ByteArray {
        if (seq <= inboundHigh) throw InvalidFrame("replayed or out-of-order sequence $seq")

        val ct = runCatching { stdDecoder.decode(payloadB64) }
            .getOrElse { throw InvalidFrame("payload is not valid base64") }
        if (ct.size < TAG_BYTES) throw InvalidFrame("frame shorter than authentication tag")

        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(openKey), MAC_BITS, nonce(seq), aad))
        val out = ByteArray(cipher.getOutputSize(ct.size))
        val plaintext = runCatching {
            var n = cipher.processBytes(ct, 0, ct.size, out, 0)
            n += cipher.doFinal(out, n)
            out.copyOf(n)
        }.getOrElse { throw InvalidFrame("authentication failed") }

        inboundHigh = seq
        return plaintext
    }

    /** Big-endian counter in the low eight bytes, four leading zeros. */
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
        const val TAG_BYTES = 16
    }
}
