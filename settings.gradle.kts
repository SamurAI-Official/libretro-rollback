pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

// `-root` because the subproject below is the artifact: a root project and a subproject cannot share a name once
// Gradle's type-safe project accessors are on, which happens as soon as this build is part of a composite whose root
// enables them (the app does, through pokemon-client). Same convention as pokemon-client-root.
rootProject.name = "libretro-rollback-root"

include(":libretro-rollback")
include(":libretro-rollback-libretro")
include(":demo")
