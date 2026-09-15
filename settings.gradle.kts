// 国内镜像优先（阿里云），官方仓库兜底。
// 官方仓库(dl.google.com / repo.maven.apache.org)在国内偶发 TLS 握手中断，
// 镜像在前可显著降低 IDE 同步 / 依赖解析失败概率。
// 注意：pluginManagement 块为独立预执行段，无法引用脚本顶层变量，故此处直接内联地址。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "我的电视"

include(":core:data")
include(":core:util")
include(":core:designsystem")
include(":tv")
//include(":mobile")

// media3 采用预编译 AAR（tv/libs/media3-*.aar，由本地 fork 仓库构建导出），
// 不再以源码模块 include 进构建树，保证工程自包含。
include(":ijkplayer-java")
