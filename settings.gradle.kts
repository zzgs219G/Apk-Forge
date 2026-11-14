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

// ── 本地 JDK 21 定位（仅本机生效，CI 不干扰）──────────────────
// 为什么不在 gradle.properties 写 org.gradle.java.home：
// 那个文件只能写常量路径，而 Termux 的 JDK 路径
// /data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
// 在 CNB / GitHub Actions 容器里不存在，Gradle 会直接以
// "Specified Java home ... does not exist" 失败。
// 这里按「目录真的存在才设置」，本机与 CI 各自走对的分支。
val localJdkHome = "/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk"
if (file(localJdkHome).isDirectory) {
    System.setProperty("org.gradle.java.home", localJdkHome)
}

include(":app")
