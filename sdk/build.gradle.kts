plugins {
    kotlin("jvm")
}

group = "com.flashcache"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":protocol"))
}
