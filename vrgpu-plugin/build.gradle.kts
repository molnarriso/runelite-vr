plugins {
    java
}

// Defaults for these live in gradle.properties; override with -P to build against
// a different client. See README.md.
val runeliteVersion = providers.gradleProperty("runeliteVersion").get()
val lwjglVersion = providers.gradleProperty("lwjglVersion").get()

// Set by the release workflow from the git tag. Local builds are "dev".
val pluginVersion = providers.gradleProperty("pluginVersion").getOrElse("dev")

val lombokVersion = "1.18.30"

// Dependencies that are NOT present in the host client and must therefore be
// packed into the plugin jar. Kept non-transitive on purpose: pulling in
// lwjgl-core here would give the JVM a second, separate copy of lwjgl and the
// plugin would no longer share a GL context with the host. See README.md.
val bundled: Configuration by configurations.creating {
    isTransitive = false
}

configurations.compileOnly.get().extendsFrom(bundled)

dependencies {
    // Provided by the host client at runtime. Brings lwjgl core/opengl, rlawt,
    // guice, guava etc. transitively.
    compileOnly("net.runelite:client:$runeliteVersion")

    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    bundled(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    bundled("org.lwjgl:lwjgl-openxr:$lwjglVersion")
    bundled("org.lwjgl:lwjgl-openxr:$lwjglVersion:natives-windows")
}

// Build straight out of the fork's tree rather than copying 19k lines, so there
// is only ever one copy of the source.
sourceSets {
    main {
        java {
            setSrcDirs(listOf("../runelite-client/src/main/java"))
            include("net/runelite/client/plugins/vrgpu/**")
        }
        resources {
            setSrcDirs(listOf("../runelite-client/src/main/resources"))
            include("net/runelite/client/plugins/vrgpu/**")
            exclude("**/.clang-format")
        }
    }
}

tasks.compileJava {
    options.release = 11
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(bundled.map { if (it.isDirectory) it else zipTree(it) })

    exclude(
        "META-INF/INDEX.LIST",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/MANIFEST.MF",
        "**/module-info.class"
    )

    // Name carries both versions: which build it is, and which client it targets.
    archiveFileName = "vrgpu-$pluginVersion-rl$runeliteVersion.jar"
}
