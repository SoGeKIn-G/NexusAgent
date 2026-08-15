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
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NexusAgent"

include(":app")

// Pure Kotlin. No Android dependency — unit-testable on the JVM without a device.
include(":core:model")
include(":core:ui")

include(":agent:perception")

// execution (M3), reasoning (M4), orchestrator (M5) - kept as packages inside one module
// rather than three, to keep Gradle configuration cost down on this machine.
include(":agent:runtime")

// Room-backed run history and the compression metrics it accumulates.
include(":core:data")
