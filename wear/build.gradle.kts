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
        // "-0.2.0" (or stacked "-0.2.0-0.2.0", or an older version) marks an APK
        // already versioned by an earlier run. Never touch it — re-renaming stacks
        // the suffix every build, and deleting it breaks AGP's up-to-date check
        // (packageDebug would not regenerate it). Only the pristine package
        // output gets moved to the versioned name.
        val versionedTail = Regex("(-\\d+(\\.\\d+)*)+$")
        apkFiles.files.forEach { src ->
            val suffix = when {
                src.name.endsWith("-unsigned.apk") -> "-unsigned.apk"
                src.name.endsWith("-signed.apk") -> "-signed.apk"
                else -> ".apk"
            }
            val base = src.name.removeSuffix(suffix)
            if (versionedTail.containsMatchIn(base)) return@forEach
            val dst = src.parentFile.resolve("$base-$v$suffix")
            // Purge stale outputs for this base (older versions, stacked names)
            // so only the current version remains. Only runs when a pristine base
            // is present, i.e. whenever packageDebug actually re-packaged.
            src.parentFile.listFiles()?.forEach { stale ->
                val n = stale.name
                if (n == src.name || n == dst.name) return@forEach
                val sameKind = when {
                    suffix == ".apk" -> n.endsWith(".apk") &&
                        !n.endsWith("-unsigned.apk") && !n.endsWith("-signed.apk")
                    else -> n.endsWith(suffix)
                }
                if (sameKind && n.startsWith("$base-")) stale.delete()
            }
            // Copy, never move: AGP and connected tests consume the pristine
            // package output by its unversioned path, so it must stay in place.
            src.copyTo(dst, overwrite = true)
        }
        // Guarantee the unversioned artifact is always present too: packaging
        // leaves its pristine output in place, but if it is gone for any
        // reason, restore it from the current versioned copy so both names
        // exist after every build that created APKs.
        apkFiles.files.forEach { src ->
            val suffix = when {
                src.name.endsWith("-unsigned.apk") -> "-unsigned.apk"
                src.name.endsWith("-signed.apk") -> "-signed.apk"
                else -> ".apk"
            }
            val base = src.name.removeSuffix(suffix)
            if (base.endsWith("-$v") && versionedTail.containsMatchIn(base)) {
                val stem = versionedTail.replaceFirst(base, "")
                val pristine = src.parentFile.resolve("$stem$suffix")
                if (!pristine.exists()) src.copyTo(pristine)
            }
        }
    }
}

plugins {
    id("com.android.application")
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
        versionCode = 11
        versionName = "0.8.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        // Disable system animations so Compose UI assertions aren't racing transitions.
        animationsDisabled = true
    }

    buildTypes {
        release {
            // R8 strips the unused Guava/health-services classes that dominate
            // the unminified APK; keep only the app's own tiny package intact.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // No R8: debuggable builds disable optimizations anyway, and stripping
            // unused classes (e.g. kotlin.LazyKt the test platform needs) breaks
            // instrumented tests. Keep debug installable-size fast instead.
            isMinifyEnabled = false
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
    // Not added automatically here (no kotlin plugin wiring; AGP built-in Kotlin).
    // Without it R8 strips stdlib from the app dex and the test dex lacks it.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
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

    // Required by createComposeRule() for the graphics-panel instrumented tests.
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Compose ui-test 1.7.x pulls espresso-core 3.5.1, which crashes on
    // Android 15/16 images (InputManager.getInstance NoSuchMethodException).
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    // Test dex must carry stdlib itself (see implementation above).
    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
}