package com.nexio.tv.core.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

const val ACTION_REEVALUATE = "com.nexio.tv.action.CONTINUE_WATCHING_AIR_REEVALUATE"

private const val REQUEST_CODE = 8408

class ContinueWatchingAirAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager
) : ContinueWatchingAirScheduler {

    override fun scheduleSoonest(triggerAtMs: Long?) {
        if (triggerAtMs == null) {
            cancel()
            return
        }

        val operation = pendingIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.i(
                "ContinueWatching",
                "exact_air_time_scheduler mode=inexact_alarm triggerAtMs=$triggerAtMs"
            )
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
            return
        }

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
    }

    override fun cancel() {
        alarmManager.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, ContinueWatchingAirAlarmReceiver::class.java)
            .setAction(ACTION_REEVALUATE)
            .setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
