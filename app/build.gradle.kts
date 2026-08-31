import java.util.Properties

import org.gradle.api.plugins.BasePluginExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Android SDK root (local.properties is machine-specific and gitignored). Used
// by exportLspClasspath to put the platform jar on the language-server classpath.
private val sdkDir: String? = run {
    val f = rootProject.file("local.properties")
    if (!f.exists()) {
        null
    } else {
        val props = Properties()
        f.inputStream().use { props.load(it) }
        props.getProperty("sdk.dir")?.replace('\\', '/')
    }
}

// Read release signing credentials from the gitignored keystore.properties
// (storeFile, storePassword, keyAlias, keyPassword). If it is missing or
// incomplete the release stays unsigned, so builds never hard-fail on a secret.
fun releaseSigning(): Pair<Properties, Boolean> {
    val props = Properties()
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    val ok = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { props.getProperty(it).orEmpty().isNotBlank() }
    return props to ok
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Name the APK artifacts after the app: morkStep-debug.apk / morkStep-release.apk.
the<BasePluginExtension>().archivesName = "morkStep"

android {
    val (signProps, haveSigning) = releaseSigning()

    namespace = "com.morkstep"
    compileSdk = 36
    signingConfigs {
        create("release") {
            if (haveSigning) {
                storeFile = rootProject.file(signProps.getProperty("storeFile"))
                storePassword = signProps.getProperty("storePassword")
                keyAlias = signProps.getProperty("keyAlias")
                keyPassword = signProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.morkstep"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (haveSigning) signingConfig = signingConfigs.getByName("release")
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
    // Name APK artifacts with the app version: morkStep-$versionName-$buildType.apk.
    applicationVariants.all {
        val v = versionName
        val t = buildType.name
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "morkStep-$v-$t.apk"
        }
    }
}

// kotlin-language-server project config: export the app + unit-test compile
// classpath (plus the Android platform jar) to $ROOT/.classpath.absolute, the
// file org.javacs.kt.MainKt reads at startup. Rerun after dependency changes;
// the file itself is machine-specific and gitignored.
tasks.register("exportLspClasspath") {
    doLast {
        val androidJar = sdkDir?.let { "$it/platforms/android-${android.compileSdk ?: 36}/android.jar" }
        // AGP's compile classpath carries the full library jars; the unit-test
        // Kotlin task adds junit/coroutines-test on top for test sources.
        val mainCp = configurations.getByName("debugCompileClasspath")
        val testCp = tasks.named<KotlinCompile>("compileDebugUnitTestKotlin").get().libraries
        val entries = (mainCp.files + testCp.files)
            .map { it.absolutePath }
            .plus(listOfNotNull(androidJar))
            .distinct()
            .sorted()
        rootProject.file(".classpath.absolute").writeText(
            "morkstep-app\n${entries.joinToString("\n")}\n"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Receive live heart rate relayed from the morkStep Wear companion app.
    implementation("com.google.android.gms:play-services-wearable:20.0.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}