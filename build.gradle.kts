plugins {
    java
    alias(libs.plugins.spotbugs) apply false
}

allprojects {
    group = "org.rigelmc"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") // Paper API
        maven("https://repo.codemc.io/repository/maven-public/") // PacketEvents - compileOnly, see protect.crash.packet
        maven("https://maven.enginehub.org/repo/") // WorldEdit - compileOnly, see protect.worldedit.extent
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "checkstyle")
    apply(plugin = "com.github.spotbugs")

    java {
        toolchain {
            // RigelMCMod targets Paper 26.1.2, which requires JDK 25 to compile against.
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    configure<CheckstyleExtension> {
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        toolVersion = "10.20.2"
        isIgnoreFailures = false
        maxWarnings = 0
    }

    configure<com.github.spotbugs.snom.SpotBugsExtension> {
        effort = com.github.spotbugs.snom.Effort.MAX
        reportLevel = com.github.spotbugs.snom.Confidence.HIGH
        ignoreFailures = false
    }

    tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
        reports.create("html") { required.set(true) }
    }
}
