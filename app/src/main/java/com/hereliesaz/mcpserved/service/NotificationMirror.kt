package com.hereliesaz.mcpserved.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hereliesaz.mcpserved.transport.NotificationEntry
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors the active notification shade.
 *
 * A separate service from [McpAccessibilityService] because the platform binds
 * notification access through its own permission and its own lifecycle; the two
 * are enabled independently and either may be absent while the other runs.
 *
 * Only notifications from granted packages are ever returned, and the filtering
 * happens at read time rather than at post time. Filtering on arrival would mean
 * the mirror's contents depend on what was granted when each notification
 * landed, so revoking a grant would leave already-captured content readable.
 */
class NotificationMirror : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: NotificationMirror? = null
            private set
    }

    /** Keyed by [StatusBarNotification.getKey], which is stable across updates. */
    private val active = ConcurrentHashMap<String, NotificationEntry>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // Seed from whatever is already posted; onNotificationPosted only fires
        // for arrivals after the bind, and the shade is rarely empty at that moment.
        runCatching { activeNotifications }.getOrNull()?.forEach { put(it) }
    }

    override fun onListenerDisconnected() {
        instance = null
        active.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { put(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { active.remove(it) }
    }

    private fun put(sbn: StatusBarNotification) {
        val x = sbn.notification?.extras
        active[sbn.key] = NotificationEntry(
            pkg = sbn.packageName,
            key = sbn.key,
            title = x?.getCharSequence("android.title")?.toString(),
            text = x?.getCharSequence("android.text")?.toString(),
            postedAtEpochMs = sbn.postTime
        )
    }

    /**
     * Current notifications from [allowed] packages, newest first.
     *
     * @param allowed package names holding a live OBSERVE grant
     */
    fun snapshot(allowed: Set<String>): List<NotificationEntry> =
        active.values
            .filter { it.pkg in allowed }
            .sortedByDescending { it.postedAtEpochMs }

    /** Dismisses a notification by key. No-op when the key is unknown. */
    fun dismiss(key: String) {
        runCatching { cancelNotification(key) }
        active.remove(key)
    }
}
