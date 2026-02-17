plugins {
    kotlin("jvm") version "1.9.21" apply false
    kotlin("plugin.serialization") version "1.9.21" apply false
    id("io.ktor.plugin") version "2.3.7" apply false
}

allprojects {
    group = "com.nocturnusai"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
