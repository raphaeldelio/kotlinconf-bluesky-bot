plugins {
    kotlin("jvm")
}

group = "dev.raphaeldelio"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-cio:3.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("redis.clients:jedis:6.0.0")
    implementation("org.springframework.ai:spring-ai-ollama:1.0.0-RC1")
    implementation("org.springframework.ai:spring-ai-openai:1.0.0-RC1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}