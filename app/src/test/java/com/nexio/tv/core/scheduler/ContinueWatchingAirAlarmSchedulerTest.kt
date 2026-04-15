package com.nexio.tv.core.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow.extract
import org.robolectric.shadows.ShadowPendingIntent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class ContinueWatchingAirAlarmSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `scheduleSoonest uses exact alarm when permission allows`() {
        val triggerAtMs = 123_456L
        val pendingIntent = slot<PendingIntent>()
        val alarmManager = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            justRun { setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, capture(pendingIntent)) }
        }
        val scheduler = ContinueWatchingAirAlarmScheduler(context, alarmManager)

        scheduler.scheduleSoonest(triggerAtMs)

        verify(exactly = 1) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, any())
        }
        verify(exactly = 0) {
            alarmManager.setAndAllowWhileIdle(any(), any(), any())
        }
        val shadowIntent = extract<ShadowPendingIntent>(pendingIntent.captured)
        assertTrue(shadowIntent.isImmutable)
    }

    @Test
    fun `scheduleSoonest falls back to inexact alarm when exact permission missing`() {
        val triggerAtMs = 234_567L
        val alarmManager = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns false
        }
        val scheduler = ContinueWatchingAirAlarmScheduler(context, alarmManager)

        scheduler.scheduleSoonest(triggerAtMs)

        verify(exactly = 1) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, any())
        }
        verify(exactly = 0) {
            alarmManager.setExactAndAllowWhileIdle(any(), any(), any())
        }
    }

    @Test
    fun `scheduleSoonest null cancels pending alarm`() {
        val alarmManager = mockk<AlarmManager>(relaxed = true)
        val scheduler = ContinueWatchingAirAlarmScheduler(context, alarmManager)

        scheduler.scheduleSoonest(null)

        verify(exactly = 1) {
            alarmManager.cancel(any<PendingIntent>())
        }
    }

    @Test
    fun `pending intent action is package scoped`() {
        val triggerAtMs = 345_678L
        val pendingIntent = slot<PendingIntent>()
        val alarmManager = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            justRun { setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, capture(pendingIntent)) }
        }
        val scheduler = ContinueWatchingAirAlarmScheduler(context, alarmManager)

        scheduler.scheduleSoonest(triggerAtMs)

        val shadowIntent = extract<ShadowPendingIntent>(pendingIntent.captured)
        val savedIntent = shadowIntent.savedIntent
        assertEquals(ACTION_REEVALUATE, savedIntent.action)
        assertEquals(context.packageName, savedIntent.`package`)
        assertEquals(
            "com.nexio.tv.core.scheduler.ContinueWatchingAirAlarmReceiver",
            savedIntent.component?.className
        )
    }
}
