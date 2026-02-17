pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "nocturnusai"
include("nocturnusai-core")
include("nocturnusai-server")
include("nocturnusai-cli")
