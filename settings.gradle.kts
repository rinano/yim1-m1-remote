// YI M1 Remote — Gradle settings.
//
// The repository order below intentionally mirrors the sibling project
// (android/settings.gradle.kts) whose modules are already present in the shared
// offline Gradle cache (android/toolchain/.gradle-home). Gradle keys its cached
// module metadata by repository, so changing the order/URLs here can turn a
// working `--offline` build into an unresolvable one.
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "YI-M1-Remote"
include(":app")
