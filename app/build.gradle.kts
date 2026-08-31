import java.util.Properties
import java.util.zip.ZipFile

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

// kotlin-language-server project config. `exportLspClasspath` regenerates both
// machine-local files the server needs without committing machine paths:
//   - $ROOT/.classpath.absolute — the app + unit-test compile classpath (plus
//     the Android platform jar) that org.javacs.kt.MainKt reads at startup;
//   - $ROOT/.omp/lsp.json — harness wiring that spawns java.exe directly with
//     that classpath (a .bat cannot inherit a pipe stdin on Windows, so a
//     script launcher would exit instantly). Both are gitignored; rerun the
//     task after dependency changes or on a fresh checkout.
//
// AAR entries cannot be indexed by kotlin-lsp (it reads jars/class dirs only),
// so each .aar's classes.jar is extracted into app/build/lsp — deterministic,
// regenerable output that keeps the exported classpath self-contained.
tasks.register("exportLspClasspath") {
    doLast {
        val androidJar = sdkDir?.let { "$it/platforms/android-${android.compileSdk ?: 36}/android.jar" }
        // AGP's compile classpath carries the full library jars; the unit-test
        // Kotlin task adds junit/coroutines-test on top for test sources.
        val mainCp = configurations.getByName("debugCompileClasspath")
        val testCp = tasks.named<KotlinCompile>("compileDebugUnitTestKotlin").get().libraries
        val aarOut = layout.buildDirectory.dir("lsp").get().asFile.apply { mkdirs() }

        fun entryFor(f: File): String = when {
            f.extension == "aar" -> {
                val target = File(aarOut, f.nameWithoutExtension + ".jar")
                if (!target.exists() || f.lastModified() > target.lastModified()) {
                    ZipFile(f).use { zf ->
                        val e = zf.getEntry("classes.jar") ?: return@use
                        zf.getInputStream(e).use { ins ->
                            target.outputStream().use { out -> ins.copyTo(out) }
                        }
                    }
                }
                target.absolutePath
            }
            else -> f.absolutePath
        }

        val entries = (mainCp.files + testCp.files)
            .map { entryFor(it) }
            .plus(listOfNotNull(androidJar))
            .distinct()
            .sorted()
        rootProject.file(".classpath.absolute").writeText(
            "morkstep-app\n${entries.joinToString("\n")}\n"
        )

        // Harness wiring: spawn java.exe directly (a .bat cannot inherit a pipe
        // stdin on Windows — the server would exit instantly). The path and
        // classpath are machine-specific, so lsp.json is regenerated here
        // (gitignored under .omp/) rather than committed.
        val javaHome = (System.getenv("JAVA_HOME")?.takeIf { File(it, "bin/java.exe").exists() }
            ?: File("C:/Program Files/Android/Android Studio/jbr").takeIf {
                File(it, "bin/java.exe").exists()
            }?.absolutePath
            ?: "")
        val javaCmd = when {
            javaHome.isNotBlank() -> "${javaHome.replace('\\', '/')}/bin/java.exe"
            else -> "java"
        }
        // Server class needs its own lib first (org.javacs.kt.MainKt), then the
        // project classpath so the compiler pipeline resolves app dependencies.
        val serverLib = rootProject.file(".tools/kotlin-ls/server/lib").absolutePath
            .replace('\\', '/') + "/*"
        val classpathArg = (listOf(serverLib) + entries).joinToString(";")
        // Values: javaCmd is already forward-slash (no backslashes); classpath
        // needs backslashes doubled for JSON.
        val json = """
            {"servers":{"kotlin-lsp":{"command":"$javaCmd","args":["-Xmx1g","-classpath","${classpathArg.replace("\\", "\\\\")}","org.javacs.kt.MainKt"],"fileTypes":[".kt",".kts"],"rootMarkers":["settings.gradle.kts",".git"]}}}
        """.trimIndent()
        rootProject.file(".omp/lsp.json").writeText(json)
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