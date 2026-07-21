import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // ARM only, matching :core:nativebridge. Keeps the OpenCV Prefab prebuilts (which ship
            // all four ABIs) from packaging x86 .so files that would have no matching libgraffitixr.so.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        // Crash auto-reporting: CrashUploadWorker files a GitHub issue containing the crash log, using
        // this token. It is read at BUILD time from the GH_TOKEN env var (the same one
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
            // Null (unsigned) only when no keystore was supplied — e.g. a local build without secrets.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // The auto-published CI build (merged-build.yml) assembles the debug variant. Sign it with
            // the RELEASE key when available so its signature stays stable across builds (in-place
            // updates keep working); fall back to the default debug key for local development.
            signingConfig = signingConfigs.findByName("release") ?: signingConfig
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21

    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    // App Bundle configuration. These splits are ON by default for an AAB; we set
    // them explicitly so the modular-delivery intent is documented in the build.
    // Play generates optimized per-device APKs from `bundleRelease`, so the large
    // per-ABI native payload (:core:nativebridge / OpenCV / LiteRT NPU runtimes)
    // is only downloaded for the device's actual ABI — no separate artifacts to
    // build. See docs/RELEASE.md.
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }
}


tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val version = variant.outputs.first().versionName.get()
            val code = variant.outputs.first().versionCode.get()
            val apkName = "MCPserved-${variant.name}-$version.$code.apk"
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(apkName)
        }
    }
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Embedded HTTP server for the on-device MCP endpoint (McpServer). Tiny,
    // dependency-free, and pure Java — it handles HTTP/1.1 framing so the app
    // only implements the MCP JSON-RPC on top.
    implementation(libs.nanohttpd)

    // Full BouncyCastle. The platform ships a stripped build under the same
    // provider name; McpApplication swaps it out at startup.
    implementation(libs.bouncycastle)

    // QR generation and scanning for the pairing exchange.
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    // ADB-level access on unrooted devices.
    compileOnly(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
