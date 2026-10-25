plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.myeongwang.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.myeongwang.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // 서명 키: GitHub 비밀값(ANDROID_KEYSTORE_B64)이 있으면 그 키로, 없으면 임시(디버그) 키로 서명
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

    // full = 기존 앱(NAS 사용) · pub = 공개판(공개 주소만, 유튜브는 본인 API 키) — 서로 다른 앱이라 같이 설치 가능
    buildFeatures { buildConfig = true }
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition" }
        create("pub") {
            dimension = "edition"
            applicationIdSuffix = ".pub"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = when { ks.exists() -> signingConfigs.getByName("release"); tempKs.exists() -> signingConfigs.getByName("temp"); else -> signingConfigs.getByName("debug") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

// 저장소 맨 위의 index.html(명왕사신TV 화면)을 앱에 넣음
val copyWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../index.html"))
    into(layout.projectDirectory.dir("src/main/assets/web"))
}
// 공개판: 개인 NAS 주소를 지운 화면을 넣음 (pub 폴더가 main보다 우선)
val copyWebPub by tasks.registering(Copy::class) {
    from(rootProject.file("../index.html"))
    into(layout.projectDirectory.dir("src/pub/assets/web"))
    filter { line: String -> line.replace("https://junghwan1570.synology.me:8443", "") }
}
tasks.named("preBuild") { dependsOn(copyWeb, copyWebPub) }

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
}
