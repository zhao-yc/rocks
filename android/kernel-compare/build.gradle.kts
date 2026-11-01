plugins { id("com.android.application") version "9.1.1" }

val testAbi = providers.gradleProperty("kernelTestAbi").orElse("arm64-v8a").get()
require(testAbi in setOf("arm64-v8a", "x86_64")) { "只允许手机或独立模拟器测试架构" }

android {
    namespace = "org.rocks.kernelcompare"
    compileSdk = 37
    buildFeatures { resValues = true }
    defaultConfig {
        applicationId = "org.rocks.browser.kerneltest"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-kernel-test"
    }
    flavorDimensions += "engine"
    productFlavors {
        create("chromium") {
            dimension = "engine"
            applicationIdSuffix = ".chromium"
            resValue("string", "app_name", "内核对照·Chromium")
        }
        create("gecko") {
            dimension = "engine"
            applicationIdSuffix = ".firefox"
            resValue("string", "app_name", "内核对照·Firefox")
            ndk { abiFilters += testAbi }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = true }
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
            // 复用已有固定证书；缺少私钥时只构建未签名中间包。
            signingConfig = if (privateKeyPath == null) null else signingConfigs.getByName("rocksDevelopment")
        }
    }
}

dependencies {
    "geckoImplementation"("org.mozilla.geckoview:geckoview:158.0.20261021001945")
    testImplementation("junit:junit:4.13.2")
}
