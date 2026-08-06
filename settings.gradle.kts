plugins {
    // Auto-provisions JDK toolchains (e.g. JDK 25) even if the machine
    // running Gradle only has an older JDK on PATH.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "RigelMCMod"

include(":api")
include(":plugin")
