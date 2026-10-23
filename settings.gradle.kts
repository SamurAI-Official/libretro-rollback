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

rootProject.name = "libretro-rollback"

include(":libretro-rollback")
include(":libretro-rollback-libretro")
include(":demo")
