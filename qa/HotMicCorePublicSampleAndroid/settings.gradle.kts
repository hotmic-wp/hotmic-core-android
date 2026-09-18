// Written from hotmic-core-android/README.md "Installation" as an outside developer would:
// mavenLocal() first (Core is not on GitHub Packages yet), then google()/mavenCentral().
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
        mavenLocal() // HotMic Core. GitHub Packages (hotmic-wp/hotmic-android-sdk) coming later.
        google()
        mavenCentral()
    }
}

rootProject.name = "HotMicCorePublicSampleAndroid"
include(":app")
