package org.rigelmc;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Resolves RigelMCMod's runtime-only dependencies (HikariCP + JDBC drivers) at plugin
 * load time via Paper's {@link MavenLibraryResolver}, instead of shading them into the
 * jar. This is the concrete fix for the class of {@code NoClassDefFoundError} plugin-load
 * failure documented against TotalFreedomMod's hard, ad hoc-shaded dependencies: version
 * mismatches surface here as a clear resolution failure at load time rather than a
 * cryptic missing-class error deep in some feature's code path later.
 */
public final class RigelPluginLoader implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(
                new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build());

        resolver.addDependency(new Dependency(new DefaultArtifact("com.zaxxer:HikariCP:7.1.0"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("org.xerial:sqlite-jdbc:3.53.2.1"), null));
        resolver.addDependency(
                new Dependency(new DefaultArtifact("org.mariadb.jdbc:mariadb-java-client:3.5.10"), null));

        // Discord4J backs the optional discord/ module (off by default) - resolved here
        // rather than shaded, same as the JDBC drivers above, so the base plugin jar
        // stays small for the common case where the Discord bridge isn't used at all.
        // Only the top-level artifact needs declaring - MavenLibraryResolver (like Maven/
        // Gradle) resolves its full transitive dependency graph (Reactor, Jackson,
        // Netty, ...) automatically from its POM.
        resolver.addDependency(new Dependency(new DefaultArtifact("com.discord4j:discord4j-core:3.3.2"), null));

        classpathBuilder.addLibrary(resolver);
    }
}
