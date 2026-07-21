import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load version properties
val versionPropsFile = project.rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}

// Load local properties
val localProperties = Properties().apply {
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

// Version resolution. On EVERY compile (any build type, any machine, any Gradle task that will
// actually compile bytecode) both the build number and the patch are incremented:
//   - versionBuild  -> the Android versionCode. Monotonic; NEVER resets.
//   - versionPatch  -> the patch segment of the versionName. Increments each compile, but resets to
//                      0 when versionMinor was bumped since the last build (a new minor starts at .0).
//                      versionMinorLast tracks the minor we last built so that reset is automatic.
// True when the requested tasks will trigger real compilation — not a sync, `tasks`, `clean`,
// a `--dry-run`, or a diagnostic like `buildEnvironment`/`buildHealth`. Build verbs cover every
// entry point that transitively invokes a KotlinCompile / JavaCompile task on this project: the
// full android build lifecycle (assemble/bundle/install/package), explicit compile invocations,
// unit-test / instrumented-test / verification tasks (test/check/lint/verify/connectedTest — all
// depend on compileDebugKotlin / compileReleaseKotlin), and `run` for library modules. Verbs are
// matched as a prefix on the leaf task name and the `build` lifecycle task is matched exactly, so
// diagnostics that merely contain "build" don't trip it.
val startParameter = gradle.startParameter
val buildVerbs = listOf(
    "assemble", "bundle", "install", "package", "compile",
    "test", "check", "lint", "verify", "connected", "run",
)
val isBuilding = !startParameter.isDryRun && startParameter.taskNames.any { taskName ->
    val task = taskName.substringAfterLast(':').lowercase()
    task == "build" || buildVerbs.any { task.startsWith(it) }
}

val verMajor = versionProps.getProperty("versionMajor", "0")
val verMinor = versionProps.getProperty("versionMinor", "1")
// Detect a minor bump BEFORE the build-gated block so the reset also applies to CI/override builds
// (and IDE syncs), where the block is skipped: a new minor always reads as patch 0 even if the file
// still holds the previous minor's patch (it may not have been rewritten by a local build yet).
val lastMinor = versionProps.getProperty("versionMinorLast", verMinor)
val isMinorBumped = verMinor != lastMinor

var currentVersionCode = versionProps.getProperty("versionBuild", "1").toInt()
var currentPatch = if (isMinorBumped) 0 else versionProps.getProperty("versionPatch", "0").toInt()

if (isBuilding) {
    currentVersionCode++ // build never resets
    // A minor bump makes this build the new minor's .0; otherwise advance the patch.
    if (!isMinorBumped) currentPatch++

    versionProps.setProperty("versionBuild", currentVersionCode.toString())
    versionProps.setProperty("versionPatch", currentPatch.toString())
    versionProps.setProperty("versionMinorLast", verMinor)
    versionPropsFile.outputStream().use {
        versionProps.store(it, "Auto-incremented on compile")
    }
}

val currentVersionName = "$verMajor.$verMinor.$currentPatch"

/**
 * Dependencies are declared with literal coordinates rather than through the
 * version catalog. The template's catalog lives at a non-standard path and
 * assuming its alias names would produce a build that fails on resolution
 * rather than on anything meaningful. Move them into the catalog once its
 * contents are known.
 */
android {
    namespace = "com.hereliesaz.mcpserved"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.mcpserved"
        minSdk = 29
        targetSdk = 37
        versionCode = currentVersionCode
        versionName = currentVersionName

        // Crash auto-reporting: CrashUploadWorker files a GitHub issue containing the crash log, using
        // this token. It is read at BUILD time from the GH_TOKEN env var (the same as
        // settings.gradle.kts uses for the GitHub Packages maven repo), with a gradle-property
        // fallback. When neither is present (typical local dev) it stays empty and CrashUploadWorker
        // no-ops — so nothing breaks locally and no token is ever committed to Git.
        //
        // SECURITY: a non-empty token here is embedded in the shipped APK's BuildConfig and CAN be
        // extracted by anyone who decompiles the app. Use a FINE-GRAINED token scoped to ONLY
        // "Issues: write" on this single repo (HereLiesAz/GraffitiXR) — never a broad/classic PAT —
        // so that a leaked token can, at worst, open issues on this one repo.
        // GitHub tokens are [A-Za-z0-9_] only (ghp_* / github_pat_*), so no string escaping is needed.
        val crashReportToken = System.getenv("GH_TOKEN")
            ?: (project.findProperty("GH_TOKEN") as String?)
            ?: ""
        buildConfigField("String", "GH_TOKEN", "\"$crashReportToken\"")
    }

    // Release signing is a property of the project, not of each CI invocation. The keystore and
    // credentials come from the environment: CI decodes the base64 `KEYSTORE_RAW` secret to
    // app/keystore.jks and exports KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD (see the
    // release-apk / release-aab / merged-build workflows). KEYSTORE_FILE may override the path.
    //
    // When no keystore is present (local dev without the secrets) the "release" config is simply
    // not created — `findByName` then returns null below, so release builds stay unsigned and debug
    // builds keep the default debug key. Nothing breaks, and no plaintext credentials live in Git.
    val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val envKeyAlias = System.getenv("KEY_ALIAS")
    val envKeyPassword = System.getenv("KEY_PASSWORD")
    // Resolve KEYSTORE_FILE against the repo root so a relative CI path can't become app/app/...;
    // default to this module's keystore.jks. Enable signing only when the keystore AND all three
    // credentials are present, so a stray local keystore without env credentials still falls back
    // gracefully (configuration succeeds; the build doesn't blow up at execution on missing creds).
    val releaseKeystore = (System.getenv("KEYSTORE_FILE")?.let { rootProject.file(it) } ?: file("keystore.jks"))
        .takeIf {
            it.exists() &&
                !envKeystorePassword.isNullOrEmpty() &&
                !envKeyAlias.isNullOrEmpty() &&
                !envKeyPassword.isNullOrEmpty()
        }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Embedded HTTP server for the on-device MCP endpoint (McpServer). Tiny,
    // dependency-free, and pure Java — it handles HTTP/1.1 framing so the app
    // only implements the MCP JSON-RPC on top.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Full BouncyCastle. The platform ships a stripped build under the same
    // provider name; McpApplication swaps it out at startup.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")

    // QR generation and scanning for the pairing exchange.
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // ADB-level access on unrooted devices.
    compileOnly("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
