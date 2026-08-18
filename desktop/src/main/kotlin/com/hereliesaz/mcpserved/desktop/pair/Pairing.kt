package com.hereliesaz.mcpserved.desktop.pair

import com.hereliesaz.mcpserved.desktop.config.ConfigStore
import com.hereliesaz.mcpserved.desktop.config.StoredConfig
import com.hereliesaz.mcpserved.desktop.crypto.Crypto

/**
 * One-time pairing between this machine and a device, for the app backend.
 *
 * Both public keys travel out of band, by QR code, in both directions, so no
 * third party ever sits in the exchange that establishes trust. The shared secret
 * then authenticates this server when it later connects to the device's control
 * port — over the LAN once discovered, or through an `adb forward` tunnel.
 *
 * The exchange establishes only that the two endpoints share a secret. It says
 * nothing about authority: what the device will actually permit is decided
 * afterwards, per package, in the grants screen.
 *
 * The two legs are independent on purpose: [startPairing] needs nothing from the
 * device, so this machine's QR is ready to scan the moment the pairing screen
 * opens — not gated behind the operator having already typed or pasted the
 * device's own payload in first. [completeWithPayload] is the second, separate
 * leg, consuming whatever payload the device eventually provides and finishing
 * the exchange with the *same* keypair already shown — generating a fresh one at
 * that point instead would leave the device holding a shared secret for a key
 * this machine no longer has.
 */
object PairingFlow {

    /** This machine's freshly generated keypair, plus its QR-ready encoding. */
    data class Identity(val keyPair: Crypto.RawKeyPair, val qr: String)

    /** The reply payload to show the device, plus the id it paired under. */
    data class Result(val reply: String, val deviceId: String)

    /**
     * Generates this machine's keypair and its QR encoding immediately — no
     * device input required. Call once per pairing attempt (a fresh call
     * mints a fresh identity, invalidating whatever was shown before); the
     * result is held by the caller and handed back to [completeWithPayload]
     * once the device's payload arrives.
     */
    fun startPairing(): Identity {
        val keyPair = Crypto.generateKeyPair()
        // "desktop" here is never read back — MainViewModel.completePairing on the
        // device only inspects the public-key field of whatever it scans. It's
        // filled in only because the wire format is a fixed 4-part shape shared by
        // both directions, not because either side gives this value any meaning.
        val qr = listOf("mcpserved", "2", "desktop", Crypto.b64Url(keyPair.publicKey)).joinToString(":")
        return Identity(keyPair, qr)
    }

    /**
     * Consumes the device's QR payload and persists the pairing, using the
     * keypair [startPairing] already generated and already showed — not a new
     * one — so this leg's result matches what the device scanned.
     *
     * @throws IllegalArgumentException when the payload is not a v2 MCPserved pairing
     */
    fun completeWithPayload(payload: String, keyPair: Crypto.RawKeyPair): Result {
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

        // Same envelope shape the device emits, so one scanner handles both directions.
        val reply = listOf("mcpserved", "2", deviceId, Crypto.b64Url(keyPair.publicKey)).joinToString(":")
        return Result(reply, deviceId)
    }
}
