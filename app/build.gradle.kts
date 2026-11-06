import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 每次构建按时间生成新版本号，不需要手改：
// versionCode = 2025-01-01 起的秒数（单调递增，远小于上限）
// versionName = 1.0.yyMMdd.HHmmss
val buildStampMs = System.currentTimeMillis()
val buildVersionCode = ((buildStampMs - 1735689600000L) / 1000L).toInt()
val buildVersionName =
    "1.0." + SimpleDateFormat("yyMMdd.HHmmss", Locale.US).format(Date(buildStampMs))

android {
    namespace = "dev.local.typinglog"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.local.typinglog"
        minSdk = 26
        targetSdk = 34
        versionCode = buildVersionCode
        versionName = buildVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // APK 文件名也带上版本号：weibo-hot-v1.0.yyMMdd.HHmmss-debug.apk
    applicationVariants.all {
        val variantName = name
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "weibo-hot-v$buildVersionName-$variantName.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
