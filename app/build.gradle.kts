import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val localSigning = Properties().apply {
    val propertiesFile = rootProject.file("signing.local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}
android {
    namespace = "app.phantom"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "app.phantom"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.10"
        ndk { abiFilters += providers.gradleProperty("phantomAbi").orNull?.split(",") ?: listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
    signingConfigs {
        if(localSigning.isNotEmpty()) create("personal") {
            storeFile=rootProject.file(localSigning.getProperty("storeFile"))
            storePassword=localSigning.getProperty("storePassword")
            keyAlias=localSigning.getProperty("keyAlias")
            keyPassword=localSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            if (providers.gradleProperty("phantomSideBySide").orNull == "true") applicationIdSuffix = ".verification"
        }
        release { isMinifyEnabled = false;if(localSigning.isNotEmpty()) signingConfig=signingConfigs.getByName("personal") }
    }
}
dependencyLocking { lockAllConfigurations() }
dependencies {
    implementation(files("libs/libXray.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
