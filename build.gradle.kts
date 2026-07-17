plugins {
    kotlin("jvm") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.netxms"
version = "5.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.netxms:netxms-client:5.1.5")
    implementation("com.xenomachina:kotlin-argparser:2.0.7")
    implementation("org.slf4j:slf4j-simple:2.0.12")
//    implementation("ch.qos.logback:logback-classic:1.4.14")
//    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")

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
        attributes["Main-Class"] = "org.netxms.sync.AppKt"
    }
}
