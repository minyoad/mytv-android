import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import java.io.FileInputStream
import java.util.Properties

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false

    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

 val keystorePropertiesFile = rootProject.file("key.properties")
 val keystoreProperties = Properties()
 if (keystorePropertiesFile.exists()) {
     keystoreProperties.load(FileInputStream(keystorePropertiesFile))
 }

allprojects {
    val appConfig: BaseAppModuleExtension.() -> Unit = {
         signingConfigs {
             val localKeystore = rootProject.file("keystore.jks")
             val userKeystore = file(
                 System.getenv("RELEASE_STORE_FILE") ?: keystoreProperties.getProperty("storeFile")
                 ?: "keystore.jks"
             )

             create("release") {
                 storeFile = if (userKeystore.exists()) userKeystore else localKeystore
                 storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                     ?: keystoreProperties.getProperty("storePassword")
                 keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
                 keyPassword =
                     System.getenv("RELEASE_KEY_PASSWORD") ?: keystoreProperties.getProperty("keyPassword")
             }
         }

        applicationVariants.all {
            outputs.all {
                val ver = defaultConfig.versionName
                val minSdk =
                    project.extensions.getByType(BaseAppModuleExtension::class.java).defaultConfig.minSdk
                val abi = filters.find { it.filterType == "ABI" }?.identifier ?: "all"
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "mytv-android-${project.name}-$ver-${abi}-sdk$minSdk.apk"
            }
        }
    }

    extra["appConfig"] = appConfig
}