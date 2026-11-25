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
        // Kadb's SPAKE2 (com.github.Flyfish233:spake2-java) is published only there.
        maven("https://jitpack.io") { content { includeGroup("com.github.Flyfish233") } }
    }
}
rootProject.name = "iBridge"
include(":app")
