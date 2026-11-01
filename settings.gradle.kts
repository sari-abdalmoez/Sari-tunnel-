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
        google()
        mavenCentral()
        // Locally built Xray AAR (scripts/build-xray-aar.sh writes app/libs/xray.aar).
        flatDir { dirs(file("app/libs")) }
    }
}

rootProject.name = "SariVPN"
include(":app")
