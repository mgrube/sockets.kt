import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties
import java.io.FileInputStream
import org.gradle.api.tasks.bundling.Jar

plugins {
    kotlin("jvm") version "1.2.60"
    `maven-publish`
}

group = "fr.rhaz"
version = "3.0.2"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib-jdk8"))
    compile("com.google.code.gson:gson:2.8.5")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

val jar by tasks.getting(Jar::class) {
    destinationDir = file("$rootDir/jar")
    from(configurations.runtime.map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
}

java.sourceSets {
    get("main").java.srcDirs("src")
    get("test").java.srcDirs("test")
}

publishing {
    repositories {
        maven {
            val mavenwrite by System.getProperties()
            url = uri(mavenwrite)
        }
    }

    publications {
        val mavenJava by creating(MavenPublication::class) {
            from(components["java"])
        }
    }
}