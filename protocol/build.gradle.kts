plugins {
    kotlin("jvm")
}

group = "com.flashcache"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
}
