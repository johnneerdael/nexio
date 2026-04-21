package com.nexio.tv.architecture

import java.io.File

private val boundaryTargets: List<File> = listOf(
    File("app/src/main/java/com/nexio/tv/ui"),
    File("app/src/main/java/com/nexio/tv/workers"),
    File("app/src/main/java/com/nexio/tv/core/tvdb/TvMetadataRouter.kt")
)

fun architectureScan(
    allowedPackages: Set<String>,
    forbiddenSimpleNames: Set<String>
): List<String> {
    val offenders = mutableListOf<String>()
    val patterns = forbiddenSimpleNames.map { Regex("""\b${Regex.escape(it)}\b""") }

    for (file in boundarySourceFiles()) {
        val pkg = file.useLines { lines ->
            lines.firstOrNull { it.startsWith("package ") }
                ?.removePrefix("package ")
                ?.trim()
                .orEmpty()
        }
        if (allowedPackages.any { pkg.startsWith(it) }) continue

        val content = file.readText()
        val matches = forbiddenSimpleNames.zip(patterns)
            .filter { (_, regex) -> regex.containsMatchIn(content) }
            .map { it.first }

        if (matches.isNotEmpty()) {
            offenders += "${file.path}:${matches.joinToString(",")}"
        }
    }

    return offenders.sorted()
}

fun sourceTextScan(
    forbiddenPatterns: List<String>,
    allowedPaths: List<String>
): List<String> {
    val allowed = allowedPaths.map(::normalizePath)
    return File("app/src/main/java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { file -> allowed.none { normalizePath(file.path).startsWith(it) } }
        .mapNotNull { file ->
            val content = file.readText()
            val matches = forbiddenPatterns.filter { content.contains(it) }
            if (matches.isEmpty()) null else "${file.path}:${matches.joinToString(",")}"
        }
        .sorted()
        .toList()
}

private fun boundarySourceFiles(): Sequence<File> =
    boundaryTargets.asSequence().flatMap { target ->
        when {
            !target.exists() -> emptySequence()
            target.isDirectory -> target.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { normalizePath(it.path).contains("/ui/screens/player/") }
                .asSequence()
            else -> sequenceOf(target)
        }
    }

private fun normalizePath(path: String): String = path.replace(File.separatorChar, '/')
