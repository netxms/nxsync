plugins {
    kotlin("jvm") version "2.2.0"
    id("com.gradleup.shadow") version "8.3.9"
}

val netxmsVersion = "6.2.1"

group = "org.netxms"
version = netxmsVersion

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.netxms:netxms-client:$netxmsVersion")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.netxms.sync.MainKt"
    }
}
