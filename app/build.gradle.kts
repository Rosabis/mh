plugins {
    id("com.android.application") version "9.2.0"
}

android {
    namespace = "com.webtoon.fold"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.webtoon.fold"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            // 个人模块：用 debug 证书签名，产物可以直接安装。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Xposed / LSPosed API (compile-only; provided by the framework at runtime)
    compileOnly("de.robv.android.xposed:api:82")
}
