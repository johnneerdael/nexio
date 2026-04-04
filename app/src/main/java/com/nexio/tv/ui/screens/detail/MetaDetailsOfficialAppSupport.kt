package com.nexio.tv.ui.screens.detail

import android.app.SearchManager
import android.content.Context
import android.content.Intent

data class OfficialStreamingAppTarget(
    val displayName: String,
    val packageName: String
)

private data class OfficialStreamingAppDefinition(
    val displayName: String,
    val packageNames: List<String>
)

private val OFFICIAL_STREAMING_APPS = listOf(
    OfficialStreamingAppDefinition(
        displayName = "Netflix",
        packageNames = listOf("com.netflix.ninja", "com.netflix.mediaclient")
    ),
    OfficialStreamingAppDefinition(
        displayName = "Prime Video",
        packageNames = listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient")
    ),
    OfficialStreamingAppDefinition(
        displayName = "Disney+",
        packageNames = listOf("com.disney.disneyplus")
    ),
    OfficialStreamingAppDefinition(
        displayName = "Max",
        packageNames = listOf("com.wbd.stream")
    ),
    OfficialStreamingAppDefinition(
        displayName = "Hulu",
        packageNames = listOf("com.hulu.livingroomplus", "com.hulu.plus")
    ),
    OfficialStreamingAppDefinition(
        displayName = "Apple TV",
        packageNames = listOf("com.apple.atve.androidtv.appletv")
    ),
    OfficialStreamingAppDefinition(
        displayName = "Peacock",
        packageNames = listOf("com.peacocktv.peacockandroid")
    ),
    OfficialStreamingAppDefinition(
        displayName = "Crunchyroll",
        packageNames = listOf("com.crunchyroll.crunchyroid")
    )
)

internal fun shouldUseUniversalStreamerMode(installedAddonsCount: Int): Boolean {
    return installedAddonsCount <= 0
}

internal fun resolveInstalledOfficialStreamingApps(
    installedPackages: Set<String>,
    searchablePackages: Set<String>
): List<OfficialStreamingAppTarget> {
    return OFFICIAL_STREAMING_APPS.mapNotNull { definition ->
        definition.packageNames.firstOrNull { it in installedPackages && it in searchablePackages }?.let { packageName ->
            OfficialStreamingAppTarget(
                displayName = definition.displayName,
                packageName = packageName
            )
        }
    }
}

internal fun buildOfficialStreamingAppIntent(
    context: Context,
    target: OfficialStreamingAppTarget,
    title: String
): Intent? {
    val packageManager = context.packageManager
    val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
        setPackage(target.packageName)
        putExtra(SearchManager.QUERY, title)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return searchIntent.takeIf { it.resolveActivity(packageManager) != null }
}

internal fun resolveSearchableStreamingPackages(
    context: Context,
    installedPackages: Set<String>
): Set<String> {
    val packageManager = context.packageManager
    return OFFICIAL_STREAMING_APPS
        .flatMap { it.packageNames }
        .filterTo(linkedSetOf()) { packageName ->
            packageName in installedPackages &&
                Intent(Intent.ACTION_SEARCH).apply {
                    setPackage(packageName)
                    putExtra(SearchManager.QUERY, "test")
                }.resolveActivity(packageManager) != null
        }
}
