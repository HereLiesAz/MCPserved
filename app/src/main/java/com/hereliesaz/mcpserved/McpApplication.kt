package com.hereliesaz.mcpserved

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Registers BouncyCastle ahead of the platform providers.
 *
 * Android ships a stripped BouncyCastle under the same provider name, missing
 * several algorithms this application depends on. Removing the platform entry
 * before inserting the full one is the documented workaround; without it the
 * insert is silently ignored and X25519 fails at first use rather than at
 * startup, which is the worse of the two failure times.
 */
class McpApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
