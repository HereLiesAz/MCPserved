pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // Compose Multiplatform's Gradle plugin and the Compose/Skiko runtime
        // artifacts for the desktop module.
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Shizuku is published here and nowhere else.
        maven("https://s01.oss.sonatype.org/content/repositories/releases")
        // Compose Multiplatform / Skiko runtime artifacts for the desktop module.
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "MCPserved"
include(":app")
include(":desktop")
