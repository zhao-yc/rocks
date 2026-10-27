plugins {
    id("com.android.application") version "9.1.1"
    kotlin("android") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

android {
    namespace = "org.rocks.geckodemo"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.rocks.browser.geckodemo"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0-gecko"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    packaging { jniLibs.useLegacyPackaging = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
            signingConfig = if (privateKeyPath == null) null else signingConfigs.getByName("rocksDevelopment")
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// A whitelist prevents the original WebView UI/resources from entering this application.
val sharedBrowserSources = tasks.register<Sync>("sharedBrowserSources") {
    from("../app/src/main/java") {
        include("org/rocks/browser/SessionCodec.kt", "org/rocks/browser/SessionRepository.kt", "org/rocks/browser/AtomicSessionFile.kt")
    }
    into(layout.buildDirectory.dir("generated/shared-browser/main"))
}
val sharedBrowserTests = tasks.register<Sync>("sharedBrowserTests") {
    from("../app/src/test/java") {
        include("org/rocks/browser/SessionCodecTest.kt", "org/rocks/browser/SessionRepositoryTest.kt")
    }
    into(layout.buildDirectory.dir("generated/shared-browser/test"))
}
kotlin.sourceSets.getByName("main").kotlin.srcDir(sharedBrowserSources.map { it.destinationDir })
kotlin.sourceSets.getByName("test").kotlin.srcDir(sharedBrowserTests.map { it.destinationDir })
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(sharedBrowserSources, sharedBrowserTests)
}

dependencies {
    implementation(project(":core"))
    implementation("org.mozilla.geckoview:geckoview:158.0.20261021001945")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
