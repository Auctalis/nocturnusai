plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("org.graalvm.buildtools.native")
}

application {
    mainClass.set("com.nocturnusai.cli.MainKt")
}

dependencies {
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("nocturnusai")
            mainClass.set("com.nocturnusai.cli.MainKt")

            buildArgs.addAll(
                // --no-fallback: fail the build if native image cannot be fully compiled
                // (rather than silently falling back to JVM mode)
                "--no-fallback",
                "--enable-url-protocols=http,https",
                // All initialization config (kotlin, ktor, coroutines, serialization, slf4j)
                // is handled by the GraalVM Reachability Metadata Repository below.
                // Adding manual --initialize-at-build-time flags causes transitive SLF4J
                // initialization errors with GraalVM 21 and is therefore omitted.
            )

            // Reduce binary size in production; strip debug info
            buildArgs.add(
                if (project.hasProperty("nativeRelease")) "-O2" else "-O1"
            )
        }
    }

    // Pull pre-built GraalVM reachability metadata for Ktor, kotlinx, etc.
    // This covers CIO engine, ContentNegotiation, SLF4J, etc. automatically.
    metadataRepository {
        enabled = true
    }
}
