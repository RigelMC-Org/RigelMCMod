import xyz.jpenilla.runpaper.task.RunServer

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

// Produces RigelMCMod-<version>.jar instead of the default plugin-<version>.jar (which
// would otherwise come from this subproject's directory name).
base {
    archivesName.set("RigelMCMod")
}

dependencies {
    compileOnly(libs.paper.api)
    implementation(project(":api"))

    // Not shaded in - RigelPluginLoader resolves these at plugin-load time via
    // MavenLibraryResolver instead, so they're declared here only to compile against.
    compileOnly(libs.hikaricp)
    compileOnly(libs.sqlite.jdbc)
    compileOnly(libs.mariadb.jdbc)
    compileOnly(libs.discord4j)

    // Also compileOnly, but NOT resolved via RigelPluginLoader/MavenLibraryResolver like
    // the four above - PacketEvents is a soft *server plugin* dependency instead
    // (paper-plugin.yml's server.dependencies block), matching TFM's own approach. See
    // gradle/libs.versions.toml's comment on this entry and protect.crash.packet for why.
    compileOnly(libs.packetevents)

    // Same soft *server plugin* dependency shape as PacketEvents above, for the same
    // reason: WorldEdit/FAWE are shared, singleton-shaped editing infrastructure, and a
    // privately-resolved second copy risks classloader/registration conflicts with the
    // real installed plugin. isTransitive = false matches TFM's own exact dependency
    // declaration (confirmed by reading its build.gradle directly) - worldedit-core's own
    // dependency graph is irrelevant to a compileOnly consumer. See
    // protect.worldedit.extent for the actual integration.
    compileOnly(libs.worldedit.bukkit) { isTransitive = false }
    compileOnly(libs.worldedit.core) { isTransitive = false }

    // EssentialsX is a hard requirement for this project (unlike everything else
    // compileOnly above/below, all soft/optional) - compiled directly against for
    // world.EssentialsWarpBridge's small, stable warp-removal API surface. See
    // gradle/libs.versions.toml's comment on this entry. isTransitive = false for the
    // same reason as worldedit-bukkit/worldedit-core above: EssentialsX's own POM pulls
    // in an old pinned org.spigotmc:spigot-api that conflicts with (and is unnecessary
    // next to) our own paper-api - the actual installed Essentials plugin jar already
    // provides everything it needs at runtime, a compileOnly consumer only needs
    // EssentialsX's own classes to compile against.
    compileOnly(libs.essentialsx) { isTransitive = false }

    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.hikaricp)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Only the api module's classes get merged in; third-party libraries are
    // injected at runtime by RigelPluginLoader instead of being shaded here.
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<RunServer>().configureEach {
    minecraftVersion("26.1.2")
}
