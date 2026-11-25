package com.hereliesaz.mcpserved.desktop.config

import com.hereliesaz.mcpserved.desktop.net.AdbLink
import com.hereliesaz.mcpserved.desktop.net.isOk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ConfigStoreTest {
    @Test
    fun distinguishesMissingInvalidAndValidPairing() {
        val dir = Files.createTempDirectory("mcpserved-pairing-")
        val path = dir.resolve("pairing.json")
        try {
            assertNull(ConfigStore.tryLoadForBackend(path))
            Files.writeString(path, "{")
            assertThrows(IllegalStateException::class.java) { ConfigStore.tryLoadForBackend(path) }
            Files.writeString(path, """{"deviceId":"test","serverPrivateKey":"AA==","devicePublicKey":"AA=="}""")
            assertThrows(IllegalStateException::class.java) { ConfigStore.tryLoadForBackend(path) }
            Files.writeString(path, """{"deviceId":"test","serverPrivateKey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","devicePublicKey":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="}""")
            val config = requireNotNull(ConfigStore.tryLoadForBackend(path))
            assertEquals("test", config.deviceId)
            assertArrayEquals(ByteArray(32), config.serverPrivateKey)
            assertArrayEquals(ByteArray(32) { 1 }, config.devicePublicKey)
            assertThrows(IllegalStateException::class.java) { ConfigStore.tryLoadForBackend(dir) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun autoAdbStopsAfterPairingOrReadError() {
        val request = buildJsonObject { put("op", JsonPrimitive("session_end")) }
        var unpaired = true
        val link = AdbLink { unpaired }
        assertTrue(link.send(request, 100).isOk())
        unpaired = false
        assertFalse(link.send(request, 100).isOk())
        unpaired = true
        assertFalse(link.send(request, 100).isOk())
        assertFalse(AdbLink { error("unreadable") }.send(request, 100).isOk())
        assertTrue(AdbLink().send(request, 100).isOk())
    }
}
