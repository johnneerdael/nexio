plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
    id("com.chaquo.python")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties

fun parseBooleanProperty(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase() ?: return false
    return normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
}

fun resolveProperty(dev: Properties, local: Properties, key: String, fallback: String = ""): String {
    return providers.gradleProperty(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: dev.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: local.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: fallback
}

fun cmakePath(path: String): String {
    val normalized = path.trim()
    if (normalized.isBlank()) return ""

    val forwardPath = normalized.replace("\\", "/")
    val isAbsolutePath = forwardPath.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(forwardPath)
    return if (isAbsolutePath) {
        forwardPath
    } else {
        rootProject.file(normalized).absolutePath.replace("\\", "/")
    }
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val devProperties = Properties().apply {
    val devPropertiesFile = rootProject.file("local.dev.properties")
    if (devPropertiesFile.exists()) {
        load(devPropertiesFile.inputStream())
    }
}

val enableDoviNative = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_NATIVE_ENABLED")
)
val doviExtractorHookReady = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_EXTRACTOR_HOOK_READY")
)
val doviEnableRealLink = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_ENABLE_REAL_LINK")
)
val doviStaticLibPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_STATIC_LIB")
val doviIncludeDirPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_INCLUDE_DIR")
val doviPrebuiltRootPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_PREBUILT_ROOT")
val useMedia3Source = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "USE_MEDIA3_SOURCE", "true")
)
val playPublishTaskRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.endsWith("bundlePlayRelease") || taskName.endsWith(":app:bundlePlayRelease")
}
val filteredMainAssetsDir = layout.buildDirectory.dir("filtered-assets/main")
val syncFilteredMainAssets by tasks.registering(Sync::class) {
    from("src/main/assets")
    into(filteredMainAssetsDir)
    exclude("trailer-helper/runtime/**")
}

android {
    namespace = "com.nexio.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexio.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = 66
        versionName = "0.48"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "INTRODB_API_URL", "\"${resolveProperty(localProperties, localProperties, "TIDB_API_URL").ifBlank { localProperties.getProperty("INTRODB_API_URL", "") }}\"")
        buildConfigField("String", "TRAILER_API_URL", "\"${localProperties.getProperty("TRAILER_API_URL", "")}\"")
        buildConfigField("String", "YOUTUBE_TRAILER_CLIENT_ID", "\"${resolveProperty(devProperties, localProperties, "YOUTUBE_TRAILER_CLIENT_ID")}\"")
        buildConfigField("String", "YOUTUBE_TRAILER_CLIENT_SECRET", "\"${resolveProperty(devProperties, localProperties, "YOUTUBE_TRAILER_CLIENT_SECRET")}\"")
        buildConfigField("String", "REAL_DEBRID_CLIENT_ID", "\"${resolveProperty(devProperties, localProperties, "REAL_DEBRID_CLIENT_ID")}\"")
        buildConfigField("String", "REAL_DEBRID_CLIENT_SECRET", "\"${resolveProperty(devProperties, localProperties, "REAL_DEBRID_CLIENT_SECRET")}\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${localProperties.getProperty("TRAKT_CLIENT_ID", "")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${localProperties.getProperty("TRAKT_CLIENT_SECRET", "")}\"")
        buildConfigField("String", "TRAKT_API_URL", "\"${localProperties.getProperty("TRAKT_API_URL", "https://api.trakt.tv/")}\"")
        buildConfigField("String", "TRAKT_REDIRECT_URI", "\"${localProperties.getProperty("TRAKT_REDIRECT_URI", "urn:ietf:wg:oauth:2.0:oob")}\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${localProperties.getProperty("SIMKL_CLIENT_ID", "")}\"")
        buildConfigField("String", "SIMKL_API_URL", "\"${localProperties.getProperty("SIMKL_API_URL", "https://api.simkl.com/")}\"")
        buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://app.nuvio.tv/tv-login")}\"")
        buildConfigField("String", "SHADOW_DATA_COLLECTION_BASE_URL", "\"${resolveProperty(devProperties, localProperties, "SHADOW_DATA_COLLECTION_BASE_URL", "https://datacollection.nexioapp.org")}\"")
        buildConfigField("String", "SHADOW_DATA_COLLECTION_WRITE_TOKEN", "\"${resolveProperty(devProperties, localProperties, "SHADOW_DATA_COLLECTION_WRITE_TOKEN")}\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${resolveProperty(devProperties, localProperties, "TMDB_API_KEY")}\"")
        buildConfigField("String", "TMDB_API_URL", "\"${resolveProperty(devProperties, localProperties, "TMDB_API_URL", "https://api.themoviedb.org/3/")}\"")
        buildConfigField("String", "TVDB_API_KEY", "\"${resolveProperty(devProperties, localProperties, "TVDB_API_KEY")}\"")
        buildConfigField("String", "TVDB_API_URL", "\"${resolveProperty(devProperties, localProperties, "TVDB_API_URL", "https://api4.thetvdb.com/v4/")}\"")
        buildConfigField("boolean", "DOVI_NATIVE_ENABLED", enableDoviNative.toString())
        buildConfigField("boolean", "DOVI_EXTRACTOR_HOOK_READY", doviExtractorHookReady.toString())
        if (enableDoviNative) {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DDOVI_ENABLE_LIBDOVI=${if (doviEnableRealLink) "ON" else "OFF"}",
                        "-DDOVI_LIBDOVI_STATIC_LIB=${cmakePath(doviStaticLibPath)}",
                        "-DDOVI_LIBDOVI_INCLUDE_DIR=${cmakePath(doviIncludeDirPath)}",
                        "-DDOVI_LIBDOVI_PREBUILT_ROOT=${cmakePath(doviPrebuiltRootPath)}"
                    )
                }
            }
        }

        // In-app updater (GitHub Releases)
        buildConfigField("String", "GITHUB_OWNER", "\"johnneerdael\"")
        buildConfigField("String", "GITHUB_REPO", "\"nexio\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            buildStagingDirectory = file("${rootProject.projectDir}/.cxx-build")
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = "nexio"
            keyPassword = "gP^EJa&xPLCk89"
            storeFile = file("../nexio.jks")
            storePassword = "gP^EJa&xPLCk89"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false

            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")

            // Dev environment (from local.dev.properties)
            buildConfigField("String", "SUPABASE_URL", "\"${devProperties.getProperty("SUPABASE_URL", "")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${devProperties.getProperty("SUPABASE_ANON_KEY", "")}\"")
            buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${devProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://app.nuvio.tv/tv-login")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${resolveProperty(devProperties, localProperties, "TIDB_API_URL").ifBlank { devProperties.getProperty("INTRODB_API_URL", "") }}\"")
            buildConfigField("String", "TRAILER_API_URL", "\"${devProperties.getProperty("TRAILER_API_URL", "")}\"")
            buildConfigField("String", "YOUTUBE_TRAILER_CLIENT_ID", "\"${resolveProperty(devProperties, localProperties, "YOUTUBE_TRAILER_CLIENT_ID")}\"")
            buildConfigField("String", "YOUTUBE_TRAILER_CLIENT_SECRET", "\"${resolveProperty(devProperties, localProperties, "YOUTUBE_TRAILER_CLIENT_SECRET")}\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")

            buildConfigField("boolean", "IS_DEBUG_BUILD", "false")

            // Production environment (from local.properties)
            buildConfigField("String", "SUPABASE_URL", "\"${localProperties.getProperty("SUPABASE_URL", "")}\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProperties.getProperty("SUPABASE_ANON_KEY", "")}\"")
            buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://app.nuvio.tv/tv-login")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${resolveProperty(localProperties, localProperties, "TIDB_API_URL").ifBlank { localProperties.getProperty("INTRODB_API_URL", "") }}\"")
            buildConfigField("String", "TRAILER_API_URL", "\"${localProperties.getProperty("TRAILER_API_URL", "")}\"")
            buildConfigField("String", "YOUTUBE_TRAILER_CLIENT_ID", "\"${resolveProperty(devProperties, localProperties, "YOUTUBE_TRAILER_CLIENT_ID")}\"")
            buildConfigField("String", "YOUTUBE_TRAILER_CLIENT_SECRET", "\"${resolveProperty(devProperties, localProperties, "YOUTUBE_TRAILER_CLIENT_SECRET")}\"")
        }
        create("releaseProfileable") {
            initWith(getByName("release"))
            isProfileable = true
            isDebuggable = false
            applicationIdSuffix = ".profileable"
            versionNameSuffix = "-profileable"
            matchingFallbacks += listOf("release")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.forkEvery = 50  // restart JVM every 50 tests to avoid state buildup
            it.workingDir = rootProject.projectDir
        }
    }

    flavorDimensions += "abiPackaging"
    productFlavors {
        if (!playPublishTaskRequested) {
            create("arm64") {
                dimension = "abiPackaging"
                ndk {
                    abiFilters += listOf("arm64-v8a")
                }
            }
            create("armv7") {
                dimension = "abiPackaging"
                ndk {
                    abiFilters += listOf("armeabi-v7a")
                }
            }
        }
        create("universal") {
            dimension = "abiPackaging"
            ndk {
                abiFilters += if (playPublishTaskRequested) {
                    listOf("arm64-v8a")
                } else {
                    listOf("armeabi-v7a", "arm64-v8a")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("js", "json")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs(
                "src/main/_jni_disabled",
                "src/main/jniLibs"
            )
            // Package a filtered copy of the main assets tree so legacy staged
            // trailer-helper runtimes don't bloat every split APK.
            assets.setSrcDirs(listOf(syncFilteredMainAssets))
        }
    }

    packaging {
        jniLibs {
            // Keep one consistent native set across dependencies.
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavformat.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        if (!playPublishTaskRequested) return@beforeVariants
        val abiFlavor = variantBuilder.productFlavors
            .firstOrNull { (dimension, _) -> dimension == "abiPackaging" }
            ?.second
        if (abiFlavor != "universal") {
            variantBuilder.enable = false
        }
    }

    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("com.nexiodebug.tv")
    }
}

tasks.register("bundlePlayRelease") {
    group = "build"
    description = "Build the Play-targeted universal release bundle without support-only release variants."
    dependsOn("bundleUniversalRelease")
}

composeCompiler {
    // Enable Compose compiler metrics for performance analysis
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability_config.conf"))
}

// Globally exclude stock media3-exoplayer and media3-ui — replaced by forked local AARs
configurations.all {
    if (!useMedia3Source) {
        exclude(group = "androidx.media3", module = "media3-exoplayer")
        exclude(group = "androidx.media3", module = "media3-ui")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.01.01")

    // baselineProfile(project(":benchmark"))  // TODO: create benchmark module later
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.profileinstaller)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.1")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.javascriptengine:javascriptengine:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(libs.androidx.tvprovider)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)

    // Navigation
    implementation(libs.navigation.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // WorkManager
    implementation(libs.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    testImplementation(libs.work.testing)

    // Lock-free queues for playback instrumentation
    implementation(libs.jctools.core)

    // ViewModel
    implementation(libs.lifecycle.viewmodel.compose)

    // Media3 core modules.
    if (useMedia3Source) {
        implementation(libs.media3.exoplayer)
        implementation("androidx.media3:media3-exoplayer-kodi-cpp-audiosink:${libs.versions.media3.get()}")
        implementation(libs.media3.ui)
        implementation("androidx.media3:media3-decoder-ffmpeg:${libs.versions.media3.get()}")
    }
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.decoder)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.container)
    implementation(libs.media3.extractor)

    // Local AAR libraries from forked ExoPlayer.
    if (useMedia3Source) {
        // Source mode uses media/ for ExoPlayer + UI + FFmpeg decoder.
        // Keep only the remaining decoder extension AARs from app/libs.
        implementation(
            fileTree(
                mapOf(
                    "dir" to "libs",
                    "include" to listOf(
                        "lib-decoder-av1-*.aar",
                        "lib-decoder-iamf-*.aar",
                        "lib-decoder-mpegh-*.aar"
                    )
                )
            )
        )
    } else {
        // AAR mode uses prebuilt core/UI + decoder extensions.
        implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar"))))
    }

    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")
    implementation("dev.chrisbanes.haze:haze-android:0.7.3") {
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
    }

    implementation(libs.gson)

    // Markdown rendering
    implementation(libs.markdown.renderer.m3)

    // QR code + local server for addon management
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)

    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.okhttp)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Performance profiling
    implementation("androidx.metrics:metrics-performance:1.0.0-beta01")  // JankStats
    implementation("androidx.compose.runtime:runtime-tracing")           // Compose function names in Perfetto

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // WP10 — playback tracer microbenchmark.
    androidTestImplementation(libs.androidx.benchmark.junit4)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.media3:media3-test-utils:${libs.versions.media3.get()}")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
