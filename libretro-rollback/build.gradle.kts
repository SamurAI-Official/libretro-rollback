plugins {
    alias(libs.plugins.multiplatform)
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        commonMain.dependencies {
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
    }
}

// The Kotlin Multiplatform plugin creates the `jvm` and `kotlinMultiplatform` publications when
// `maven-publish` is applied; no manual publication is needed.
