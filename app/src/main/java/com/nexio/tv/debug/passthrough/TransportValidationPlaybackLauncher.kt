package com.nexio.tv.debug.passthrough

import android.content.Context
import android.content.Intent
import android.util.Log
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
        Log.i(TAG, "launchSelectedSample begin sampleId=$sampleId")
        val sample = runCatching {
            Log.i(TAG, "launchSelectedSample loading manifest sampleId=$sampleId")
            manifestLoader.loadFromAssets(context.assets).samples.firstOrNull { it.id == sampleId }
        }.getOrNull() ?: run {
            Log.w(TAG, "Failed to load manifest or find sample sampleId=$sampleId")
            return false
        }
        Log.i(
            TAG,
            "launchSelectedSample manifest resolved sampleId=${sample.id} assetPath=${sample.sourceAssetPath} referenceAsset=${sample.referenceAssetPath}"
        )
        Log.i(TAG, "launchSelectedSample starting session sampleId=${sample.id}")
        val session = sessionStore.startSession(sample.id) ?: run {
            Log.w(TAG, "Failed to start transport validation session sampleId=${sample.id}")
            return false
        }
        Log.i(
            TAG,
            "launchSelectedSample session ready sampleId=${sample.id} referenceBursts=${session.referenceBursts.size}"
        )

        Log.i(TAG, "launchSelectedSample starting activity sampleId=${sample.id}")
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TRANSPORT_VALIDATION_COMMAND, MainActivity.TRANSPORT_VALIDATION_COMMAND_START)
                .putExtra(MainActivity.EXTRA_TRANSPORT_VALIDATION_SAMPLE_ID, sample.id)
                .putExtra(MainActivity.EXTRA_TRANSPORT_VALIDATION_SAMPLE_TITLE, sample.displayName)
                .putExtra(MainActivity.EXTRA_TRANSPORT_VALIDATION_ASSET_PATH, sample.sourceAssetPath)
        )
        Log.i(
            TAG,
            "Launched transport validation sampleId=${sample.id} assetPath=${sample.sourceAssetPath} referenceBursts=${session.referenceBursts.size}"
        )
        return true
    }

    fun stopPlayback() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TRANSPORT_VALIDATION_COMMAND, MainActivity.TRANSPORT_VALIDATION_COMMAND_STOP)
        )
        Log.i(TAG, "Requested transport validation stop")
    }

    companion object {
        private const val TAG = "TransportValidation"
    }
}
