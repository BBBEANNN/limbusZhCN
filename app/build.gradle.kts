import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localSigningPropertiesFile = rootProject.file("signing/keystore.properties")
val localSigningProperties = Properties().apply {
    if (localSigningPropertiesFile.isFile) {
        localSigningPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.example.limbuszhcn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.limbuszhcn"
        // 与当前 Limbus Company 本体保持一致，覆盖 Android 8.0 及以上仍在使用的常见机型。
        // VirtualApp 库自身最低支持 API 23，但应用使用了 java.time，因此安全下限设为 API 26。
        minSdk = 26
        targetSdk = 35
        // v1.3 以下载汉化包为唯一译名来源，并优先保证中文字体完整、清晰、不重叠。
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (localSigningPropertiesFile.isFile) {
            create("localDebug") {
                storeFile = localSigningPropertiesFile.parentFile.resolve(
                    localSigningProperties.getProperty("storeFile")
                )
                storePassword = localSigningProperties.getProperty("storePassword")
                keyAlias = localSigningProperties.getProperty("keyAlias")
                keyPassword = localSigningProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            // AppSealing 会根据 DEBUGGABLE/JDWP 状态切换保护路径；调试变体保留日志和
            // ADB 入口即可，不能让平台把容器进程当作可调试应用，否则真机行为会偏离生产环境。
            isDebuggable = false
            signingConfigs.findByName("localDebug")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(project(":virtualapp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    testImplementation(libs.junit)
    testImplementation("com.google.android.gms:play-services-tasks:18.2.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
