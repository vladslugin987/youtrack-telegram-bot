plugins {
    kotlin("jvm") version "2.2.20"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.youtrack.bot"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    
    // Telegram Bot
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.3.0")
    
    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:5.2.1")
    
    // JSON
    implementation("com.google.code.gson:gson:2.13.1")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

application {
    mainClass.set("MainKt")
}

// Configure Shadow to build a fat jar
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
}
