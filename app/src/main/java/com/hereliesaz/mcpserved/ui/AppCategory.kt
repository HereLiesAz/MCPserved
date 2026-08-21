package com.hereliesaz.mcpserved.ui

import android.Manifest
import android.content.pm.ApplicationInfo

/**
 * The platform's own notion of what *kind* of app this is — the same
 * `android:appCategory` manifest attribute Play Store uses to file an app
 * under a store category, surfaced back via [ApplicationInfo.category].
 *
 * Coverage is voluntary and far from universal: most apps never declare
 * one, so [UNDEFINED] is the common case rather than the exception. Still
 * worth filtering on for the apps that *do* declare it — it's a genuine
 * platform signal, not a guess made up from the app's name or icon.
 */
enum class AppCategory(val label: String) {
    GAME("Games"),
    AUDIO("Audio"),
    VIDEO("Video"),
    IMAGE("Photo & video"),
    SOCIAL("Social"),
    NEWS("News & magazines"),
    MAPS("Maps & navigation"),
    PRODUCTIVITY("Productivity"),
    ACCESSIBILITY("Accessibility"),
    UNDEFINED("Uncategorized");

    companion object {
        fun of(info: ApplicationInfo): AppCategory = when (info.category) {
            ApplicationInfo.CATEGORY_GAME -> GAME
            ApplicationInfo.CATEGORY_AUDIO -> AUDIO
            ApplicationInfo.CATEGORY_VIDEO -> VIDEO
            ApplicationInfo.CATEGORY_IMAGE -> IMAGE
            ApplicationInfo.CATEGORY_SOCIAL -> SOCIAL
            ApplicationInfo.CATEGORY_NEWS -> NEWS
            ApplicationInfo.CATEGORY_MAPS -> MAPS
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> PRODUCTIVITY
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> ACCESSIBILITY
            else -> UNDEFINED
        }
    }
}

/**
 * Sensitive device-data permissions grouped into the buckets a device owner
 * actually reasons in when deciding what to hand an AI. Computed from what
 * the OS **currently grants** the app — not merely what its manifest
 * declares wanting — so "Camera" here means the app can use the camera
 * right now, not that it once asked and was refused.
 *
 * This is a curated allowlist rather than every dangerous permission Android
 * defines: the ones a device owner is likely to actually care about when
 * scanning a grant list, not an exhaustive permissions audit.
 */
enum class PermissionTag(val label: String, private val permissions: Set<String>) {
    CAMERA("Camera", setOf(Manifest.permission.CAMERA)),
    MICROPHONE("Microphone", setOf(Manifest.permission.RECORD_AUDIO)),
    LOCATION(
        "Location",
        setOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    ),
    CONTACTS(
        "Contacts",
        setOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    ),
    SMS_PHONE(
        "SMS & phone",
        setOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE
        )
    ),
    STORAGE(
        "Storage & media",
        setOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    ),
    CALENDAR(
        "Calendar",
        setOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    );

    companion object {
        /** Every tag with at least one of its permissions in [granted]. */
        fun tagsFor(granted: Set<String>): Set<PermissionTag> =
            entries.filterTo(mutableSetOf()) { tag -> tag.permissions.any { it in granted } }
    }
}
