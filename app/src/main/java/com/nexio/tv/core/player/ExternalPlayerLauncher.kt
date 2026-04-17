package com.nexio.tv.core.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.net.Uri
import android.widget.Toast
import com.nexio.tv.R

data class ExternalPlayerCandidate(
    val packageName: String,
    val label: String
)

object ExternalPlayerDiscovery {
    private const val PROBE_URL = "https://example.com/video.mkv"
    private val IGNORED_RESOLVER_PACKAGES = setOf(
        "com.android.tv.frameworkpackagestubs"
    )

    fun discover(context: Context, url: String = PROBE_URL): List<ExternalPlayerCandidate> {
        val packageManager = context.packageManager
        return packageManager.queryVideoActivities(
            ExternalPlayerLauncher.buildViewIntent(url = url)
        )
            .asSequence()
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                if (packageName in IGNORED_RESOLVER_PACKAGES) return@mapNotNull null

                val label = resolveInfo.loadLabel(packageManager).toString()
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?: packageName
                ExternalPlayerCandidate(packageName = packageName, label = label)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            .toList()
    }
}

object ExternalPlayerLauncher {

    fun buildViewIntent(
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null,
        preferredPackageName: String? = null
    ): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")

            title?.let {
                putExtra("title", it)
                putExtra(Intent.EXTRA_TITLE, it)
            }

            headers?.let { hdrs ->
                if (hdrs.isNotEmpty()) {
                    val headerArray = hdrs.entries.map { "${it.key}: ${it.value}" }.toTypedArray()
                    putExtra("headers", headerArray)
                }
            }

            preferredPackageName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::setPackage)
        }
    }

    fun launch(
        context: Context,
        url: String,
        title: String? = null,
        headers: Map<String, String>? = null,
        preferredPackageName: String? = null
    ): Boolean {
        return try {
            val fallbackIntent = buildViewIntent(
                url = url,
                title = title,
                headers = headers
            )
            val preferredIntent = preferredPackageName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { packageName ->
                    buildViewIntent(
                        url = url,
                        title = title,
                        headers = headers,
                        preferredPackageName = packageName
                    )
                }
                ?.takeIf { it.canResolve(context.packageManager) }
            val intent = preferredIntent ?: fallbackIntent

            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.player_no_external_player),
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }
}

private fun Intent.canResolve(packageManager: PackageManager): Boolean {
    return packageManager.queryVideoActivities(this).isNotEmpty()
}

private fun PackageManager.queryVideoActivities(intent: Intent): List<ResolveInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
}
