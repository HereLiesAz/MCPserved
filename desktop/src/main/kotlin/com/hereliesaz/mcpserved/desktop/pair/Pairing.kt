package com.hereliesaz.mcpserved.desktop.pair

import com.hereliesaz.mcpserved.desktop.config.ConfigStore
import com.hereliesaz.mcpserved.desktop.config.StoredConfig
import com.hereliesaz.mcpserved.desktop.crypto.Crypto
import com.hereliesaz.mcpserved.desktop.net.LanAddress
import java.security.SecureRandom

/**
 * One-time pairing between this machine and a device, for the app backend.
 *
 * The exchange establishes only that the two endpoints share a secret. It says
 * nothing about authority: what the device will actually permit is decided
 * afterwards, per package, in the grants screen.
 *
 * A single scan finishes the whole thing. This machine's public key, LAN
 * address, and a one-time [PairingResponder] token all travel out of band
 * together, in the one QR the device's camera reads — nothing here is typed
 * or pasted by a person. The moment the device scans it, it computes the
 * pairing locally and pushes its own public key straight back over the LAN,
 * authenticated by that same token (see `PairingResponder`), so the operator
 * never sees a second code. [startPairing] mints the identity and QR the
 * instant the pairing screen opens — not gated behind anything the device
 * has to provide first — and [completeAuto] finishes the exchange when
 * [PairingResponder] delivers the device's side.
 *
 * [completeWithPayload] remains for the one case with no shared LAN at all:
 * the `mcpserved pair` terminal command, bridging over `adb forward` alone,
 * where the device's payload is retyped from its own QR instead.
 */
object PairingFlow {

    /**
     * This machine's freshly generated keypair, the matching [PairingResponder]
     * token, and the QR encoding that carries both (plus this machine's LAN
     * address) to the device's camera in one shot.
     */
    data class Identity(val keyPair: Crypto.RawKeyPair, val token: ByteArray, val qr: String)

    /** The id the device paired under. */
    data class Result(val deviceId: String)

    /**
     * The `adb` fallback's result: the id the device paired under, plus this
     * machine's own reply payload — there is no LAN push in that path, so the
     * operator has to scan (or the CLI has to print) this back to the device
     * by hand, the same way pairing worked before the LAN auto-push existed.
     */
    data class CliResult(val deviceId: String, val reply: String)

    /**
     * Generates this machine's keypair and pairing token and builds the QR
     * encoding immediately — no device input required. Call once per pairing
     * attempt (a fresh call mints a fresh identity and token, invalidating
     * whatever was shown before); the result is held by the caller and handed
     * back to [completeAuto] once [PairingResponder] delivers the device's
     * submission.
     */
    fun startPairing(): Identity {
        val keyPair = Crypto.generateKeyPair()
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val host = LanAddress.resolve()?.hostAddress ?: "0.0.0.0"
        val qr = listOf(
            "mcpserved-pair",
            "1",
            Crypto.b64Url(keyPair.publicKey),
            host,
            PairingResponder.PORT.toString(),
            Crypto.b64Url(token),
        ).joinToString(":")
        return Identity(keyPair, token, qr)
    }

    /**
     * Persists the pairing from a [PairingResponder.Submission] already
     * verified against [identity]'s token, using the same keypair the QR
     * already showed.
     */
    fun completeAuto(identity: Identity, submission: PairingResponder.Submission): Result {
        ConfigStore.save(
            StoredConfig(
                deviceId = submission.deviceId,
                serverPrivateKey = Crypto.b64(identity.keyPair.privateKey),
                devicePublicKey = Crypto.b64(submission.devicePublicKey),
            ),
        )
        return Result(submission.deviceId)
    }

    /**
     * The `adb`-only fallback: consumes a device's own QR payload — the
     * `mcpserved:2:…` format it always shows, typed or pasted in from a
     * terminal — and persists the pairing using [keyPair].
     *
     * @throws IllegalArgumentException when the payload is not a v2 MCPserved pairing
     */
    fun completeWithPayload(payload: String, keyPair: Crypto.RawKeyPair): CliResult {
        val parts = payload.trim().split(":")
        require(parts.size == 4 && parts[0] == "mcpserved" && parts[1] == "2") {
            "that is not an MCPserved v2 pairing payload"
        }

        val deviceId = parts[2]
        val devicePublicKey = Crypto.unb64Url(parts[3])
        require(devicePublicKey.size == 32) { "device public key is not 32 bytes" }

        ConfigStore.save(
            StoredConfig(
                deviceId = deviceId,
                serverPrivateKey = Crypto.b64(keyPair.privateKey),
                devicePublicKey = Crypto.b64(devicePublicKey),
            ),
        )

        // Same envelope shape the device emits, so its one scanner handles both directions.
        val reply = listOf("mcpserved", "2", deviceId, Crypto.b64Url(keyPair.publicKey)).joinToString(":")
        return CliResult(deviceId, reply)
    }
}
