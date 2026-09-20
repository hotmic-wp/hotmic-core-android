pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // HotMic Core comes from Maven Central. Internal development against an unreleased
        // build: `./gradlew :core:publishToMavenLocal` in hotmic-core-android-source, then
        // uncomment the next line (and match the version in Example/build.gradle.kts).
        // mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "hotmic-core-android"

include(":example")
project(":example").projectDir = file("Example")
