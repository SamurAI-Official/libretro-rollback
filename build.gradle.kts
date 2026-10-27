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

    // Where the engine is published for everything that consumes it: `dev.kotlinds:libretro-rollback` is not on Maven
    // Central (upstream has no such artifact), so the fork publishes it to GitHub Packages — the app's build resolves
    // it from there instead of a local `publishToMavenLocal` (see .github/workflows/publish.yml). Publishing needs a
    // token with `packages: write`; reading needs one with `read:packages`.
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension>("publishing") {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/SamurAI-Official/libretro-rollback")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user")?.toString()
                        password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key")?.toString()
                    }
                }
            }
        }
    }
}
