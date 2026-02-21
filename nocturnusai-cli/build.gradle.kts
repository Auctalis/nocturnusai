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
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
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
                "--initialize-at-build-time=io.ktor",
                "--initialize-at-build-time=kotlinx.serialization",
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
