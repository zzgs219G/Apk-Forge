// 仓库配置：本地/国内环境优先走阿里云镜像，其次官方源。
// 与参考工程 sx_rjk 保持一致。
// 注意：--offline 构建时 Gradle 依赖仓库标识匹配本地缓存元数据，
// 若这里与缓存时使用的仓库不一致，会报 "plugin was not found"。
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ApkForge"

include(":app")
