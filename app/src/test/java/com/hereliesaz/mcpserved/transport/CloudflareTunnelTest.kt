package com.hereliesaz.mcpserved.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for [CloudflareTunnel.tunnelUrlIn] — the exact bug this
 * guards against shipped and was confirmed live on a real device: `cloudflared`
 * logs its own call to the `api.trycloudflare.com` control-plane endpoint on
 * the same combined stdout/stderr stream, and a naive "first URL that matches"
 * parse grabs that instead of the actual assigned tunnel hostname.
 */
class CloudflareTunnelTest {

    @Test
    fun `finds the real tunnel URL on the line cloudflared actually prints it`() {
        assertEquals(
            "https://special-lamp-cats.trycloudflare.com",
            CloudflareTunnel.tunnelUrlIn("|  https://special-lamp-cats.trycloudflare.com  |"),
        )
    }

    @Test
    fun `ignores the control-plane endpoint`() {
        assertNull(CloudflareTunnel.tunnelUrlIn("INF Requesting new quick Tunnel from https://api.trycloudflare.com"))
    }

    @Test
    fun `ignores the control-plane endpoint even alongside a real URL on the same line`() {
        assertEquals(
            "https://special-lamp-cats.trycloudflare.com",
            CloudflareTunnel.tunnelUrlIn(
                "registered via https://api.trycloudflare.com as https://special-lamp-cats.trycloudflare.com",
            ),
        )
    }

    @Test
    fun `is case-insensitive about the control-plane hostname`() {
        assertNull(CloudflareTunnel.tunnelUrlIn("https://API.TRYCLOUDFLARE.COM"))
    }

    @Test
    fun `a line with no trycloudflare URL at all yields nothing`() {
        assertNull(CloudflareTunnel.tunnelUrlIn("INF Starting tunnel connection"))
    }
}
