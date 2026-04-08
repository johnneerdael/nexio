package com.nexio.tv.instrumentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Secondary ADB control plane for the playback-trace feature.
 *
 * This receiver is exported in the manifest so `adb shell am broadcast`
 * can reach it without any special permission, but every action is gated
 * by [PlaybackTraceAdbControlToggle]. With the toggle off (the default),
 * every incoming broadcast is silently ignored — no log spam, no state
 * change. Only after the user explicitly enables "Allow ADB control" in
 * the debrid settings menu will commands actually take effect.
 *
 * All work goes through [PlaybackTraceController], the single source of
 * truth the in-app UI also uses. ADB commands cannot do anything the UI
 * can't do.
 *
 * ### Supported actions
 *
 *  - `com.nexio.tv.action.PLAYBACK_TRACE_ENABLE`       — turn tracer on
 *  - `com.nexio.tv.action.PLAYBACK_TRACE_DISABLE`      — turn tracer off
 *  - `com.nexio.tv.action.PLAYBACK_TRACE_STATUS`       — refresh status snapshot
 *  - `com.nexio.tv.action.PLAYBACK_TRACE_EXPORT_LAST`  — share latest via FileProvider
 *  - `com.nexio.tv.action.PLAYBACK_TRACE_EXPORT_ALL`   — share zip via FileProvider
 *  - `com.nexio.tv.action.PLAYBACK_TRACE_CLEAR`        — delete every retained JSONL
 *
 * ### Example
 *
 * ```
 * adb shell am broadcast -a com.nexio.tv.action.PLAYBACK_TRACE_ENABLE \
 *   -n com.nexio.tv/com.nexio.tv.instrumentation.PlaybackTraceAdbReceiver
 * ```
 *
 * Logs every accepted + rejected broadcast to logcat tag `PlaybackTraceAdb`.
 */
@AndroidEntryPoint
class PlaybackTraceAdbReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: PlaybackTraceController
    @Inject lateinit var adbControlToggle: PlaybackTraceAdbControlToggle

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                // Runtime opt-in gate. Off by default; the user must flip
                // "Allow ADB control" in the debrid settings menu.
                val allowed = adbControlToggle.isEnabled()
                if (!allowed) {
                    Log.i(
                        TAG,
                        "Rejected $action — ADB control is disabled (opt-in from debrid settings)",
                    )
                    return@launch
                }
                handle(context, action)
            } catch (t: Throwable) {
                Log.w(TAG, "Error handling $action", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(context: Context, action: String) {
        when (action) {
            ACTION_ENABLE -> {
                controller.setEnabled(true)
                Log.i(TAG, "ADB: tracer enabled")
            }
            ACTION_DISABLE -> {
                controller.setEnabled(false)
                Log.i(TAG, "ADB: tracer disabled")
            }
            ACTION_STATUS -> {
                controller.refreshStatus()
                val status = controller.statusFlow.value
                Log.i(
                    TAG,
                    "ADB: status enabled=${status.enabled} sessions=${status.sessionCount} " +
                        "bytes=${status.totalBytes} last=${status.lastSessionFileName}",
                )
            }
            ACTION_EXPORT_LAST -> {
                val intent = controller.exportLast()
                if (intent != null) {
                    // Fire as a new task so it works from a background context.
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(Intent.createChooser(intent, "Export last trace").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    Log.i(TAG, "ADB: export_last chooser launched")
                } else {
                    Log.i(TAG, "ADB: export_last — no trace available")
                }
            }
            ACTION_EXPORT_ALL -> {
                val intent = controller.exportAll()
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(Intent.createChooser(intent, "Export all traces").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    Log.i(TAG, "ADB: export_all chooser launched")
                } else {
                    Log.i(TAG, "ADB: export_all — no traces available")
                }
            }
            ACTION_CLEAR -> {
                val deleted = controller.clearAll()
                Log.i(TAG, "ADB: clear — deleted $deleted file(s)")
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackTraceAdb"

        const val ACTION_ENABLE = "com.nexio.tv.action.PLAYBACK_TRACE_ENABLE"
        const val ACTION_DISABLE = "com.nexio.tv.action.PLAYBACK_TRACE_DISABLE"
        const val ACTION_STATUS = "com.nexio.tv.action.PLAYBACK_TRACE_STATUS"
        const val ACTION_EXPORT_LAST = "com.nexio.tv.action.PLAYBACK_TRACE_EXPORT_LAST"
        const val ACTION_EXPORT_ALL = "com.nexio.tv.action.PLAYBACK_TRACE_EXPORT_ALL"
        const val ACTION_CLEAR = "com.nexio.tv.action.PLAYBACK_TRACE_CLEAR"

        private val SUPPORTED_ACTIONS: Set<String> = setOf(
            ACTION_ENABLE,
            ACTION_DISABLE,
            ACTION_STATUS,
            ACTION_EXPORT_LAST,
            ACTION_EXPORT_ALL,
            ACTION_CLEAR,
        )
    }
}
