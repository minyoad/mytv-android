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

val mediaSettingsFile = file("../media/core_settings.gradle")
if (mediaSettingsFile.exists()) {
    // 本地 media3 源码（E:\myproject\media）与宿主构建树集成所需的 glue 变量。
    // 注意：media3 构建脚本（如 lib-common）内部按“无前缀”模块名解析 project(':lib-xxx')，
    // 因此模块必须 include 在顶层（:lib-*），不能加 media3: 前缀，也不设置 androidxMediaModulePrefix。
    (gradle as ExtensionAware).extra["androidxMediaSettingsDir"] = file("../media").absolutePath

    // 与 media 官方 core_settings.gradle 相同的模块清单，但 projectDir 直接指向真实目录
    // （Gradle 9 要求 project 目录必须存在）。
    fun media3(module: String, dir: String) {
        include(":$module")
        project(":$module").projectDir = file("../media/libraries/$dir")
    }
    media3("lib-common", "common")
    media3("lib-common-ktx", "common_ktx")
    media3("lib-container", "container")
    media3("lib-session", "session")
    media3("lib-exoplayer", "exoplayer")
    media3("lib-exoplayer-dash", "exoplayer_dash")
    media3("lib-exoplayer-hls", "exoplayer_hls")
    media3("lib-exoplayer-rtsp", "exoplayer_rtsp")
    media3("lib-exoplayer-smoothstreaming", "exoplayer_smoothstreaming")
    media3("lib-exoplayer-ima", "exoplayer_ima")
    media3("lib-exoplayer-workmanager", "exoplayer_workmanager")
    media3("lib-ui", "ui")
    media3("lib-ui-danmaku", "ui_danmaku")
    media3("lib-ui-leanback", "ui_leanback")
    media3("lib-ui-compose", "ui_compose")
    media3("lib-ui-compose-material3", "ui_compose_material3")
    media3("lib-database", "database")
    media3("lib-datasource", "datasource")
    media3("lib-datasource-cronet", "datasource_cronet")
    media3("lib-datasource-rtmp", "datasource_rtmp")
    media3("lib-datasource-okhttp", "datasource_okhttp")
    media3("lib-decoder", "decoder")
    media3("lib-decoder-av1", "decoder_av1")
    media3("lib-decoder-ffmpeg", "decoder_ffmpeg")
    media3("lib-decoder-flac", "decoder_flac")
    media3("lib-decoder-iamf", "decoder_iamf")
    media3("lib-decoder-mpegh", "decoder_mpegh")
    media3("lib-decoder-opus", "decoder_opus")
    media3("lib-decoder-vp9", "decoder_vp9")
    media3("lib-extractor", "extractor")
    media3("lib-cast", "cast")
    media3("lib-effect", "effect")
    media3("lib-effect-lottie", "effect_lottie")
    media3("lib-inspector", "inspector")
    media3("lib-inspector-frame", "inspector_frame")
    media3("lib-muxer", "muxer")
    media3("lib-transformer", "transformer")
    media3("test-utils-robolectric", "test_utils_robolectric")
    media3("test-data", "test_data")
    media3("test-utils", "test_utils")
}
include(":ijkplayer-java")
