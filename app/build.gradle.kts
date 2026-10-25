plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "Com.hau.name"
    compileSdk = 34

    defaultConfig {
        // ID MOI (khac "com.hau.truyentep" cu) -> Android coi la 1 app hoan toan moi, o canh app cu.
        applicationId = "com.hau.dktv"
        minSdk = 24
        targetSdk = 34
        // versionCode tang dan theo so lan build tren GitHub Actions -> ban sau luon
        // cao hon ban truoc, cai de len duoc (cap nhat) ma khong can go app cu.
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "2.0.$runNumber"
    }

    // Ky bang 1 keystore CO DINH (lay tu GitHub Secrets) - bat buoc de cac ban build
    // khac nhau cai de len nhau duoc. Khong co keystore (build may local) thi dung
    // khoa debug mac dinh.
    val ksFile = file("release.keystore")
    if (ksFile.exists()) {
        signingConfigs {
            create("fixed") {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "truyentep"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
        if (ksFile.exists()) {
            getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
            getByName("release") { signingConfig = signingConfigs.getByName("fixed") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Firebase — signaling WebRTC
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-database-ktx")

    // WebRTC
    implementation("io.github.webrtc-sdk:android:125.6422.06.1")


}
