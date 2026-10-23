plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
}

allprojects {
    group = "dev.kotlinds"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}
