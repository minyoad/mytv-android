import com.android.build.gradle.internal.dsl.BaseAppModuleExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    @Suppress("UNCHECKED_CAST")
    apply(extra["appConfig"] as BaseAppModuleExtension.() -> Unit)

    namespace = "top.yogiczy.mytv.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "top.yogiczy.mytv.tv"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "2.2.8"
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

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
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

    // 播放器
    val mediaSettingsFile = file("../../media/core_settings.gradle")
    if (mediaSettingsFile.exists()) {
        implementation(project(":media3:lib-exoplayer"))
        implementation(project(":media3:lib-exoplayer-hls"))
        implementation(project(":media3:lib-exoplayer-rtsp"))
    } else {
        implementation(libs.androidx.media3.exoplayer)
        implementation(libs.androidx.media3.exoplayer.hls)
        implementation(libs.androidx.media3.exoplayer.rtsp)
    }

//    implementation ("tv.danmaku.ijk.media:ijkplayer-java:0.8.8")
//    implementation ("tv.danmaku.ijk.media:ijkplayer-armv7a:0.8.8")
//    implementation ("tv.danmaku.ijk.media:ijkplayer-arm64:0.8.8")
//    implementation ("tv.danmaku.ijk.media:ijkplayer-x86:0.8.8")
//    implementation ("tv.danmaku.ijk.media:ijkplayer-x86_64:0.8.8")

    implementation("com.github.CarGuo:GSYIjkJava:1.0.0")

    //根据你的需求ijk模式的so
//    implementation("com.github.CarGuo.GSYVideoPlayer:gsyVideoPlayer-arm64:v8.6.0-release-jitpack")
    implementation("com.github.CarGuo.GSYVideoPlayer:gsyVideoPlayer-armv7a:v8.6.0-release-jitpack")
    implementation("com.github.CarGuo.GSYVideoPlayer:gsyVideoPlayer-armv5:v8.6.0-release-jitpack")
    implementation("com.github.CarGuo.GSYVideoPlayer:gsyVideoPlayer-x86:v8.6.0-release-jitpack")
    implementation("com.github.CarGuo.GSYVideoPlayer:gsyVideoPlayer-x64:v8.6.0-release-jitpack")

    // 二维码
    implementation(libs.qrose)

    implementation(libs.coil.compose)

    implementation(libs.okhttp)
    implementation(libs.androidasync)

    implementation("com.google.code.gson:gson:2.8.9")

    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:util"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}