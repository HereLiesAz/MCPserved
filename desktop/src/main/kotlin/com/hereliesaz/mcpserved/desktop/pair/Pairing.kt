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
 */
object PairingFlow {

    /** The reply payload to show the device, plus the id it paired under. */
    data class Result(val reply: String, val deviceId: String)

    /**
     * Consumes the device's QR payload, mints this machine's keypair, persists the
     * pairing, and returns the reply payload for the device to scan back.
     *
     * @throws IllegalArgumentException when the payload is not a v2 MCPserved pairing
     */
    fun pairFromPayload(payload: String): Result {
        val parts = payload.trim().split(":")
        require(parts.size == 4 && parts[0] == "mcpserved" && parts[1] == "2") {
            "that is not an MCPserved v2 pairing payload"
        }

        val deviceId = parts[2]
        val devicePublicKey = Crypto.unb64Url(parts[3])
        require(devicePublicKey.size == 32) { "device public key is not 32 bytes" }

        val keyPair = Crypto.generateKeyPair()
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
