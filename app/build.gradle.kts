plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.foxxd.toolbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.foxxd.toolbox"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 真机都是 arm64，雷电模拟器是 x86_64，其余 ABI 裁掉
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            // 用 debug 同一把钥匙签名：debug/release 可互相覆盖安装，不清数据
            //（个人分发工具，无需单独密钥库）
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
