package com.nexio.tv.debug.passthrough

import android.content.Context
import android.content.Intent
import com.nexio.tv.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransportValidationPlaybackLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manifestLoader: TransportValidationManifestLoader,
    private val sessionStore: TransportValidationSessionStore,
) {
    fun launchSelectedSample(sampleId: String): Boolean {
        val sample = runCatching {
            manifestLoader.loadFromAssets(context.assets).samples.firstOrNull { it.id == sampleId }
        }.getOrNull() ?: return false
        sessionStore.startSession(sample.id) ?: return false

        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TRANSPORT_VALIDATION_COMMAND, MainActivity.TRANSPORT_VALIDATION_COMMAND_START)
                .putExtra(MainActivity.EXTRA_TRANSPORT_VALIDATION_SAMPLE_ID, sample.id)
                .putExtra(MainActivity.EXTRA_TRANSPORT_VALIDATION_SAMPLE_TITLE, sample.displayName)
                .putExtra(MainActivity.EXTRA_TRANSPORT_VALIDATION_ASSET_PATH, sample.sourceAssetPath)
        )
        return true
    }

    fun stopPlayback() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TRANSPORT_VALIDATION_COMMAND, MainActivity.TRANSPORT_VALIDATION_COMMAND_STOP)
        )
    }
}
