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
        // HotMic Core is resolved from Maven Local for now. Publish it from the
        // private `hotmic-core-android-source` repo with `./gradlew :core:publishToMavenLocal`.
        // GitHub Packages (hotmic-wp/hotmic-android-sdk) distribution comes later.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "hotmic-core-android"

include(":example")
project(":example").projectDir = file("Example")
