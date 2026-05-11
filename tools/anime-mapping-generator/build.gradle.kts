plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.nexio.animemap.MainKt")
}

dependencies {
    implementation(libs.moshi)
    implementation(libs.okio)
    ksp(libs.moshi.codegen)
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

val fribbRawUrl = "https://raw.githubusercontent.com/Fribb/anime-lists/refs/heads/master/anime-list-full.json"
val fribbCommitsUrl = "https://api.github.com/repos/Fribb/anime-lists/commits/master"
val scudleeRawUrl = "https://raw.githubusercontent.com/Anime-Lists/anime-lists/refs/heads/master/anime-list-full.xml"
val scudleeCommitsUrl = "https://api.github.com/repos/Anime-Lists/anime-lists/commits/master"

val cacheDir = layout.buildDirectory.dir("cache")
val fribbCache = cacheDir.map { it.file("fribb.json") }
val scudleeCache = cacheDir.map { it.file("scudlee.xml") }
val sourceShasFile = cacheDir.map { it.file("source-shas.json") }

val overlayFile = layout.projectDirectory.file("src/main/resources/nexio-anime-overlay.json")
val rootProjectDir = rootProject.layout.projectDirectory
val assetOutput = rootProjectDir.file("app/src/main/assets/anime/nexio-anime-map-v1.json")
val provenanceOutput = rootProjectDir.file("app/src/main/assets/anime/nexio-anime-map-provenance.json")

tasks.register<JavaExec>("fetchAnimeMappingSources") {
    group = "anime-mapping"
    description = "Fetch Fribb + ScudLee upstream files into build/cache. Explicit-invocation only."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nexio.animemap.FetchMain")
    args = listOf(
        fribbRawUrl, fribbCommitsUrl, fribbCache.get().asFile.absolutePath,
        scudleeRawUrl, scudleeCommitsUrl, scudleeCache.get().asFile.absolutePath,
        sourceShasFile.get().asFile.absolutePath
    )
    outputs.file(fribbCache)
    outputs.file(scudleeCache)
    outputs.file(sourceShasFile)
}

tasks.register<JavaExec>("generateAnimeMappingAsset") {
    group = "anime-mapping"
    description = "Generate nexio-anime-map-v1.json from cached upstream sources + overlay. Explicit-invocation only — :app:preBuild does NOT depend on this."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.nexio.animemap.GenerateMain")
    args = listOf(
        fribbCache.get().asFile.absolutePath,
        scudleeCache.get().asFile.absolutePath,
        overlayFile.asFile.absolutePath,
        sourceShasFile.get().asFile.absolutePath,
        assetOutput.asFile.absolutePath,
        provenanceOutput.asFile.absolutePath,
        fribbRawUrl, scudleeRawUrl
    )
    inputs.file(fribbCache)
    inputs.file(scudleeCache)
    inputs.file(sourceShasFile)
    inputs.file(overlayFile)
    outputs.file(assetOutput)
    outputs.file(provenanceOutput)
    doFirst {
        val missing = listOfNotNull(
            fribbCache.get().asFile.takeIf { !it.exists() },
            scudleeCache.get().asFile.takeIf { !it.exists() }
        )
        if (missing.isNotEmpty()) {
            error("upstream cache missing: ${missing.joinToString { it.name }}. " +
                  "Run :tools:anime-mapping-generator:fetchAnimeMappingSources first.")
        }
    }
}
