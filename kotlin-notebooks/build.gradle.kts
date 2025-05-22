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
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.3")

    implementation("redis.clients:jedis:6.0.0")

    implementation("org.springframework.ai:spring-ai-transformers:1.0.0")
    implementation("org.springframework.ai:spring-ai-ollama:1.0.0")
    implementation("org.springframework.ai:spring-ai-redis-store:1.0.0")

    implementation("ai.djl.huggingface:tokenizers:0.33.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}