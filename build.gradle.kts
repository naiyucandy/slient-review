plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.silentreview"
val localPlatformPath = providers.gradleProperty("localPlatformPath").orNull
val localBuildBranch = localPlatformPath?.let {
    Regex("\"buildNumber\"\\s*:\\s*\"(\\d+)\\.")
        .find(file("$it/product-info.json").readText())?.groupValues?.get(1)?.toInt()
        ?: error("Cannot read the local IntelliJ SDK build number")
}
// Compile against the oldest supported platform so new IDE APIs cannot silently
// raise the requirements of the single distribution.
require(localBuildBranch == null || localBuildBranch == 241) {
    "Build the distribution with an IntelliJ 2024.1 SDK; use verificationIdePaths to check newer IDEs"
}
version = "1.1.5"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localPath = localPlatformPath
        if (localPath != null) local(localPath) else intellijIdeaCommunity("2024.1")
        bundledPlugin("Git4Idea")
    }
    compileOnly("com.google.code.gson:gson:2.10.1")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = sourceCompatibility
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}
tasks.test { useJUnitPlatform() }

intellijPlatform {
    pluginConfiguration {
        name = "Silent Review"
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        providers.gradleProperty("pluginVerifierPath").orNull?.let { cliPath = file(it) }
        // All verification IDEs are supplied locally. Keep checks reproducible
        // without fetching a web-maintained list of ignored API problems.
        freeArgs = listOf("-offline")
        ides {
            val paths = providers.gradleProperty("verificationIdePaths").orNull
            if (paths.isNullOrBlank()) current()
            else paths.split(File.pathSeparatorChar).filter(String::isNotBlank).forEach { local(file(it)) }
        }
    }
}
