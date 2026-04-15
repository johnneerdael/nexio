package com.nexio.tv.core.scheduler

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ContinueWatchingAirAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var snapshotService: ContinueWatchingSnapshotService

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_REEVALUATE -> snapshotService.ensureFresh(force = true)
                    Intent.ACTION_BOOT_COMPLETED,
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                        snapshotService.rescheduleAirTimeAlarmFromSnapshot()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
