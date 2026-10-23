plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":libretro-rollback-libretro"))
}

application {
    mainClass.set("dev.kotlinds.libretrorollback.demo.MainKt")
}
