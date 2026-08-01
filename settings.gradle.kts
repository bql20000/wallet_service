plugins {
    // Lets Gradle auto-download the JDK 21 toolchain when the machine only has
    // an older JDK installed, so `./gradlew bootRun` works without a manual install.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "wallet_service"
