pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KeePassPN"
include(":app")
include(":database")
include(":crypto")
include(":chroma")
include(":icon-pack")
include(":icon-pack:classic")
include(":icon-pack:material")
