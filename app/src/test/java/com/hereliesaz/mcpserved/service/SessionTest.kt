package com.hereliesaz.mcpserved.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {

    @Test
    fun `touch extends by the requested ttl rather than the default`() {
        val session = Session(defaultTtlSec = 300)
        session.begin(ttlSec = 30)

        val touched = session.touch()!!

        assertEquals(30, touched.ttlSec)
        assertTrue("expiry must stay near 30 s, not 300 s", touched.remainingSec() <= 30)
    }

    @Test
    fun `touch with no live session returns null`() {
        assertEquals(null, Session().touch())
    }
}
