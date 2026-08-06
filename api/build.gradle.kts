// Public API module: interfaces and events other plugins can integrate against.
// Deliberately has no runtime dependencies beyond Paper's API surface.

dependencies {
    compileOnly(libs.paper.api)
}

java {
    withJavadocJar()
    withSourcesJar()
}
