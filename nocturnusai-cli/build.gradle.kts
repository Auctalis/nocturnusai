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
                "--no-fallback",
                "--initialize-at-build-time=kotlin",
                "--initialize-at-build-time=kotlinx.coroutines",
                // Note: io.ktor is NOT listed here — the GraalVM Reachability Metadata
                // Repository (enabled below) handles Ktor's initialization config.
                // Adding --initialize-at-build-time=io.ktor causes SLF4J LoggerFactory to
                // be initialized at image-build time, which GraalVM 21 rejects.
                "--initialize-at-build-time=kotlinx.serialization",
                "--initialize-at-run-time=org.slf4j",
                "--enable-url-protocols=http,https",
                "-H:+InstallExitHandlers",
                "-H:+ReportUnsupportedElementsAtRuntime",
                // Reduces binary size — we don't need the Truffle/polyglot stack
                "-H:-IncludeAllTimeZones",
                // Allow missing reflection registrations to degrade gracefully in dev
                "-H:+PrintClassInitialization",
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
