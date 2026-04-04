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
    // GraalVM native image: Ktor/coroutines classes that the metadata repository
    // marks as build-time-initialized call LoggerFactory.getLogger() in their static
    // initializers. slf4j-nop provides a concrete no-op SLF4J binding so that
    // LoggerFactory can initialize cleanly at build time (all loggers → NOP).
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

// Generate version.properties from Gradle project version
tasks.register("generateVersionProperties") {
    val outputDir = layout.buildDirectory.dir("generated-resources")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("version")
        dir.mkdirs()
        dir.resolve("version.properties").writeText("version=${project.version}\n")
    }
}

tasks.named<Copy>("processResources") {
    dependsOn("generateVersionProperties")
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated-resources"))
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("nocturnusai")
            mainClass.set("com.nocturnusai.cli.MainKt")

            buildArgs.addAll(
                "--no-fallback",
                "--enable-url-protocols=http,https",
                // SLF4J is called by Ktor/coroutines static initializers that the GraalVM
                // metadata repository marks as build-time-initialized. We must explicitly
                // allow SLF4J itself to initialize at build time too. With slf4j-nop on the
                // classpath (see dependencies above), the build-time LoggerFactory safely
                // binds to NOP — correct for a CLI tool that needs no application logging.
                "--initialize-at-build-time=org.slf4j",
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
