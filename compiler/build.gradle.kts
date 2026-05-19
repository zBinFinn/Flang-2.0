plugins {
    kotlin("jvm")
    java
    antlr
    idea
}

group = "com.zbinfinn"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    antlr("org.antlr:antlr4:4.13.2")
    implementation("org.antlr:antlr4-runtime:4.13.2")
    implementation(libs.kotlinxSerialization)
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.generateGrammarSource {
    maxHeapSize = "128m"
    arguments.addAll(
        listOf(
            "-package",
            "com.zbinfinn",
            "-visitor",
            "-no-listener",
        ),
    )
}

sourceSets {
    named("main") {
        java.srcDir(layout.buildDirectory.dir("generated-src/antlr/main"))
    }
}

tasks.named("compileJava") {
    dependsOn(tasks.generateGrammarSource)
}
tasks.named("compileKotlin") {
    dependsOn(tasks.generateGrammarSource)
}

tasks.test {
    useJUnitPlatform()
}

idea {
    module {
        sourceDirs.add(file(layout.buildDirectory.dir("generated-src/antlr/main").get()))
    }
}
