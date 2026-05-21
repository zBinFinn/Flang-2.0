import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.file.DuplicatesStrategy

plugins {
    kotlin("jvm")
    alias(libs.plugins.intellijPlatform)
}

group = "com.zbinfinn"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":compiler"))
    implementation("org.antlr:antlr4-runtime:4.13.2")
    testImplementation(kotlin("test"))

    intellijPlatform {
        intellijIdea("2026.1.1")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}

intellijPlatform {
    pluginConfiguration {
        id = "com.zbinfinn.flang"
        name = "Flang"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "261"
        }
    }
}
