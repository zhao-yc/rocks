plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.rocks.browser"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.rocks.browser"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.2-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    val privateKeyPath = providers.environmentVariable("ROCKS_ANDROID_KEYSTORE").orNull
    if (privateKeyPath != null) {
        signingConfigs.create("rocksDevelopment") {
            storeFile = file(privateKeyPath)
            storeType = "PKCS12"
            storePassword = providers.environmentVariable("ROCKS_ANDROID_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ROCKS_ANDROID_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ROCKS_ANDROID_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        getByName("debug") {
            // 缺少私钥只产出未签名中间包，绝不自动改用临时调试密钥。
            signingConfig = if (privateKeyPath == null) null else signingConfigs.getByName("rocksDevelopment")
        }
        release { isMinifyEnabled = false }
    }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
