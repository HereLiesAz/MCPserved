import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
}

/**
 * jpackage rejects a version whose first component is zero for the Windows and
 * macOS installers, so the installer version is decoupled from the Android
 * versionName (which lives at 0.x). It defaults to 1.0.0 and CI overrides it with
 * the release tag via `-PdesktopPackageVersion=x.y.z`.
 */
val desktopPackageVersion: String = (project.findProperty("desktopPackageVersion") as String?) ?: "1.0.0"

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
            packageVersion = desktopPackageVersion
            description = "Desktop MCP server for MCPserved — control an authorized Android device"
            vendor = "hereliesaz"
            copyright = "© hereliesaz"

            modules("java.naming", "java.management", "jdk.crypto.ec")

            linux {
                menuGroup = "Development"
            }
            windows {
                menuGroup = "MCPserved"
                // A stable UUID keeps upgrades in place instead of installing
                // side-by-side copies.
                upgradeUuid = "6f3a1c2e-8d54-4f2b-9a1d-2c7e5b0d9f11"
            }
            macOS {
                bundleID = "com.hereliesaz.mcpserved.desktop"
            }
        }
    }
}
