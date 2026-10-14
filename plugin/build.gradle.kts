plugins {
    java
}

group = "ch.blugdeg"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Die genaue Paper-Version setzt der GitHub-Workflow (-PpaperVersion=...).
val paperVersion: String = (findProperty("paperVersion") as String?) ?: "26.3.+"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("plugin.yml") {
        expand("version" to v)
    }
}

tasks.jar {
    archiveFileName.set("Lifematic-Plugin-${project.version}.jar")
}
