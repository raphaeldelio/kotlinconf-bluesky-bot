plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "kotlinconf-bsky-bot"
include("consumer-app")
include("filtering-app")
include("topic-extraction-app")
include("kotlin-notebooks")