import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName).orNull ?: keystoreProps.getProperty(propertyName)

val uploadStoreFile = signingValue("ANDROID_KEYSTORE_FILE", "storeFile")
val uploadStorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val uploadKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val uploadKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val signingValues = listOf(uploadStoreFile, uploadStorePassword, uploadKeyAlias, uploadKeyPassword)
val hasReleaseSigning = signingValues.all { !it.isNullOrBlank() }
require(signingValues.all { it == null } || hasReleaseSigning) {
    "Release signing is incomplete. Supply all four ANDROID_KEYSTORE_* / ANDROID_KEY_* values or keystore.properties."
}
if (providers.gradleProperty("requireReleaseSigning").orNull == "true") {
    require(hasReleaseSigning) { "A release upload key is required for this build." }
}
if (hasReleaseSigning) {
    require(file(uploadStoreFile!!).isFile) { "The release keystore file does not exist." }
}
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.let { value ->
    requireNotNull(value.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }) {
        "releaseVersionCode must be an integer between 1 and 2100000000."
    }
} ?: 1
val releaseVersionName = providers.gradleProperty("releaseVersionName").orElse("1.0").get()
require(releaseVersionName.isNotBlank() && releaseVersionName.length <= 100 &&
    releaseVersionName.none { it.isISOControl() }) { "Invalid releaseVersionName." }

android {
    namespace = "de.vorleser"
    compileSdk = 36
    defaultConfig {
        applicationId = "de.vorleser.app"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = file(uploadStoreFile!!)
            storePassword = uploadStorePassword
            keyAlias = uploadKeyAlias
            keyPassword = uploadKeyPassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.camera:camera-core:1.5.3")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
