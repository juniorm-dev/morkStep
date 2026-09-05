import java.util.Properties

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
        // "-0.5.0" (or stacked "-0.5.0-0.5.0", or an older version) marks an APK
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

// Fails the release build when a git tag for the current version already
// exists. Releases are tagged v<versionName>, so a second build of the same
// version means versionCode/versionName were not bumped — exactly the mistake
// that shipped a 0.10.1 package under the v0.10.2 tag. Run before packaging a
// release variant so the package name can never drift from the release tag.
abstract class VerifyVersionTag : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @TaskAction
    fun run() {
        val v = version.get()
        val tag = "v$v"
        val existing = ProcessBuilder("git", "tag", "-l", tag)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().use { it.readText() }
            .trim()
        if (existing == tag) {
            throw GradleException(
                "Version $v is already released (tag $tag exists on this repo). " +
                    "Bump versionCode/versionName in app/build.gradle.kts before " +
                    "building a release package."
            )
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
        versionCode = 20
        versionName = "0.12.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Instrumented (emulator) tests start from a clean app state: no leftover
        // profiles or history, so assertions are deterministic. These tests are
        // explicitly excluded from assemble/test — run them on demand only.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }
    testOptions {
        // Disable system animations so Compose UI assertions aren't racing transitions.
        animationsDisabled = true
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
        // Guard release packaging against shipping a version that was already
        // tagged (see VerifyVersionTag). Debug builds are not gated so day-to-day
        // iteration is unaffected.
        if (variant.buildType == "release") {
            val verify = project.tasks.register("verify${pkgName}Version", VerifyVersionTag::class.java) {
                this.version.set(version)
            }
            tasks.matching { it.name == "package$pkgName" }.configureEach {
                dependsOn(verify)
            }
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
    // Post-workout heart-rate backfill when the Wear relay is off: read
    // average/min/max HR for the workout window from Health Connect.
    implementation("androidx.health.connect:connect-client:1.1.0")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Compose ui-test 1.7.x pulls espresso-core 3.5.1, which crashes on
    // Android 15/16 images (InputManager.getInstance NoSuchMethodException).
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}