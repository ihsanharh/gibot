plugins {
    id("java")
    id("application")
    id("com.gradleup.shadow") version "9.2.2"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main/")
    maven("https://repo.opencollab.dev/maven-snapshots")
    maven("https://repo.opencollab.dev/maven-releases")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    implementation("org.cloudburstmc.protocol:bedrock-codec")
    implementation("org.cloudburstmc.protocol:common")
    implementation("org.cloudburstmc.protocol:bedrock-connection")

    implementation("com.github.kas-tle:MinecraftAuth:f3d6e3fbc8")
    implementation("org.bitbucket.b_c:jose4j:0.9.6")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.apache.logging.log4j:log4j-core:2.12.2")
    implementation("org.apache.logging.log4j:log4j-api:2.12.2")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.12.2")
    implementation("org.checkerframework:checker-qual:3.37.0")
}

group = "com.ihsanharh"

application {
    mainClass.set("com.ihsanharh.gibot.GiBot")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.register("executable") {
    dependsOn("shadowJar")
    doLast {
        val shadowJarTask = tasks.named<org.gradle.jvm.tasks.Jar>("shadowJar").get()
        val jarFile = shadowJarTask.archiveFile.get().asFile
        val execFile = file("$projectDir/gibot")
        val stub = "#!/bin/sh\nexec java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -jar \"\$0\" \"\$@\"\n".toByteArray()
        val jarBytes = jarFile.readBytes()
        execFile.writeBytes(stub + jarBytes)
        execFile.setExecutable(true, false)
    }
}

tasks.named("shadowJar") {
    finalizedBy("executable")
}
