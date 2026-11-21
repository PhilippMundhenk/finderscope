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

rootProject.name = "finderscope"
include(":core")
// The Android module needs the Android SDK; FINDERSCOPE_JVM_ONLY skips it (JVM-only CI job, quick local runs).
if (file("app").isDirectory && System.getenv("FINDERSCOPE_JVM_ONLY") == null) include(":app")
