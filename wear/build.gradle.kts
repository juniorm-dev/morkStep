import org.gradle.api.plugins.BasePluginExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Name the Wear APK artifact after the app: morkStep-wear-debug.apk.
the<BasePluginExtension>().archivesName = "morkStep-wear"

android {
    namespace = "com.morkstep.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.morkstep.wear"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // Name APK artifacts with the app version: morkStep-wear-$versionName-$buildType.apk.
    applicationVariants.all {
        val v = versionName
        val t = buildType.name
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "morkStep-wear-$v-$t.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Live heart rate on the watch (Android Health Services).
    implementation("androidx.health:health-services-client:1.1.0-rc02")
    // Health Services exposes Guava ListenableFuture in its API surface.
    implementation("com.google.guava:guava:33.3.1-jre")
    // Relay heart rate to the paired phone over the Wearable data layer.
    implementation("com.google.android.gms:play-services-wearable:20.0.1")

    testImplementation("junit:junit:4.13.2")
}
