// Patched floors for transitive libraries on the build's own plugin classpath
// (pulled in by the Android Gradle plugin and friends, not shipped in the app).
// Dependabot reports them against settings.gradle.kts; the plugins pin older
// versions, so raise them here. `strictly` would fail the build if a plugin ever
// needed something older; `require` just lifts the floor.
buildscript {
    dependencies {
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.85") // GHSA-574f / 9pwp / qp49
            classpath("org.bouncycastle:bcpkix-jdk18on:1.85") // GHSA-wg6q (needs >= 1.84)
            classpath("org.jdom:jdom2:2.0.6.1")               // GHSA-2363 (XXE)
            classpath("com.google.guava:guava:33.7.2-jre")    // GHSA-xxph
            classpath("org.bitbucket.b_c:jose4j:0.9.6")       // GHSA-3677
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.jetbrains.compose) apply false
}

// Same floors for the project's own configurations (lint tool, unit-test
// runtime, Robolectric's classpath), which the buildscript constraints above
// don't reach. Only raises; anything already newer is left alone.
allprojects {
    val patchedFloors = mapOf(
        "org.bouncycastle:bcprov-jdk18on" to "1.85",
        "org.bouncycastle:bcpkix-jdk18on" to "1.85",
        "org.jdom:jdom2" to "2.0.6.1",
        "com.google.guava:guava" to "33.7.2-jre",
        "org.bitbucket.b_c:jose4j" to "0.9.6",
    )
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            patchedFloors["${requested.group}:${requested.name}"]?.let { useVersion(it) }
        }
    }
}
