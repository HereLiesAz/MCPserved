package com.hereliesaz.mcpserved.ui

import android.app.Activity
import com.hereliesaz.aznavrail.updates.AzUpdateChecker
import com.hereliesaz.aznavrail.updates.play.AzPlayUpdates

/** Performs an explicit update check; never contacts a server at startup. */
internal fun checkAppUpdate(activity: Activity) {
    AzUpdateChecker.check(activity, "HereLiesAz/MCPserved", manual = true, playSource = AzPlayUpdates)
}
