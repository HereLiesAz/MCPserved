package com.hereliesaz.mcpserved.desktop.service

import com.hereliesaz.mcpserved.desktop.config.ConfigStore
import com.hereliesaz.mcpserved.desktop.config.DiscoveredAddress
import com.hereliesaz.mcpserved.desktop.config.DiscoveryCache
import com.hereliesaz.mcpserved.desktop.discovery.DeviceDiscovery
import java.time.Instant
import java.util.concurrent.CountDownLatch

/**
 * The always-on desktop half.
 *
 * Where the stdio server is spawned fresh for each model turn and lives only as
 * long as the host keeps it, this daemon runs continuously in the background and
 * does one thing: keep looking for the phone. It browses `_mcpserved._tcp` over
 * mDNS the whole time, and whenever the paired device appears it records the
 * address (see [DiscoveryCache]) so the next stdio server connects instantly
 * instead of paying its own discovery sweep.
 *
 * It holds no control socket open — the device advertises only while it is armed,
 * so simply seeing it on the network is the readiness signal. Being present here
 * is not authority: the pairing key still gates every connection.
 */
object ServiceDaemon {

    private const val POLL_MS = 3_000L

    private fun log(msg: String) = println("[${Instant.now()}] mcpserved-service: $msg")

    fun run() {
        log("started — watching ${DeviceDiscovery.SERVICE_TYPE}")
        val discovery = DeviceDiscovery()
        discovery.start { }

        val stop = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread {
            log("stopping")
            runCatching { discovery.stop() }
            stop.countDown()
        })

        var lastSeenHost: String? = null
        var warnedUnpaired = false

        while (stop.count > 0) {
            val config = ConfigStore.tryLoad()
            if (config == null) {
                if (!warnedUnpaired) {
                    log("not paired yet — pair from the app; discovery keeps running")
                    warnedUnpaired = true
                }
            } else {
                warnedUnpaired = false
                val device = discovery.snapshot().firstOrNull { it.deviceId == config.deviceId }
                if (device != null) {
                    DiscoveryCache.write(
                        DiscoveredAddress(
                            deviceId = device.deviceId,
                            host = device.host,
                            port = device.port,
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    if (device.host != lastSeenHost) {
                        log("device reachable at ${device.host}:${device.port}")
                        lastSeenHost = device.host
                    }
                } else if (lastSeenHost != null) {
                    log("device went quiet (no longer advertising)")
                    lastSeenHost = null
                }
            }
            if (!stop.await(POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) continue
        }
    }
}
