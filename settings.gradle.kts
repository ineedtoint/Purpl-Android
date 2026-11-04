pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Purpl-Android"
include(":purpl")
if (file("purpl-ui/build.gradle.kts").exists()) include(":purpl-ui")
if (file("sample/build.gradle.kts").exists()) include(":sample")
