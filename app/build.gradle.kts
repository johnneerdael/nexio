plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.time.Instant
import java.util.Properties
import org.gradle.api.tasks.testing.Test

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

val animeIdMapOutput = layout.projectDirectory.file("src/main/assets/anime/anime-id-map.json")
val animeIdMapSources = mapOf(
    "fribbAnimeList" to "https://raw.githubusercontent.com/Fribb/anime-lists/refs/heads/master/anime-list-full.json",
    "traktAnimeMovies" to "https://github.com/rensetsu/db.trakt.extended-anitrakt/releases/download/latest/movies_ex.json",
    "kitsuImdb" to "https://raw.githubusercontent.com/TheBeastLT/stremio-kitsu-anime/bbf149474f610885629b95b1b9ce4408c3c1353d/static/data/imdb_mapping.json"
)

fun positiveId(value: Any?): String? {
    return when (value) {
        null -> null
        is Boolean -> null
        is Number -> value.toLong().takeIf { it > 0L }?.toString()
        else -> value.toString().trim().takeIf { it.all(Char::isDigit) }?.toLongOrNull()?.takeIf { it > 0L }?.toString()
    }
}

fun normalizedString(value: Any?): String? =
    value?.toString()?.trim()?.takeIf { it.isNotBlank() }

fun normalizedImdb(value: Any?): String? =
    normalizedString(value)?.takeIf { it.startsWith("tt") }

fun mediaTypeFromAnimeListType(value: String?): String {
    return when (value?.trim()?.lowercase()) {
        "movie", "film" -> "movie"
        else -> "series"
    }
}

fun sortedJsonMap(map: Map<String, Any>): LinkedHashMap<String, Any> {
    fun keySort(value: String): Pair<Int, String> =
        value.toLongOrNull()?.let { 0 to it.toString().padStart(20, '0') } ?: (1 to value)

    return linkedMapOf<String, Any>().also { sorted ->
        map.keys.sortedWith(compareBy({ keySort(it).first }, { keySort(it).second })).forEach { key ->
            sorted[key] = map.getValue(key)
        }
    }
}

fun sortedRecord(record: Map<String, String>): LinkedHashMap<String, String> {
    val order = listOf("kitsu", "mal", "anilist", "anidb", "tmdb", "tvdb", "imdb", "mediaType", "sourceType")
    return linkedMapOf<String, String>().also { sorted ->
        order.forEach { key -> record[key]?.let { sorted[key] = it } }
    }
}

fun addIndex(index: MutableMap<String, String>, key: String?, kitsuId: String) {
    if (!key.isNullOrBlank() && !index.containsKey(key)) {
        index[key] = kitsuId
    }
}

fun indexAnimeRecord(
    indexes: Map<String, MutableMap<String, String>>,
    record: Map<String, String>
) {
    val kitsuId = record["kitsu"] ?: return
    indexes.getValue("byKitsu")[kitsuId] = kitsuId
    addIndex(indexes.getValue("byMal"), record["mal"], kitsuId)
    addIndex(indexes.getValue("byAnilist"), record["anilist"], kitsuId)
    addIndex(indexes.getValue("byAnidb"), record["anidb"], kitsuId)
    addIndex(indexes.getValue("byTvdb"), record["tvdb"], kitsuId)
    addIndex(indexes.getValue("byImdb"), record["imdb"], kitsuId)
    record["tmdb"]?.let { tmdbId ->
        if (record["mediaType"] == "movie") {
            addIndex(indexes.getValue("byTmdbMovie"), tmdbId, kitsuId)
        } else {
            addIndex(indexes.getValue("byTmdbSeries"), tmdbId, kitsuId)
        }
    }
}

fun normalizeFribbAnimeRecord(raw: Map<*, *>): LinkedHashMap<String, String> {
    val record = linkedMapOf<String, String>()
    positiveId(raw["mal_id"])?.let { record["mal"] = it }
    positiveId(raw["kitsu_id"])?.let { record["kitsu"] = it }
    positiveId(raw["anidb_id"])?.let { record["anidb"] = it }
    positiveId(raw["anilist_id"])?.let { record["anilist"] = it }
    positiveId(raw["themoviedb_id"])?.let { record["tmdb"] = it }
    positiveId(raw["tvdb_id"])?.let { record["tvdb"] = it }
    normalizedImdb(raw["imdb_id"])?.let { record["imdb"] = it }
    val sourceType = normalizedString(raw["type"])
    record["mediaType"] = mediaTypeFromAnimeListType(sourceType)
    sourceType?.let { record["sourceType"] = it }
    return sortedRecord(record)
}

fun buildAnimeIdMapAsset(
    fribbRecords: List<*>,
    traktAnimeMovieRecords: List<*>,
    kitsuImdbRecords: Map<*, *>,
    generatedAt: String
): LinkedHashMap<String, Any> {
    val recordsByKitsu = linkedMapOf<String, Map<String, String>>()
    val indexes = mapOf(
        "byKitsu" to linkedMapOf<String, String>(),
        "byMal" to linkedMapOf(),
        "byAnilist" to linkedMapOf(),
        "byAnidb" to linkedMapOf(),
        "byTvdb" to linkedMapOf(),
        "byTmdbMovie" to linkedMapOf(),
        "byTmdbSeries" to linkedMapOf(),
        "byImdb" to linkedMapOf()
    )

    fribbRecords.filterIsInstance<Map<*, *>>().forEach { raw ->
        val record = normalizeFribbAnimeRecord(raw)
        val kitsuId = record["kitsu"] ?: return@forEach
        recordsByKitsu[kitsuId] = sortedRecord(recordsByKitsu[kitsuId].orEmpty() + record)
        indexAnimeRecord(indexes, recordsByKitsu.getValue(kitsuId))
    }

    kitsuImdbRecords.forEach { (rawKitsuId, rawImdbId) ->
        val kitsuId = positiveId(rawKitsuId) ?: return@forEach
        val imdbId = normalizedImdb(rawImdbId) ?: return@forEach
        val record = sortedRecord(recordsByKitsu[kitsuId].orEmpty() + mapOf("kitsu" to kitsuId, "imdb" to imdbId, "mediaType" to "series"))
        recordsByKitsu[kitsuId] = record
        indexes.getValue("byKitsu")[kitsuId] = kitsuId
        indexes.getValue("byImdb").putIfAbsent(imdbId, kitsuId)
    }

    traktAnimeMovieRecords.filterIsInstance<Map<*, *>>().forEach { raw ->
        val malId = positiveId((raw["myanimelist"] as? Map<*, *>)?.get("id")) ?: return@forEach
        val kitsuId = indexes.getValue("byMal")[malId] ?: return@forEach
        val externals = raw["externals"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val additions = linkedMapOf("kitsu" to kitsuId, "mal" to malId, "mediaType" to "movie")
        positiveId(externals["tmdb"])?.let { tmdbId ->
            additions["tmdb"] = tmdbId
            indexes.getValue("byTmdbMovie")[tmdbId] = kitsuId
        }
        normalizedImdb(externals["imdb"])?.let { imdbId ->
            additions["imdb"] = imdbId
            indexes.getValue("byImdb")[imdbId] = kitsuId
        }
        recordsByKitsu[kitsuId] = sortedRecord(recordsByKitsu[kitsuId].orEmpty() + additions)
        indexes.getValue("byKitsu")[kitsuId] = kitsuId
    }

    val counts = linkedMapOf<String, Any>(
        "recordsByKitsu" to recordsByKitsu.size
    )
    indexes.forEach { (name, values) -> counts[name] = values.size }

    return linkedMapOf(
        "schemaVersion" to 1,
        "generatedAt" to generatedAt,
        "sources" to animeIdMapSources,
        "counts" to counts,
        "recordsByKitsu" to sortedJsonMap(recordsByKitsu.mapValues { it.value })
    ).also { asset ->
        indexes.forEach { (name, values) ->
            asset[name] = sortedJsonMap(values)
        }
    }
}

fun fetchJson(url: String): Any {
    val connection = URI(url).toURL().openConnection()
    connection.setRequestProperty("User-Agent", "Nexio anime-id-map Gradle generator")
    return connection.getInputStream().use { stream ->
        JsonSlurper().parse(stream)
    }
}

fun sameAnimeIdMapContent(existing: Any?, generated: Map<String, Any>): Boolean {
    val existingMap = existing as? Map<*, *> ?: return false
    return existingMap.filterKeys { it != "generatedAt" } == generated.filterKeys { it != "generatedAt" }
}

val generateAnimeIdMap by tasks.registering {
    group = "build"
    description = "Download anime ID mappings and generate the bundled indexed Android asset."
    outputs.file(animeIdMapOutput)
    outputs.upToDateWhen { false }

    doLast {
        val fribbRecords = fetchJson(animeIdMapSources.getValue("fribbAnimeList")) as? List<*>
            ?: error("Fribb anime-list response was not a JSON array")
        val traktAnimeMovieRecords = fetchJson(animeIdMapSources.getValue("traktAnimeMovies")) as? List<*>
            ?: error("Trakt anime movie response was not a JSON array")
        val kitsuImdbRecords = fetchJson(animeIdMapSources.getValue("kitsuImdb")) as? Map<*, *>
            ?: error("Kitsu IMDb response was not a JSON object")

        val outputFile = animeIdMapOutput.asFile
        val existingAsset = outputFile.takeIf { it.exists() }?.let { JsonSlurper().parse(it) }
        val existingGeneratedAt = (existingAsset as? Map<*, *>)?.get("generatedAt") as? String
        val provisionalAsset = buildAnimeIdMapAsset(
            fribbRecords = fribbRecords,
            traktAnimeMovieRecords = traktAnimeMovieRecords,
            kitsuImdbRecords = kitsuImdbRecords,
            generatedAt = existingGeneratedAt ?: Instant.now().toString()
        )

        if (sameAnimeIdMapContent(existingAsset, provisionalAsset)) {
            println("Anime ID map is unchanged: ${outputFile.relativeTo(projectDir)}")
            println(JsonOutput.prettyPrint(JsonOutput.toJson(provisionalAsset["counts"])))
            return@doLast
        }

        val asset = if (existingGeneratedAt == null) {
            provisionalAsset
        } else {
            buildAnimeIdMapAsset(
                fribbRecords = fribbRecords,
                traktAnimeMovieRecords = traktAnimeMovieRecords,
                kitsuImdbRecords = kitsuImdbRecords,
                generatedAt = Instant.now().toString()
            )
        }
        outputFile.parentFile.mkdirs()
        outputFile.writeText(JsonOutput.toJson(asset) + "\n")
        println("Generated ${outputFile.relativeTo(projectDir)}")
        println(JsonOutput.prettyPrint(JsonOutput.toJson(asset["counts"])))
    }
}

val openRouterReasoningModelsOutput =
    layout.projectDirectory.file("src/main/assets/openrouter_reasoning_models.json")
val openRouterReasoningModelsEndpoint =
    "https://openrouter.ai/api/v1/models?supported_parameters=reasoning"

fun stripOpenRouterModelVariant(value: Any?): String? =
    value?.toString()?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.substringBefore(':')
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

val generateOpenRouterReasoningModels by tasks.registering {
    group = "build"
    description = "Refresh the bundled list of OpenRouter models that support reasoning controls."
    outputs.file(openRouterReasoningModelsOutput)
    outputs.upToDateWhen { false }

    doLast {
        val outputFile = openRouterReasoningModelsOutput.asFile
        val payload = try {
            fetchJson(openRouterReasoningModelsEndpoint) as? Map<*, *>
                ?: throw IllegalStateException("OpenRouter response was not a JSON object")
        } catch (cause: Exception) {
            val fallback = if (outputFile.exists()) "keeping committed asset" else "no committed asset present"
            logger.warn(
                "Skipping OpenRouter reasoning-model refresh ({}): {}",
                fallback,
                cause.message ?: cause.javaClass.simpleName
            )
            return@doLast
        }

        val rawEntries = payload["data"] as? List<*>
            ?: throw IllegalStateException("OpenRouter response missing 'data' array")

        val slugs = sortedSetOf<String>()
        rawEntries.filterIsInstance<Map<*, *>>().forEach { entry ->
            stripOpenRouterModelVariant(entry["id"])?.let(slugs::add)
            stripOpenRouterModelVariant(entry["canonical_slug"])?.let(slugs::add)
        }

        if (slugs.isEmpty()) {
            logger.warn("OpenRouter response contained zero reasoning models; keeping existing asset.")
            return@doLast
        }

        val rendered = JsonOutput.toJson(slugs.toList()) + "\n"
        if (outputFile.exists() && outputFile.readText() == rendered) {
            println("OpenRouter reasoning models unchanged (${slugs.size} entries).")
            return@doLast
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(rendered)
        println("Generated ${outputFile.relativeTo(projectDir)} (${slugs.size} entries)")
    }
}

val runtimeEventAuditSampleFile = layout.buildDirectory.file("reports/integration-runtime-audit/runtime-event-sample.generated.jsonl")
val generateRuntimeEventAuditSample by tasks.registering(Test::class) {
    group = "verification"
    description = "Generate runtime audit event sample from DefaultIntegrationRuntime tests."
    val sourceTest = tasks.named<Test>("testUniversalDebugUnitTest")
    testClassesDirs = sourceTest.get().testClassesDirs
    classpath = sourceTest.get().classpath
    outputs.file(runtimeEventAuditSampleFile)
    filter {
        includeTestsMatching("com.nexio.tv.core.integration.DefaultIntegrationRuntimeTest.generated runtime audit sample uses real phase names and network flags")
    }
}

val generateIntegrationRuntimeAudit by tasks.registering(com.nexio.build.integrationaudit.GenerateIntegrationRuntimeAuditTask::class) {
    group = "verification"
    description = "Generate the IntegrationRuntime Connectivity & Policy Audit package."
    dependsOn(generateRuntimeEventAuditSample)
    sourceRoot.set(layout.projectDirectory.dir("src/main/java"))
    expectedShapesFile.set(layout.projectDirectory.file("src/test/resources/integration/expected_api_shapes.yaml"))
    expectedContractsFile.set(layout.projectDirectory.file("src/test/resources/integration/expected_integration_contracts.yaml"))
    metadataRouterPrerequisitesFile.set(layout.projectDirectory.file("src/test/resources/integration/metadata_router_prerequisites.txt"))
    runtimeEventFixtureFile.set(runtimeEventAuditSampleFile)
    outputDirectory.set(layout.buildDirectory.dir("reports/integration-runtime-audit"))
    projectRoot.set(rootProject.layout.projectDirectory)
    failOnFailVerdict.set(providers.gradleProperty("integrationRuntimeAudit.failOnFailVerdict").map(String::toBoolean).orElse(true))
}

tasks.register<Test>("generateMetadataExecutionAudit") {
    group = "verification"
    description = "Runs metadata execution audit and writes JSON/Markdown reports."
    val sourceTest = tasks.named<Test>("testUniversalDebugUnitTest")
    testClassesDirs = sourceTest.get().testClassesDirs
    classpath = sourceTest.get().classpath
    filter {
        includeTestsMatching("com.nexio.tv.metadata.audit.MetadataExecutionAuditGoldenTest")
        includeTestsMatching("com.nexio.tv.metadata.audit.MetadataArchitectureBoundaryTest")
    }
}

val filteredMainAssetsDir = layout.buildDirectory.dir("filtered-assets/main")
val syncFilteredMainAssets by tasks.registering(Sync::class) {
    dependsOn(generateAnimeIdMap, generateOpenRouterReasoningModels)
    from("src/main/assets")
    into(filteredMainAssetsDir)
}

android {
    namespace = "com.nexio.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexio.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = 73
        versionName = "0.55"
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
        buildConfigField("String", "IMDB_API_URL", "\"${resolveProperty(devProperties, localProperties, "IMDB_API_URL", "https://api.nexioapp.org/v1/")}\"")
        buildConfigField("String", "IMDB_WS_URL", "\"${resolveProperty(devProperties, localProperties, "IMDB_WS_URL", "wss://api.nexioapp.org/v1/ws")}\"")
        buildConfigField("String", "IMDB_API_KEY", "\"${resolveProperty(devProperties, localProperties, "IMDB_API_KEY")}\"")
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
            it.forkEvery = 10  // restart JVM every 10 tests to keep cross-test pollution (MockK global state, leaked coroutines dispatching to Dispatchers.Main) from causing flaky failures; see 2026-04-21 investigation
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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

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
    testImplementation(libs.androidx.room.testing)
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
