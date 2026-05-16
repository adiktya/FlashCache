plugins {
    kotlin("jvm")
    application
}

group = "com.flashcache"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.flashcache.server.FlashCacheServer")
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":sdk"))
    implementation("io.netty:netty-handler:4.1.115.Final")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    manifest {
        attributes("Main-Class" to "com.flashcache.server.FlashCacheServer")
    }
}

tasks.named("build") {
    dependsOn("fatJar")
}
