plugins {
    kotlin("jvm") version "1.9.21" apply false
    kotlin("plugin.serialization") version "1.9.21" apply false
    id("io.ktor.plugin") version "2.3.13" apply false
    id("org.owasp.dependencycheck") version "12.1.0" apply false
    id("org.graalvm.buildtools.native") version "0.10.3" apply false
}

allprojects {
    group = "com.nocturnusai"
    version = "0.3.4"

    repositories {
        mavenCentral()
    }

    // Force Netty to latest patched version across all transitive deps
    // Fixes: CVE-2025-24970, CVE-2025-25193, CVE-2025-55163, CVE-2025-58056, CVE-2025-58057
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.netty") {
                useVersion("4.1.121.Final")
                because("Force Netty upgrade to fix multiple 2024/2025 CVEs")
            }
        }
    }
}
