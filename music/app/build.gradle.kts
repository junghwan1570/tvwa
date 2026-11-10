plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.myeongwang.music"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.myeongwang.music"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // 한국어 글자만 넣음 (라이브러리의 다른 나라 말 글자는 뺌)
        resourceConfigurations += listOf("ko")
    }

    // 서명 키: 비밀값(ANDROID_KEYSTORE_B64)이 있으면 그 키로, 없으면 임시 키로 서명
    val ks = rootProject.file("release.keystore")
    val tempKs = rootProject.file("ci-temp.keystore")
    signingConfigs {
        create("release") {
            storeFile = ks
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = "myeongwang"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        }
        create("temp") {
            storeFile = tempKs
            storePassword = "android"
            keyAlias = "temp"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = when {
                ks.exists() -> signingConfigs.getByName("release")
                tempKs.exists() -> signingConfigs.getByName("temp")
                else -> signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
}
