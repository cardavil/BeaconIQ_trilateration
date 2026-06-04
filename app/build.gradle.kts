import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Endpoint + auth token are read from the untracked local.properties so they
// are not baked into version-controlled source. Fresh checkouts must add:
//   beaconiq.endpoint=<Apps Script /exec URL>
//   beaconiq.token=<shared secret>
// (absent values fall back to empty strings — uploads will fail until set).
val beaconiqProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val beaconiqEndpoint: String = beaconiqProps.getProperty("beaconiq.endpoint", "")
val beaconiqToken: String = beaconiqProps.getProperty("beaconiq.token", "")

android {
    namespace = "beaconiq"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "beaconiq.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ENDPOINT_URL", "\"$beaconiqEndpoint\"")
        buildConfigField("String", "AUTH_TOKEN", "\"$beaconiqToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.altbeacon)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    testImplementation(libs.ext.junit)
    testImplementation(libs.assertj.core)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}