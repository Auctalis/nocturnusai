plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
    id("org.owasp.dependencycheck")
    application
}

dependencyCheck {
    // Fail build on CVSS >= 7 (HIGH/CRITICAL)
    failBuildOnCVSS = 7.0f
    suppressionFile = rootProject.file("owasp-suppressions.xml").absolutePath

    analyzers {
        // Sonatype OSS Index requires a paid account — disable it
        ossIndexEnabled = false
        // Assembly Analyzer requires .NET runtime — not available in CI or on macOS
        assemblyEnabled = false
        // Disable analyzers irrelevant to a JVM-only project
        nodeEnabled = false
        nodeAuditEnabled = false
        nuspecEnabled = false
        rubygemsEnabled = false
        pyPackageEnabled = false
        pyDistributionEnabled = false
        golangDepEnabled = false
        golangModEnabled = false
        composerEnabled = false
        swiftEnabled = false
    }
}

application {
    mainClass.set("com.nocturnusai.server.ApplicationKt")
}

tasks.named<JavaExec>("run") {
    // Load .env file from project root if present
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
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
    from(rootProject.file("USERGUIDE.md"))
    dependsOn("generateVersionProperties")
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated-resources"))
}

// Point STORAGE_DIR at a per-build temp directory so tests never read stale data
// written by previous runs and never pollute the working-tree data/ directory.
// The directory is deleted and recreated at the start of each test run so that
// every test class gets a completely fresh DatabaseManager with no on-disk state.
tasks.named<Test>("test") {
    val tmpDir = layout.buildDirectory.dir("test-data").get().asFile
    doFirst {
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
    }
    environment("STORAGE_DIR", tmpDir.absolutePath)
}

dependencies {
    implementation(project(":nocturnusai-core"))
    implementation("io.ktor:ktor-server-core:2.3.13")
    implementation("io.ktor:ktor-server-netty:2.3.13")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
    implementation("io.ktor:ktor-server-cors:2.3.13")
    implementation("io.ktor:ktor-server-status-pages:2.3.13")
    implementation("io.ktor:ktor-network-tls-certificates:2.3.13")
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-server-call-logging:2.3.13")
    implementation("io.ktor:ktor-server-websockets:2.3.13")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-tests-jvm:2.3.13")
    implementation(kotlin("reflect"))
    implementation("io.ktor:ktor-server-metrics-micrometer:2.3.13")
    implementation("io.micrometer:micrometer-registry-prometheus:1.10.3")
}
