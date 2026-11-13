import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.voicerewriter"
    // Compose 1.12 refuses to build below 37. Compile-time only: targetSdk stays 36.
    compileSdk = 37

    defaultConfig {
        // Was com.voicerewriter(.dev) under the upstream name; changed with the signing key on the
        // way to a public repo, so the phone installs this as a new app once (2026-11-10).
        applicationId = "io.github.kyongsikyoon.soksak"
        minSdk = 24
        // Play requires targetSdk 36 for every update from 31 Aug 2026. See the API 36
        // behaviour changes handled in this migration (edge-to-edge, predictive back,
        // 16 KB native page alignment) noted at each site.
        targetSdk = 36
        // Personal fork: CI stamps each build with its run number so every build is a newer
        // version than the last — what an updater (Obtainium) keys on. Local builds stay at 10.
        val ciRun = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (ciRun != null) 1000 + ciRun else 10
        // Kept in step with the git tag and macOS MARKETING_VERSION from 1.1.0 on; before this
        // release the three drifted apart (Android 1.0.1 / tag v0.2.1 / macOS 0.2.1).
        versionName = if (ciRun != null) "1.6.0-dev.$ciRun" else "1.6.0"
        ndk {
            // Device is arm64; whisper.cpp and onnxruntime (Silero VAD) ship arm64-v8a.
            // Restricting here keeps the APK from bundling unused ABIs.
            abiFilters += "arm64-v8a"
        }
    }

    // The builds the phone installs are signed with one fixed key so each installs over the last.
    // The key never lives in the repo: CI decodes it from the SOKSAK_KEYSTORE_BASE64 secret and
    // exports these. Locally (vars unset) builds use the machine's standard debug key, which the
    // phone won't accept as an update — install phone builds from CI.
    val soksakKeystore = System.getenv("SOKSAK_KEYSTORE_FILE")
    signingConfigs {
        if (soksakKeystore != null) {
            create("soksak") {
                storeFile = file(soksakKeystore)
                storePassword = System.getenv("SOKSAK_KEYSTORE_PASSWORD")
                keyAlias = "soksak"
                keyPassword = System.getenv("SOKSAK_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (soksakKeystore != null) signingConfig = signingConfigs.getByName("soksak")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Embed native (whisper) debug symbols in the AAB so Play Console can
            // symbolicate native crash/ANR stack traces. Stripped from the delivered APKs.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            if (soksakKeystore != null) signingConfig = signingConfigs.getByName("soksak")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // Native libs are extracted on install (matches extractNativeLibs=true).
        jniLibs {
            useLegacyPackaging = true
            // The sherpa static-link AAR still ships an x86 libonnxruntime.so, which collides
            // with com.microsoft.onnxruntime's at merge time (before abiFilters strips x86).
            // arm64 has only the Microsoft copy, so either pick is correct.
            pickFirsts += "**/libonnxruntime.so"
            // The QNN runtime AAR carries every Qualcomm backend and Hexagon version. The app runs
            // only HTP with precompiled context binaries on v79 (SM8750) and v81 (SM8850): drop the
            // CPU/GPU/DSP backends, the on-device graph compiler (HtpPrepare, 74 MB) and older chips.
            excludes += listOf(
                "**/libQnnDsp*.so", "**/libQnnGpu.so", "**/libQnnHtpPrepare.so",
                "**/libQnnHtpV6*.so", "**/libQnnHtpV73*.so", "**/libQnnHtpV75*.so",
            )
        }
    }
    testOptions {
        unitTests {
            // Code under test logs via android.util.Log; return defaults so pure JVM unit
            // tests don't need Robolectric just to no-op logging.
            isReturnDefaultValues = true
        }
    }
}

val qnnRuntime = "com.qualcomm.qti:qnn-runtime:2.40.0"
val qnnNoticeAar: Configuration by configurations.creating { isTransitive = false }

/**
 * Copies NOTICE.txt out of the QNN runtime AAR into the app's assets (as qualcomm_qnn_notice.txt,
 * shown under Settings → Open source licenses). Qualcomm's licence forbids removing that file, and
 * AGP drops an AAR's root files, so without this the APK would ship the libraries without it.
 */
abstract class QnnNoticeTask : DefaultTask() {
    @get:InputFiles abstract val aar: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction fun extract() {
        fs.sync {
            from(archives.zipTree(aar.singleFile)) {
                include("NOTICE.txt")
                rename { "qualcomm_qnn_notice.txt" }
            }
            into(outputDir)
        }
        check(outputDir.file("qualcomm_qnn_notice.txt").get().asFile.length() > 0) {
            "NOTICE.txt missing from ${aar.singleFile.name}; the QNN runtime must not ship without it"
        }
    }
}

val qnnNotice = tasks.register<QnnNoticeTask>("qnnNotice") {
    aar.from(qnnNoticeAar)
    outputDir.set(layout.buildDirectory.dir("generated/qnnNotice"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(qnnNotice, QnnNoticeTask::outputDir)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the unit-test classpath: `isReturnDefaultValues` turns the stubbed
    // android.jar version into silent nulls, which would make a JSON round-trip test pass
    // while proving nothing.
    testImplementation("org.json:json:20240303")

    implementation(project(":lib")) // on-device whisper.cpp
    // Silero VAD (ai.onnxruntime Java API). 1.22.0 is the first release whose arm64 .so files —
    // both libonnxruntime.so and libonnxruntime4j_jni.so — are 16 KB page aligned; 1.19.2 shipped
    // 4 KB, which Android 16 devices with 16 KB pages can only run in compatibility mode.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // sherpa-onnx (SenseVoice, Whisper on the Qualcomm NPU): Kotlin API vendored under
    // com/k2fsa/sherpa/onnx, libsherpa-onnx-jni.so (onnxruntime linked in statically, so no clash with
    // the VAD's libonnxruntime.so) and the QNN runtime dropped into jniLibs by CI's sherpa-qnn job.
    // tar.bz2 extraction of the sherpa model bundles.
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Qualcomm's QNN runtime for the NPU models (libQnnHtp, libQnnSystem, the v79/v81 stub and skel;
    // the rest is excluded under packaging). Taken from Qualcomm's own Maven package rather than a
    // third-party copy: its licence (AI Stack License, POM: "Qualcomm AI Hub Model License") allows
    // shipping it in object code inside an app, on condition its NOTICE file is kept (qnnNotice
    // below). Same QAIRT build (2.40.0.251030) as the headers libsherpa-onnx-jni.so is compiled with.
    implementation(qnnRuntime)
    qnnNoticeAar("$qnnRuntime@aar")
    // Silent in-app updates through Shizuku (ShizukuInstaller).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
