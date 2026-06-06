pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LibreChat-Mobile"

include(":app")
include(":core:common")
include(":core:logging")
include(":core:model")
include(":core:network")
include(":core:data")
include(":core:ui")
include(":feature:auth")
include(":feature:chat")
include(":feature:conversations")
include(":feature:settings")
include(":feature:agents")
include(":feature:files")
include(":feature:skills")
include(":shared")
