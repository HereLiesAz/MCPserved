import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // Shared with the Android side: X25519 / HKDF / ChaCha20-Poly1305 so the
    // sealed-frame wire format matches byte for byte.
    implementation(libs.bouncycastle)
    // QR rendering for the pairing screen.
    implementation(libs.zxing.core)
    // mDNS / DNS-SD: browse for devices the phone advertises over the LAN.
    implementation(libs.jmdns)
    testImplementation(libs.junit)
}

/**
 * Public product version is MAJOR.MINOR.PATCH.BUILD, supplied by the central
 * desktop workflow. Local packaging reads the checked-in version baseline.
 * Native formats may restrict the number of components; never change the
 * public major version to satisfy an installer format.
 */
val desktopVersionBaseline = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val desktopPackageVersion: String = (project.findProperty("desktopPackageVersion") as String?) ?:
    listOf(
        desktopVersionBaseline.getProperty("versionMajor"),
        desktopVersionBaseline.getProperty("versionMinor"),
        if (desktopVersionBaseline.getProperty("versionMinor") == desktopVersionBaseline.getProperty("versionMinorLast"))
            desktopVersionBaseline.getProperty("versionPatch") else "0",
        desktopVersionBaseline.getProperty("versionBuild"),
    ).joinToString(".")
val desktopVersionParts = desktopPackageVersion.split(".")
require(desktopVersionParts.size in 3..4 && desktopVersionParts.all { it.matches(Regex("0|[1-9][0-9]*")) }) {
    "desktopPackageVersion must be MAJOR.MINOR.PATCH[.BUILD]"
}
val desktopShortVersion = desktopVersionParts.take(3).joinToString(".")
val desktopBuildNumber = desktopVersionParts.getOrNull(3)?.toInt()
    ?: desktopVersionParts[2].toInt()
val windowsInstallerVersion = "${desktopVersionParts[0]}.${desktopVersionParts[1]}.$desktopBuildNumber"
val macInstallerBuildVersion = "${maxOf(1, desktopBuildNumber)}.0.0"
// jpackage validates --app-version as CFBundleVersion before using Compose's
// generated Info.plist. The placeholder is macOS-only and never a release version.
val macJpackageVersion = if (desktopVersionParts[0] == "0") "1.0.0" else desktopShortVersion
val desktopNativeVersion = when {
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> windowsInstallerVersion
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> macJpackageVersion
    else -> desktopPackageVersion
}

compose.desktop {
    application {
        mainClass = "com.hereliesaz.mcpserved.desktop.MainKt"

        nativeDistributions {
            // Deb + Rpm cover the common Linux packaging; Dmg and Msi are the
            // native installers macOS and Windows users expect. The matching CI
            // job runs on each OS because jpackage can only build for the host it
            // runs on.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "MCPserved"
            packageVersion = desktopShortVersion
            description = "Desktop MCP server for MCPserved — control an authorized Android device"
            vendor = "hereliesaz"
            copyright = "© hereliesaz"

            // java.net.http: the opt-in relay path's WebSocket client (RelayFrameStream).
            modules("java.naming", "java.management", "jdk.crypto.ec", "java.net.http")

            // The launcher icon is derived from the Android app's, so the phone
            // and the desktop present the same mark. jpackage wants a different
            // container per OS: .png (Linux), .ico (Windows), .icns (macOS).
            linux {
                packageVersion = desktopPackageVersion
                menuGroup = "Development"
                iconFile.set(project.file("icons/icon.png"))
            }
            windows {
                packageVersion = windowsInstallerVersion
                menuGroup = "MCPserved"
                // A stable UUID keeps upgrades in place instead of installing
                // side-by-side copies.
                upgradeUuid = "6f3a1c2e-8d54-4f2b-9a1d-2c7e5b0d9f11"
                iconFile.set(project.file("icons/icon.ico"))
            }
            macOS {
                packageVersion = macJpackageVersion
                bundleID = "com.hereliesaz.mcpserved.desktop"
                // CFBundleVersion is a positive build identifier, not the product major.
                packageBuildVersion = macInstallerBuildVersion
                iconFile.set(project.file("icons/icon.icns"))
            }
        }
    }
}

// Restore public macOS version before packaging the app image into a DMG.
// The current desktop build has no Developer ID signing; refresh the ad-hoc
// signature after changing Info.plist, without modifying nested runtime signatures.
// Once production signing is configured, this must run before Developer ID signing.
if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
    val bundle = layout.buildDirectory.dir("compose/binaries/main/app/MCPserved.app").get().asFile.absolutePath
    val visibleVersion = desktopShortVersion
    val bundleBuildVersion = macInstallerBuildVersion
    tasks.matching { it.name == "createDistributable" }.configureEach {
        inputs.property("mcpservedPublicMacVersion", visibleVersion)
        inputs.property("mcpservedMacBuildVersion", bundleBuildVersion)
        doLast {
            val plist = "$bundle/Contents/Info.plist"
            for (command in listOf(
                listOf("/usr/libexec/PlistBuddy", "-c", "Set :CFBundleShortVersionString $visibleVersion", plist),
                listOf("/usr/libexec/PlistBuddy", "-c", "Set :CFBundleVersion $bundleBuildVersion", plist),
                listOf("/usr/bin/codesign", "--force", "--sign", "-", "--timestamp=none", "--preserve-metadata=entitlements,flags", bundle),
                listOf("/usr/bin/codesign", "--verify", "--strict", bundle),
            )) {
                check(ProcessBuilder(command).inheritIO().start().waitFor() == 0) {
                    "Failed to restore the public macOS bundle version: ${command.first()}"
                }
            }
        }
    }
}

// Exercise the actual installer payload on CI's native OS, not only JVM classes.
// Keep local packaging independent of Python; developers can opt in after packaging.
val smokeDesktopPackages = tasks.register<Exec>("smokeDesktopPackages") {
    group = "verification"
    description = "Extract native installers and verify the packaged MCP stdio handshake."
    val python = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"
    commandLine(
        python,
        layout.projectDirectory.file("verify_packages.py").asFile.absolutePath,
        layout.buildDirectory.dir("compose/binaries/main").get().asFile.absolutePath,
        desktopNativeVersion,
        desktopPackageVersion,
        macInstallerBuildVersion,
    )
}
if (providers.environmentVariable("CI").orNull == "true") {
    tasks.matching { it.name == "packageDistributionForCurrentOS" }.configureEach {
        finalizedBy(smokeDesktopPackages)
    }
}
