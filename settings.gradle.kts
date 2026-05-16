pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.0.21"
    }
}

rootProject.name = "flashcache"

include("protocol", "sdk", "server", "router")
