pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven { url=uri("https://jitpack.io") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url=uri("https://jitpack.io") }

    }
}

rootProject.name = "我的电视"

include(":core:data")
include(":core:util")
include(":core:designsystem")
include(":tv")
include(":mobile")

val mediaSettingsFile = file("../media/core_settings.gradle")
if (mediaSettingsFile.exists()) {
    (gradle as ExtensionAware).extra["androidxMediaModulePrefix"] = "media3:"
    apply(from = mediaSettingsFile)
}
