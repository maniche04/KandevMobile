import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Release signing comes from ~/.forge/signing.properties, or the file named by FORGE_SIGNING_PROPERTIES (CI). Never committed.
val signingFile = System.getenv("FORGE_SIGNING_PROPERTIES")?.takeIf { it.isNotBlank() }?.let(::File)
    ?.also { require(it.isFile) { "FORGE_SIGNING_PROPERTIES names a missing file: $it" } }
    ?: File(System.getProperty("user.home"), ".forge/signing.properties")
val signing = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }
// In-app update directory: FORGE_UPDATE_URL (the release workflow sets the repository's GitHub Releases), else url=...
// from ~/.forge/update.properties; blank means the user sets it in Settings.
val updateFile = File(System.getProperty("user.home"), ".forge/update.properties")
val updateUrl = System.getenv("FORGE_UPDATE_URL")?.takeIf { it.isNotBlank() }
    ?: Properties().apply { if (updateFile.isFile) updateFile.inputStream().use { load(it) } }.getProperty("url", "")

android {
    namespace = "ca.mslab.forge"
    compileSdk = 36
    defaultConfig {
        applicationId = "ca.mslab.forge"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.3.4"
        buildConfigField("String", "UPDATE_URL", "\"${updateUrl.replace("\\", "").replace("\"", "")}\"")
    }
    signingConfigs {
        if (signing.isNotEmpty()) create("release") {
            storeFile = File(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-dev" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("licenses"))
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.unifiedpush.android:connector:3.3.5")
    implementation("com.google.firebase:firebase-messaging:26.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Firebase is optional. Only generate resources for packages present in the local config.
val firebaseConfig = file("google-services.json")
for ((variant, packageName) in mapOf("Debug" to "ca.mslab.forge.debug", "Release" to "ca.mslab.forge")) {
    val configured = firebaseConfig.isFile && firebaseConfig.readText().contains("\"$packageName\"")
    tasks.matching { it.name == "process${variant}GoogleServices" }.configureEach { onlyIf { configured } }
}
