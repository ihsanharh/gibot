rootProject.name = "gibot"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version ("1.0.0")
}

includeBuild("protocol")
includeBuild("network") {
    dependencySubstitution {
        substitute(module("org.cloudburstmc.netty:netty-transport-nethernet")).using(project(":transport-nethernet"))
        substitute(module("org.cloudburstmc.netty:netty-transport-raknet")).using(project(":transport-raknet"))
    }
}
