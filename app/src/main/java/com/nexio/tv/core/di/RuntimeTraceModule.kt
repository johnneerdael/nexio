package com.nexio.tv.core.di

import android.content.Context
import com.google.gson.Gson
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.trace.CompositeRuntimeTraceSink
import com.nexio.tv.core.trace.DataStoreTraceModeProvider
import com.nexio.tv.core.trace.LogcatChannelGate
import com.nexio.tv.core.trace.LogcatRuntimeTraceSink
import com.nexio.tv.core.trace.LogcatTraceChannel
import com.nexio.tv.core.trace.LogcatTraceChannelsProvider
import com.nexio.tv.core.trace.RuntimeTraceContext
import com.nexio.tv.core.trace.RuntimeTraceContextRequestTaggingInterceptor
import com.nexio.tv.core.trace.RuntimeTraceEventListener
import com.nexio.tv.core.trace.RuntimeTraceInterceptor
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceBuildInfo
import com.nexio.tv.core.trace.TraceEventEnvelope
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.core.trace.TraceModeProvider
import com.nexio.tv.core.trace.TraceRedactor
import com.nexio.tv.core.trace.TraceSessionManager
import com.nexio.tv.core.trace.UnscopedNetworkPolicyGuard
import com.nexio.tv.data.local.TraceSettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.EventListener
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RuntimeTraceModule {

    @Provides
    @Singleton
    fun provideTraceBuildInfo(): TraceBuildInfo = TraceBuildInfo(
        appVersion = BuildConfig.VERSION_NAME,
        buildType = BuildConfig.BUILD_TYPE,
        gitSha = BuildConfig.GIT_SHA, // F2-I-01
        deviceModel = android.os.Build.MODEL ?: "unknown",
        androidVersion = android.os.Build.VERSION.RELEASE ?: "unknown"
    )

    @Provides
    @Singleton
    fun provideTraceSessionManager(
        @ApplicationContext context: Context,
        gson: Gson,
        buildInfo: TraceBuildInfo
    ): TraceSessionManager {
        val tracesRoot = File(context.filesDir, "traces").apply { mkdirs() }
        return TraceSessionManager(
            tracesRoot = tracesRoot,
            gson = gson,
            buildInfo = buildInfo
        )
    }

    @Provides
    @Singleton
    fun provideTraceModeProvider(store: TraceSettingsDataStore): TraceModeProvider {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return DataStoreTraceModeProvider(store.mode, scope)
    }

    @Provides
    @Singleton
    fun provideLogcatTraceChannelsProvider(
        store: TraceSettingsDataStore
    ): LogcatTraceChannelsProvider {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return LogcatTraceChannelsProvider(
            firstPaintSource = store.firstPaintLogcatEnabled,
            metaRouteSource = store.metaRouteLogcatEnabled,
            intRuntimeSource = store.intRuntimeLogcatEnabled,
            scope = scope
        )
    }

    @Provides
    @Singleton
    fun provideRuntimeTraceSink(
        manager: TraceSessionManager,
        channels: LogcatTraceChannelsProvider
    ): RuntimeTraceSink {
        val activeSessionSink: RuntimeTraceSink = ActiveSessionRuntimeTraceSink(manager)
        val logcatSink: RuntimeTraceSink = LogcatRuntimeTraceSink(
            gate = object : LogcatChannelGate {
                override fun isEnabled(channel: LogcatTraceChannel): Boolean =
                    channels.isEnabled(channel)
            }
        )
        val sink: RuntimeTraceSink = CompositeRuntimeTraceSink(listOf(activeSessionSink, logcatSink))
        // Side-effect: wire ProfileBoundaryEnforcer's static sink slot so
        // boundary checks emit `profile.boundary_check` events into the active session.
        com.nexio.tv.core.integration.ProfileBoundaryEnforcer.installTraceSink(sink) {
            manager.activeSession()?.traceSessionId
        }
        // Side-effect: wire ContinueWatchingSnapshotService's static sink slot so
        // snapshot writes/reads emit `continue_watching.snapshot_write|read` events.
        com.nexio.tv.data.repository.ContinueWatchingSnapshotService.installTraceSink(sink) {
            manager.activeSession()?.traceSessionId
        }
        return sink
    }

    @Provides
    @Singleton
    fun provideTraceRedactor(): TraceRedactor = TraceRedactor()

    @Provides
    @Singleton
    fun provideTraceMetadataEvents(
        sink: RuntimeTraceSink,
        manager: TraceSessionManager,
        profileManager: com.nexio.tv.core.profile.ProfileManager
    ): TraceMetadataEvents {
        val events = TraceMetadataEvents(
            sink = sink,
            sessionId = { manager.activeSession()?.traceSessionId }
        )
        // Side-effect: wire FirstPaintTracer so MetaPreview.toFirstPaintHomeDisplayMetadata()
        // emits `metadata.first_paint` from the canonical addon-preview boundary.
        com.nexio.tv.core.trace.FirstPaintTracer.install(
            events = events,
            profileHashProvider = {
                val sessionId = manager.activeSession()?.traceSessionId
                if (sessionId == null) {
                    null
                } else {
                    com.nexio.tv.core.trace.TraceHash.of(
                        sessionId,
                        profileManager.activeProfileId.value.toString()
                    )
                }
            }
        )
        return events
    }

    @Provides
    @Singleton
    fun provideUnscopedNetworkPolicyGuard(
        sink: RuntimeTraceSink,
        manager: TraceSessionManager
    ): UnscopedNetworkPolicyGuard = UnscopedNetworkPolicyGuard(
        sink = sink,
        sessionId = { manager.activeSession()?.traceSessionId },
        isInternalBuild = BuildConfig.DEBUG
    )

    @Provides
    @Singleton
    fun provideRuntimeTraceContextRequestTaggingInterceptor(): RuntimeTraceContextRequestTaggingInterceptor =
        RuntimeTraceContextRequestTaggingInterceptor()

    @Provides
    @Singleton
    fun provideRuntimeTraceInterceptor(
        sink: RuntimeTraceSink,
        redactor: TraceRedactor,
        modeProvider: TraceModeProvider,
        unscopedGuard: UnscopedNetworkPolicyGuard
    ): RuntimeTraceInterceptor = RuntimeTraceInterceptor(
        sink = sink,
        redactor = redactor,
        modeProvider = modeProvider,
        unscopedGuard = unscopedGuard,
        isInternalBuild = BuildConfig.DEBUG
    )

    @Provides
    @Singleton
    fun provideRuntimeTraceEventListenerFactory(
        sink: RuntimeTraceSink,
        modeProvider: TraceModeProvider
    ): EventListener.Factory = EventListener.Factory { _ ->
        RuntimeTraceEventListener(
            sink = sink,
            modeProvider = modeProvider,
            contextResolver = { c ->
                c.request().tag(RuntimeTraceContext::class.java)
            }
        )
    }
}

private class ActiveSessionRuntimeTraceSink(
    private val manager: TraceSessionManager
) : RuntimeTraceSink {
    override fun activeTraceSessionId(): String? = manager.activeSession()?.traceSessionId

    override fun emit(event: TraceEventEnvelope<*>) {
        manager.activeSink().emit(event)
    }
    override fun eventsWritten(): Long = manager.activeSink().eventsWritten()
    override fun eventsDropped(): Long = manager.activeSink().eventsDropped()
}
