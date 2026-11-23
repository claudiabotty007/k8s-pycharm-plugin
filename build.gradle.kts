plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = "dev.k8stools"
version = "2.3.3" // see CLAUDE.md for the versioning rules

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build against a locally installed IDE (see gradle.properties) to avoid a huge download.
        // To build against a downloaded PyCharm instead, replace with: pycharmCommunity("2025.2.6.1")
        local(providers.gradleProperty("ideDir"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Every buildPlugin also copies the plugin jar (the same jar that is inside the zip) into dist/
// (committed and pushed to main, see CLAUDE.md).
val copyJarToDist by tasks.registering(Copy::class) {
    from(tasks.named("composedJar"))
    into(layout.projectDirectory.dir("dist"))
}
tasks.buildPlugin { finalizedBy(copyJarToDist) }

// ./gradlew runIde -PopenProject=<path> opens that project in the sandbox IDE.
tasks.runIde {
    providers.gradleProperty("openProject").orNull?.let { args(it) }
}
