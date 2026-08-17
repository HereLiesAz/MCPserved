package com.hereliesaz.mcpserved.transport

import android.util.Base64
import com.hereliesaz.mcpserved.crypto.FrameCodec
import com.hereliesaz.mcpserved.crypto.KeyPairing
import com.hereliesaz.mcpserved.crypto.Pairing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom

/**
 * Exercises [FrameSession] end to end — handshake, sealed dispatch, sealed
 * reply — the same behavior [LocalServer] used to inline directly, now
 * proven independent of both `Socket` and Android `Context`.
 *
 * The device side uses [KeyPairing]/[RequestHandler] fakes; the "peer" side
 * (the role a desktop or [RemoteRelayClient]'s counterpart plays) drives the
 * real X25519/HKDF/ChaCha20-Poly1305 math directly, so this proves the wire
 * format, not just that two fakes agree with each other.
 *
 * Runs under Robolectric only because [FrameCodec] and [FrameSession] call
 * `android.util.Base64`, which throws "not mocked" under a bare local JVM
 * test; nothing here touches `EncryptedSharedPreferences` or any other
 * Android framework surface.
 */
@RunWith(RobolectricTestRunner::class)
class FrameSessionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "op"
    }

    /** A pure-JVM stand-in for [Pairing]: same key-derivation math, no `Context`. */
    private class FakeKeyPairing(
        override val deviceId: String,
        private val priv: X25519PrivateKeyParameters,
        private val peerPub: X25519PublicKeyParameters,
    ) : KeyPairing {
        override fun deriveKeys(salt: ByteArray): Pairing.FrameKeys {
            val shared = sharedSecret(priv, peerPub)
            return Pairing.FrameKeys(
                deviceToServer = hkdf(shared, salt, "mcpserved d2s v1"),
                serverToDevice = hkdf(shared, salt, "mcpserved s2d v1"),
            )
        }
    }

    /** An in-memory [FrameStream] the test drives from the "peer" end. */
    private class TestFrameStream : FrameStream {
        /** Lines the peer sends toward the device; [FrameSession.run] reads these. */
        val inbound = Channel<String?>(Channel.UNLIMITED)

        /** Lines the device writes back; the peer reads these. */
        val outbound = Channel<String?>(Channel.UNLIMITED)

        override suspend fun readLine(): String? = inbound.receiveCatching().getOrNull()
        override suspend fun writeLine(line: String) {
            outbound.trySend(line)
        }

        override fun close() {
            inbound.close()
            outbound.close()
        }
    }

    @Test
    fun `handshake then a request dispatches to the handler and the sealed reply opens`() = runTest {
        val devicePriv = X25519PrivateKeyParameters(SecureRandom())
        val devicePub = devicePriv.generatePublicKey()
        val peerPriv = X25519PrivateKeyParameters(SecureRandom())
        val peerPub = peerPriv.generatePublicKey()
        val deviceId = "test-device-id"

        var handledRequest: Request? = null
        val handler = RequestHandler { req ->
            handledRequest = req
            Response.Ack()
        }

        val session = FrameSession(FakeKeyPairing(deviceId, devicePriv, peerPub), handler)
        val stream = TestFrameStream()
        var connected = false
        val sessionJob = launch { session.run(stream, onConnected = { connected = true }) }

        // ---- peer side: derive the same directional keys from a fresh salt ----
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val sharedSecret = sharedSecret(peerPriv, devicePub)
        val peerCodec = FrameCodec(
            sealKey = hkdf(sharedSecret, salt, "mcpserved s2d v1"),
            openKey = hkdf(sharedSecret, salt, "mcpserved d2s v1"),
        )

        stream.inbound.trySend(
            json.encodeToString(Hello(PROTO_VERSION, Base64.encodeToString(salt, Base64.NO_WRAP or Base64.URL_SAFE))),
        )

        val aad = deviceId.toByteArray()
        val sealedReq = peerCodec.seal(json.encodeToString<Request>(Request.Capabilities).toByteArray(), aad)
        stream.inbound.trySend(
            json.encodeToString(Envelope(deviceId, sealedReq.seq, sealedReq.payloadB64)),
        )

        val replyLine = stream.outbound.receive()
        assertTrue("connected callback should fire once keys derive", connected)
        assertEquals(Request.Capabilities, handledRequest)

        val replyEnvelope = json.decodeFromString<Envelope>(replyLine!!)
        assertEquals(deviceId, replyEnvelope.deviceId)
        val plaintext = peerCodec.open(replyEnvelope.seq, replyEnvelope.payload, aad)
        val response = json.decodeFromString<Response>(String(plaintext))
        assertTrue(response.ok)

        stream.close()
        sessionJob.join()
    }

    @Test
    fun `an unopenable frame is dropped without a reply`() = runTest {
        val devicePriv = X25519PrivateKeyParameters(SecureRandom())
        val peerPriv = X25519PrivateKeyParameters(SecureRandom())
        val peerPub = peerPriv.generatePublicKey()
        val strangerPriv = X25519PrivateKeyParameters(SecureRandom())
        val deviceId = "test-device-id"

        var handlerCalls = 0
        val handler = RequestHandler { req -> handlerCalls++; Response.Ack() }

        val session = FrameSession(FakeKeyPairing(deviceId, devicePriv, peerPub), handler)
        val stream = TestFrameStream()
        val sessionJob = launch { session.run(stream) }

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        stream.inbound.trySend(
            json.encodeToString(Hello(PROTO_VERSION, Base64.encodeToString(salt, Base64.NO_WRAP or Base64.URL_SAFE))),
        )

        // A frame sealed under a key the device never derived (wrong private key)
        // — must be silently dropped, not answered, not crashed on.
        val strangerShared = sharedSecret(strangerPriv, devicePriv.generatePublicKey())
        val strangerCodec = FrameCodec(
            sealKey = hkdf(strangerShared, salt, "mcpserved s2d v1"),
            openKey = hkdf(strangerShared, salt, "mcpserved d2s v1"),
        )
        val bogus = strangerCodec.seal(
            json.encodeToString<Request>(Request.Capabilities).toByteArray(),
            deviceId.toByteArray(),
        )
        stream.inbound.trySend(json.encodeToString(Envelope(deviceId, bogus.seq, bogus.payloadB64)))

        // Follow it with a legitimate, correctly sealed frame from the real peer,
        // to prove the loop kept running rather than dying on the bogus one.
        val realShared = sharedSecret(peerPriv, devicePriv.generatePublicKey())
        val realCodec = FrameCodec(
            sealKey = hkdf(realShared, salt, "mcpserved s2d v1"),
            openKey = hkdf(realShared, salt, "mcpserved d2s v1"),
        )
        val real = realCodec.seal(json.encodeToString<Request>(Request.Capabilities).toByteArray(), deviceId.toByteArray())
        stream.inbound.trySend(json.encodeToString(Envelope(deviceId, real.seq, real.payloadB64)))

        val replyLine = stream.outbound.receive()
        assertEquals(1, handlerCalls) // only the legitimate frame reached the handler
        assertTrue(replyLine != null)

        stream.close()
        sessionJob.join()
    }

    @Test
    fun `a Hello at the wrong protocol version ends the session without dispatching`() = runTest {
        val devicePriv = X25519PrivateKeyParameters(SecureRandom())
        val peerPub = X25519PrivateKeyParameters(SecureRandom()).generatePublicKey()
        val handler = RequestHandler { Response.Ack() }
        val session = FrameSession(FakeKeyPairing("device", devicePriv, peerPub), handler)
        val stream = TestFrameStream()

        stream.inbound.trySend(json.encodeToString(Hello(v = PROTO_VERSION + 1, salt = "AAAA")))
        val ended = session.run(stream)

        assertEquals(FrameSession.EndReason.STREAM_CLOSED, ended)
        stream.close()
    }

    @Test
    fun `an empty stream ends immediately`() = runTest {
        val devicePriv = X25519PrivateKeyParameters(SecureRandom())
        val peerPub = X25519PrivateKeyParameters(SecureRandom()).generatePublicKey()
        val session = FrameSession(FakeKeyPairing("device", devicePriv, peerPub)) { Response.Ack() }
        val stream = TestFrameStream()
        stream.close()

        assertEquals(FrameSession.EndReason.STREAM_CLOSED, session.run(stream))
        assertNull(stream.outbound.tryReceive().getOrNull())
    }

    private companion object {
        fun sharedSecret(priv: X25519PrivateKeyParameters, pub: X25519PublicKeyParameters): ByteArray {
            val out = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
            X25519Agreement().apply {
                init(priv)
                calculateAgreement(pub, out, 0)
            }
            return out
        }

        fun hkdf(secret: ByteArray, salt: ByteArray, info: String): ByteArray {
            val out = ByteArray(32)
            HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters(secret, salt, info.toByteArray()))
                generateBytes(out, 0, out.size)
            }
            return out
        }
    }
}
