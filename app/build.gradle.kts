plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jose.yotuve"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.jose.yotuve"
        minSdk = 26
        targetSdk = 35
        // GitHub pasa el número de compilación: así la app sabe si hay versión nueva
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = "1.$versionCode"
        // yt-dlp/ffmpeg traen binarios nativos para estas ABIs
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    // Firma fija: imprescindible para que las actualizaciones se instalen encima
    signingConfigs {
        create("yotuve") {
            storeFile = file("yotuve.jks"); storePassword = "yotuve123"
            keyAlias = "yotuve"; keyPassword = "yotuve123"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("yotuve") }
    }
    // Obligatorio para youtubedl-android: los binarios deben extraerse al instalar
    packaging { jniLibs { useLegacyPackaging = true } }
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a", "x86_64"); isUniversalApk = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // yt-dlp + python + ffmpeg empaquetados para Android
    val ytdl = "0.17.2"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    // Reproductor con caché
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-database:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-cast:$media3")          // Chromecast / Google TV
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.appcompat:appcompat:1.7.0")   // tema del diálogo de elegir TV

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
