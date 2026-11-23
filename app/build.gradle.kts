import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "it.parrocchia.coro"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.parrocchia.coro"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/Android.mk")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("fluidsynth/v2.6.2/jniLibs"))
        }
    }

    androidResources {
        noCompress += listOf("sf2", "sf3", "mid", "midi", "kar", "mp3", "wav")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val fluidSynthVersion = "2.6.2"
val fluidSynthRoot = layout.buildDirectory.dir("fluidsynth/v$fluidSynthVersion")
val fluidSynthCacheZip = File(
    gradle.gradleUserHomeDir,
    "caches/coro-fluidsynth/fluidsynth-v$fluidSynthVersion-android24.zip"
)
val fluidSynthAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

val prepareFluidSynth by tasks.registering {
    val rootDirFile = fluidSynthRoot.get().asFile
    val headerMarker = File(rootDirFile, "include/fluidsynth.h")
    val libMarker = File(rootDirFile, "jniLibs/arm64-v8a/libfluidsynth.so")
    outputs.dir(fluidSynthRoot)
    onlyIf { !headerMarker.exists() || !libMarker.exists() }

    doLast {
        rootDirFile.mkdirs()
        fluidSynthCacheZip.parentFile?.mkdirs()

        val zipValid = fluidSynthCacheZip.exists() && runCatching {
            ZipFile(fluidSynthCacheZip).use { it.getEntry("include/fluidsynth.h") != null }
        }.getOrDefault(false)

        if (!zipValid) {
            fluidSynthCacheZip.delete()
            val url = URI(
                "https://github.com/FluidSynth/fluidsynth/releases/download/v$fluidSynthVersion/fluidsynth-v$fluidSynthVersion-android24.zip"
            ).toURL()
            val tmpFile = File(fluidSynthCacheZip.parentFile, "${fluidSynthCacheZip.name}.tmp")
            var lastErr: Exception? = null
            for (attempt in 1..3) {
                try {
                    logger.lifecycle("Downloading FluidSynth v$fluidSynthVersion Android prebuilts (attempt $attempt/3)...")
                    tmpFile.delete()
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 30_000
                        readTimeout = 300_000
                        setRequestProperty("User-Agent", "Coro-Android-Build")
                    }
                    try {
                        val code = conn.responseCode
                        check(code in 200..299) { "HTTP $code while downloading FluidSynth" }
                        conn.inputStream.use { input ->
                            tmpFile.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 64 * 1024)
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    ZipFile(tmpFile).use { z ->
                        check(z.getEntry("include/fluidsynth.h") != null) { "Downloaded FluidSynth zip is incomplete" }
                    }
                    check(tmpFile.renameTo(fluidSynthCacheZip)) { "Could not move FluidSynth archive to cache" }
                    lastErr = null
                    break
                } catch (e: Exception) {
                    lastErr = e
                    tmpFile.delete()
                    logger.warn("FluidSynth download attempt $attempt failed: ${e.message}")
                }
            }
            if (lastErr != null) throw lastErr
        }

        ZipFile(fluidSynthCacheZip).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                val target: File? = when {
                    name.startsWith("include/") -> File(rootDirFile, name)
                    name.startsWith("lib/") && name.endsWith(".so") -> {
                        val parts = name.split("/")
                        if (parts.size == 3 && parts[1] in fluidSynthAbis) {
                            File(rootDirFile, "jniLibs/${parts[1]}/${parts[2]}")
                        } else null
                    }
                    else -> null
                }
                if (target != null) {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(prepareFluidSynth)
}

tasks.configureEach {
    if (name.startsWith("configureNdkBuild") ||
        name.startsWith("buildNdkBuild") ||
        name.startsWith("merge") && name.contains("JniLibFolders")
    ) {
        dependsOn(prepareFluidSynth)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
}
