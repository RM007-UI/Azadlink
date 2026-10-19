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
    }
}
rootProject.name = "AzadLink"
include(":app", ":aether-vpn")
project(":aether-vpn").projectDir = file("oblivion-upstream/android/aether-vpn")
