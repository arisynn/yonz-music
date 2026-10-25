import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.chaquo.python")
}
val ownerSigning = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
android {
    namespace = "com.yonzmusic.app"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.yonzmusic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("testVersionCode").map(String::toInt).getOrElse(2)
        versionName = "1.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (ownerSigning.isNotEmpty()) create("ownerRelease") {
            storeFile = file(ownerSigning.getProperty("storeFile"))
            storePassword = ownerSigning.getProperty("storePassword")
            keyAlias = ownerSigning.getProperty("keyAlias")
            keyPassword = ownerSigning.getProperty("keyPassword")
            enableV2Signing = true; enableV3Signing = true
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".dev"; versionNameSuffix = "-dev" }
        create("migration") {
            initWith(getByName("debug"))
            applicationIdSuffix = ""
            versionNameSuffix = "-migration"
            matchingFallbacks += listOf("debug")
        }
        release {
            signingConfig = signingConfigs.findByName("ownerRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/quickjs"))
    packaging { jniLibs.useLegacyPackaging = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = true; checkReleaseBuilds = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
val buildQuickJs = tasks.register<Exec>("buildQuickJs") {
    inputs.dir(rootProject.file("native"))
    inputs.file(rootProject.file("tools/native/build_quickjs.py"))
    outputs.dir(layout.buildDirectory.dir("generated/quickjs"))
    commandLine("python3", rootProject.file("tools/native/build_quickjs.py"), "--sdk", android.sdkDirectory,
        "--output", layout.buildDirectory.dir("generated/quickjs").get().asFile)
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildQuickJs) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython("python3")
        pip {
            install("yt-dlp==2026.10.21")
            install("yt-dlp-ejs==0.8.0")
            install("certifi==2026.10.19")
            install("mutagen==1.48.1")
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("com.android.tools.build:apksig:8.9.1")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
