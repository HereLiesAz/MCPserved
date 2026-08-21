package com.hereliesaz.mcpserved.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AppCategory.of] and [PermissionTag.tagsFor] are the two signals the
 * Grants screen's "kind of app" and "already has access to" filters are
 * built on — exercised directly here since a wrong mapping would silently
 * mis-file an app under the wrong chip rather than fail loudly.
 *
 * Pinned to SDK 35 explicitly: `ApplicationInfo.category` (API 26) and
 * `CATEGORY_ACCESSIBILITY` (API 33) aren't present on every android-all jar
 * Robolectric might otherwise pick by default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppCategoryTest {

    @Test
    fun `maps every declared platform category`() {
        val cases = mapOf(
            ApplicationInfo.CATEGORY_GAME to AppCategory.GAME,
            ApplicationInfo.CATEGORY_AUDIO to AppCategory.AUDIO,
            ApplicationInfo.CATEGORY_VIDEO to AppCategory.VIDEO,
            ApplicationInfo.CATEGORY_IMAGE to AppCategory.IMAGE,
            ApplicationInfo.CATEGORY_SOCIAL to AppCategory.SOCIAL,
            ApplicationInfo.CATEGORY_NEWS to AppCategory.NEWS,
            ApplicationInfo.CATEGORY_MAPS to AppCategory.MAPS,
            ApplicationInfo.CATEGORY_PRODUCTIVITY to AppCategory.PRODUCTIVITY,
            ApplicationInfo.CATEGORY_ACCESSIBILITY to AppCategory.ACCESSIBILITY,
            ApplicationInfo.CATEGORY_UNDEFINED to AppCategory.UNDEFINED
        )
        cases.forEach { (platformCategory, expected) ->
            val info = ApplicationInfo().apply { category = platformCategory }
            assertEquals(expected, AppCategory.of(info))
        }
    }

    @Test
    fun `unrecognized category value falls back to undefined`() {
        val info = ApplicationInfo().apply { category = 999 }
        assertEquals(AppCategory.UNDEFINED, AppCategory.of(info))
    }

    @Test
    fun `tags for a granted permission it maps to`() {
        assertEquals(setOf(PermissionTag.CAMERA), PermissionTag.tagsFor(setOf(Manifest.permission.CAMERA)))
    }

    @Test
    fun `multiple permissions within one tag still yield a single tag`() {
        val granted = setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(setOf(PermissionTag.LOCATION), PermissionTag.tagsFor(granted))
    }

    @Test
    fun `permissions across tags all surface`() {
        val granted = setOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        assertEquals(setOf(PermissionTag.CAMERA, PermissionTag.MICROPHONE), PermissionTag.tagsFor(granted))
    }

    @Test
    fun `an unrelated permission yields no tags`() {
        assertEquals(emptySet<PermissionTag>(), PermissionTag.tagsFor(setOf(Manifest.permission.WAKE_LOCK)))
    }

    @Test
    fun `no granted permissions yields no tags`() {
        assertEquals(emptySet<PermissionTag>(), PermissionTag.tagsFor(emptySet()))
    }
}
