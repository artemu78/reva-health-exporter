import java.util.Properties

plugins {
    id("com.android.application")
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val releaseSigningValues = mapOf(
    "storeFile" to providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull,
    "storePassword" to providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull,
    "keyAlias" to providers.environmentVariable("ANDROID_KEY_ALIAS").orNull,
    "keyPassword" to providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull,
)
val releaseSigningReady = releaseSigningValues.values.all { !it.isNullOrBlank() }

android {
    namespace = "dev.reva.healthexporter"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "dev.reva.healthexporter"
        minSdk = 30
        targetSdk = 36
        versionCode = versionProperties.getProperty("VERSION_CODE").toInt()
        versionName = versionProperties.getProperty("VERSION_NAME")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = file(checkNotNull(releaseSigningValues["storeFile"]))
                storePassword = releaseSigningValues["storePassword"]
                keyAlias = releaseSigningValues["keyAlias"]
                keyPassword = releaseSigningValues["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("com.google.code.gson:gson:2.13.2")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.work:work-testing:2.10.0")
    testImplementation("androidx.health.connect:connect-testing:1.0.0-alpha03") {
        exclude(group = "androidx.health.connect", module = "connect-client")
        exclude(group = "androidx.health.connect", module = "connect-client-proto")
    }

    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.work:work-testing:2.10.0")
}
