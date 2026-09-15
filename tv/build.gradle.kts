import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "top.yogiczy.mytv.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            val localKeystore = rootProject.file("keystore.jks")
            val userKeystore = file(
                System.getenv("RELEASE_STORE_FILE")
                    ?: keystoreProperties.getProperty("storeFile")
                    ?: "keystore.jks"
            )

            storeFile = if (userKeystore.exists()) userKeystore else localKeystore
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                ?: keystoreProperties.getProperty("keyAlias")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                ?: keystoreProperties.getProperty("keyPassword")
        }
    }

    defaultConfig {
        applicationId = "top.yogiczy.mytv.tv"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 27
        versionName = "2.6.0-beta3"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 自定义 APK 输出文件名（AGP 8）：
    // mytv-android-tv-<versionName>-all-sdk<minSdk>.apk
    // 例：mytv-android-tv-2.6.0-beta-all-sdk23.apk
    applicationVariants.all {
        val variantVersionName = versionName ?: "unknown"
        val minSdk = defaultConfig.minSdk ?: 0
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "mytv-android-tv-${variantVersionName}-all-sdk${minSdk}.apk"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

//    splits {
//        abi {
//            isEnable = true
//            isUniversalApk = false
//            reset()
//            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
//        }
//    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation.base)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.material.icons.extended)

    // 播放器：media3 由本地 fork 仓库导出为预编译 AAR（tv/libs/media3-*.aar），
    // 经下方 fileTree("libs") 统一引入；工程不再依赖外部源码或 Maven 坐标。
    // AAR 无 POM，以下为其缺省的传递运行时依赖，需宿主显式声明：
    //  - lib-common api 依赖 Guava（EventLogger 等运行时用到 Joiner/Optional），
    //    exclude 与 media 仓库 libraries/common/build.gradle 保持一致
    implementation("com.google.guava:guava:33.3.1-android") {
        // 与 media 仓库 libraries/common/build.gradle 的 exclude 保持一致：
        // 排除 Guava 仅编译期使用、却声明为 runtime 的注解依赖
        exclude(group = "com.google.code.findbugs", module = "jsr305")
        exclude(group = "org.checkerframework", module = "checker-compat-qual")
        exclude(group = "org.checkerframework", module = "checker-qual")
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
        exclude(group = "com.google.j2objc", module = "j2objc-annotations")
        exclude(group = "org.codehaus.mojo", module = "animal-sniffer-annotations")
    }
    //  - lib-extractor 的文本编码探测依赖
    implementation("com.googlecode.juniversalchardet:juniversalchardet:1.0.3")
    //  - lib-datasource-rtmp 依赖的 LibRtmp Client（RTMP 源播放）
    implementation("io.antmedia:rtmp-client:3.2.0")

    // 二维码
    implementation(libs.qrose)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    implementation(libs.okhttp)
    implementation(libs.androidasync)

    implementation("com.google.code.gson:gson:2.10.1")

    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:util"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation(project(":ijkplayer-java"))

    implementation(libs.androidx.profileinstaller)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
