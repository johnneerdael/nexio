package com.nexio.tv.architecture

import java.io.File

private val boundaryTargets: List<File> = listOf(
    File("app/src/main/java/com/nexio/tv/ui"),
    File("app/src/main/java/com/nexio/tv/workers"),
    File("app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt"),
    File("app/src/main/java/com/nexio/tv/data/repository/DebridLibraryService.kt"),
    File("app/src/main/java/com/nexio/tv/data/repository/servicewrap"),
    File("app/src/main/java/com/nexio/tv/core/poster/PosterRatingsUrlResolver.kt")
)

data class PathPatternMatches(
    val path: String,
    val matches: List<String>
) {
    fun format(): String = "$path:${matches.joinToString(",")}"
}

fun architectureScan(
    allowedPackages: Set<String>,
    forbiddenSimpleNames: Set<String>
): List<String> {
    val patterns = forbiddenSimpleNames.map { Regex("""\b${Regex.escape(it)}\b""") }
    return scanTextFiles(
        files = mainSourceFiles(),
        forbiddenSimpleNames = forbiddenSimpleNames,
        patterns = patterns,
        allowedPackages = allowedPackages
    )
}

fun sourceTextScan(
    forbiddenPatterns: List<String>,
    allowedPaths: List<String>
): List<String> {
    return productionTextScan(
        forbiddenPatterns = forbiddenPatterns,
        allowedPaths = allowedPaths
    )
}

fun productionTextScan(
    forbiddenPatterns: List<String>,
    allowedPaths: List<String>
): List<String> {
    return productionTextScanMatches(
        forbiddenPatterns = forbiddenPatterns,
        allowedPaths = allowedPaths
    ).map(PathPatternMatches::format)
}

fun productionTextScanMatches(
    forbiddenPatterns: List<String>,
    allowedPaths: List<String>
): List<PathPatternMatches> {
    return scanTextPaths(
        files = mainSourceFiles(),
        forbiddenPatterns = forbiddenPatterns,
        allowedPaths = resolveProductionAllowedPaths(allowedPaths)
    )
}

fun productionRegexScan(
    forbiddenPatterns: Map<String, Regex>,
    allowedPaths: List<String>
): List<String> {
    return productionRegexScanMatches(
        forbiddenPatterns = forbiddenPatterns,
        allowedPaths = allowedPaths
    ).map(PathPatternMatches::format)
}

fun productionRegexScanMatches(
    forbiddenPatterns: Map<String, Regex>,
    allowedPaths: List<String>
): List<PathPatternMatches> {
    return scanRegexPaths(
        files = mainSourceFiles(),
        forbiddenPatterns = forbiddenPatterns,
        allowedPaths = resolveProductionAllowedPaths(allowedPaths)
    )
}

fun productionAllowedPathSuffixes(vararg suffixes: String): List<String> =
    suffixes.map { suffix ->
        require(suffix.startsWith("/")) { "Production allowed path suffix must start with '/': $suffix" }
        suffix
    }

fun productionSimpleNamesInPathSuffix(
    pathSuffix: String,
    simpleNamePattern: Regex = Regex("""\b(?:interface|class)\s+([A-Z][A-Za-z0-9_]*)\b""")
): List<String> {
    val normalizedSuffix = normalizePath(pathSuffix)
    require(normalizedSuffix.startsWith("/")) { "Production path suffix must start with '/': $pathSuffix" }

    return mainSourceFiles()
        .filter { normalizePath(it.path).contains(normalizedSuffix) }
        .flatMap { file ->
            simpleNamePattern.findAll(file.readText()).map { match -> match.groupValues[1] }.asSequence()
        }
        .distinct()
        .sorted()
        .toList()
}

fun productionRemoteApiSurfaceSimpleNames(
    pathSuffix: String,
    simpleNamePattern: Regex = Regex("""\b(interface|class)\s+([A-Z][A-Za-z0-9_]*)\b"""),
    outboundSignalPattern: Regex = Regex(
        """@(GET|POST|PUT|DELETE|PATCH|Streaming|Multipart|FormUrlEncoded)\b|\bWebSocket\b|\bWebSocketListener\b""",
        setOf(RegexOption.MULTILINE)
    )
): List<String> {
    val normalizedSuffix = normalizePath(pathSuffix)
    require(normalizedSuffix.startsWith("/")) { "Production path suffix must start with '/': $pathSuffix" }

    val files = mainSourceFiles()
        .filter { normalizePath(it.path).contains(normalizedSuffix) }
        .toList()
    require(files.isNotEmpty()) { "No production files found for remote API surface suffix: $pathSuffix" }

    val simpleNames = files
        .flatMap { file ->
            val content = file.readText()
            simpleNamePattern.findAll(content)
                .map { match -> match.groupValues[1] to match.groupValues[2] }
                .filter { (declarationKind, simpleName) ->
                    simpleName.endsWith("Api") ||
                        (declarationKind == "interface" &&
                            simpleName.endsWith("Service") &&
                            outboundSignalPattern.containsMatchIn(content))
                }
                .map { (_, simpleName) -> simpleName }
                .asSequence()
        }
        .distinct()
        .sorted()
        .toList()
    require(simpleNames.isNotEmpty()) { "No remote API/service symbols discovered for suffix: $pathSuffix" }
    return simpleNames
}

fun productionAllowedPathContainsAll(vararg fragments: String): List<String> {
    require(fragments.isNotEmpty()) { "Production allowed path fragments must not be empty." }
    fragments.forEach { fragment ->
        require(fragment.isNotBlank()) { "Production allowed path fragment must not be blank." }
    }
    return listOf("contains-all:${fragments.joinToString("::")}")
}

fun sourceTextScanWithinTargets(
    forbiddenPatterns: List<String>,
    allowedPaths: List<String>
): List<String> {
    return scanTextPaths(
        files = boundarySourceFiles(),
        forbiddenPatterns = forbiddenPatterns,
        allowedPaths = allowedPaths
    ).map(PathPatternMatches::format)
}

private fun boundarySourceFiles(): Sequence<File> =
    boundaryTargets.asSequence().flatMap { target ->
        when {
            !target.exists() -> error("Required architecture boundary target is missing: ${target.path}")
            target.isDirectory -> target.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .asSequence()
            else -> sequenceOf(target)
        }
    }

private fun mainSourceFiles(): Sequence<File> =
    productionSourceRoots().flatMap { root ->
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .asSequence()
    }

private fun productionSourceRoots(): Sequence<File> =
    productionSourceRootDirectories().asSequence()
        .filter(File::isDirectory)
        .filterNot { it.name == "test" || it.name == "androidTest" }
        .flatMap { sourceSet ->
            sequenceOf(File(sourceSet, "java"), File(sourceSet, "kotlin"))
                .filter(File::exists)
        }
        .ifEmpty {
            error("No production source roots found under app/src. Expected at least one app/src/<sourceSet>/(java|kotlin) directory.")
        }

private fun productionSourceRootDirectories(): List<File> {
    val appSrc = File("app/src")
    require(appSrc.exists()) { "Required production source root is missing: ${appSrc.path}" }
    require(appSrc.isDirectory) { "Required production source root is not a directory: ${appSrc.path}" }

    val sourceSets = appSrc.listFiles()
        ?: error("Unable to read production source root directory: ${appSrc.path}")

    return sourceSets.toList()
}

private fun resolveProductionAllowedPaths(allowedPaths: List<String>): List<String> =
    allowedPaths.map { path ->
        if (path.startsWith("/")) path else normalizePath(path)
    }

private fun scanTextFiles(
    files: Sequence<File>,
    forbiddenSimpleNames: Set<String>,
    patterns: List<Regex>,
    allowedPackages: Set<String>
): List<String> = files
    .filterNot { file ->
        val pkg = file.packageName()
        allowedPackages.any { allowed ->
            pkg == allowed || pkg.startsWith("$allowed.")
        }
    }
    .mapNotNull { file ->
        val content = file.readText()
        val matches = forbiddenSimpleNames.zip(patterns)
            .filter { (_, regex) -> regex.containsMatchIn(content) }
            .map { it.first }

        if (matches.isEmpty()) null else "${file.path}:${matches.joinToString(",")}"
    }
    .sorted()
    .toList()

private fun scanTextPaths(
    files: Sequence<File>,
    forbiddenPatterns: List<String>,
    allowedPaths: List<String>
): List<PathPatternMatches> {
    val allowed = allowedPaths.map(::normalizePath)
    return files
        .filter { file -> allowed.none { isAllowedPath(file, it) } }
        .mapNotNull { file ->
            val content = file.readText()
            val matches = forbiddenPatterns.filter { content.contains(it) }
            if (matches.isEmpty()) null else PathPatternMatches(file.path, matches)
        }
        .sortedBy(PathPatternMatches::path)
        .toList()
}

private fun scanRegexPaths(
    files: Sequence<File>,
    forbiddenPatterns: Map<String, Regex>,
    allowedPaths: List<String>
): List<PathPatternMatches> {
    val allowed = allowedPaths.map(::normalizePath)
    return files
        .filter { file -> allowed.none { isAllowedPath(file, it) } }
        .mapNotNull { file ->
            val content = file.readText()
            val matches = forbiddenPatterns.filterValues { regex -> regex.containsMatchIn(content) }.keys.toList()
            if (matches.isEmpty()) null else PathPatternMatches(file.path, matches)
        }
        .sortedBy(PathPatternMatches::path)
        .toList()
}

private fun File.packageName(): String = useLines { lines ->
    lines.firstOrNull { it.startsWith("package ") }
        ?.removePrefix("package ")
        ?.removeSuffix(";")
        ?.trim()
        .orEmpty()
}

private fun isAllowedPath(file: File, allowedPath: String): Boolean {
    val normalizedPath = normalizePath(file.path)
    return when {
        allowedPath.startsWith("contains-all:") -> {
            val fragments = allowedPath.removePrefix("contains-all:").split("::")
            fragments.all(normalizedPath::contains)
        }
        allowedPath.startsWith("/") && allowedPath.endsWith("/") -> normalizedPath.contains(allowedPath)
        allowedPath.startsWith("/") -> normalizedPath.endsWith(allowedPath)
        else -> normalizedPath.startsWith(allowedPath)
    }
}

private fun normalizePath(path: String): String = path.replace(File.separatorChar, '/')
