import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation = configurations.getByName("integrationTestImplementation") {
    extendsFrom(configurations.getByName("testImplementation"))
}

val integrationTestRuntimeOnly = configurations.getByName("integrationTestRuntimeOnly") {
    extendsFrom(configurations.getByName("testRuntimeOnly"))
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.opentest4j:opentest4j:1.3.0")
    integrationTestImplementation(platform("org.junit:junit-bom:5.10.3"))
    integrationTestImplementation("org.junit.jupiter:junit-jupiter")
    integrationTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
    // Match Starter's runtime dependency for its documented DI customization seam.
    integrationTestImplementation("org.kodein.di:kodein-di-jvm:7.26.1")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
    integrationTestRuntimeOnly(kotlin("reflect"))
    integrationTestRuntimeOnly(kotlin("stdlib"))

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        val changelog = project.changelog // local variable for configuration cache compatibility
        // Use released notes when available, otherwise the Unreleased section.
        changeNotes = providers.gradleProperty("version").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

tasks {
    publishPlugin {
        dependsOn(patchChangelog)
    }
}

val integrationTest = intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath

        systemProperty("path.to.build.plugin", tasks.prepareSandbox.get().pluginDirectory.get().asFile)
        systemProperty("platformVersion", providers.gradleProperty("platformVersion").get())
        timeout = Duration.ofMinutes(15)
        useJUnitPlatform()
        dependsOn(tasks.prepareSandbox)

        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}
