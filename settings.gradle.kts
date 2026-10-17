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

rootProject.name = "NasFinder"
include(":app")
include(":hanai-onnx")
project(":hanai-onnx").projectDir = file("../HanAI/android/hanai-onnx")
