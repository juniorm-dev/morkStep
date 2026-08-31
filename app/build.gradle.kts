import java.util.Properties

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

// Renames every packaged APK to a version-numbered name, preserving a
// "-unsigned"/"-signed" suffix. Input is globbed so it also matches the
// unsigned release artifact (morkStep-release-unsigned.apk).
abstract class VersionApk : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:InputFiles
    abstract val apkFiles: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val v = version.get()
        apkFiles.files.forEach { src ->
            val suffix = when {
                src.name.endsWith("-unsigned.apk") -> "-unsigned.apk"
                src.name.endsWith("-signed.apk") -> "-signed.apk"
                else -> ".apk"
            }
            val base = src.name.removeSuffix(suffix)
            val dst = src.parentFile.resolve("$base-$v$suffix")
            if (dst.exists()) dst.delete()
            if (!src.renameTo(dst)) {
                src.copyTo(dst, overwrite = true)
                src.delete()
            }
        }
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
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
androidComponents {
    onVariants { variant ->
        val base = the<BasePluginExtension>().archivesName.get()
        val type = variant.buildType ?: "debug"
        val pkgName = variant.name.replaceFirstChar { it.uppercase() }
        val apkDir = layout.buildDirectory.dir("outputs/apk/${variant.name}")
        val version = variant.outputs.first().versionName.orNull ?: "0"
        val rename = project.tasks.register("rename${pkgName}Apk", VersionApk::class.java) {
            apkFiles.from(apkDir.map { it.asFileTree.matching { include("*.apk") } })
            this.version.set(version)
        }
        tasks.matching { it.name == "package$pkgName" }.configureEach {
            finalizedBy(rename)
        }
        tasks.matching { it.name == "create${pkgName}ApkListingFileRedirect" }.configureEach {
            mustRunAfter(rename)
        }
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

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}