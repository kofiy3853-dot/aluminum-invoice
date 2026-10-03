plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val extractorBaseUrl = providers.gradleProperty("extractorBaseUrl")
    .orElse("http://10.0.2.2:8081")
    .get()
val extractorAccessToken = providers.gradleProperty("extractorAccessToken")
    .orElse(providers.environmentVariable("EXTRACTOR_ACCESS_TOKEN"))
    .orElse("")
    .get()

android {
    namespace = "com.whoknowsventures.quotes"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.whoknowsventures.quotes"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "EXTRACTOR_BASE_URL", "\"$extractorBaseUrl\"")
        buildConfigField("String", "EXTRACTOR_ACCESS_TOKEN", "\"$extractorAccessToken\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    signingConfigs {
        create("localDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("localDebug") }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
