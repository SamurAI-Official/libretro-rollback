plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":libretro-rollback"))
    api(libs.libretro.kmp)
    testImplementation(libs.kotlin.test)
}
