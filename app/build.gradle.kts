plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.fileflash"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "app.fileflash"
        minSdk = 29
        targetSdk = 37
        versionCode = 11
        versionName = "0.1.7"
    }
    buildFeatures { compose = true }
    val releaseKey = rootProject.file(".signing/fileflash.p12")
    val releasePassword = rootProject.file(".signing/password.txt")
    if (releaseKey.exists() && releasePassword.exists()) {
        signingConfigs.create("localRelease") {
            storeFile = releaseKey
            storePassword = releasePassword.readText().trim()
            keyAlias = "fileflash"
            keyPassword = releasePassword.readText().trim()
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKey.exists() && releasePassword.exists()) signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
