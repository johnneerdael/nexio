package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.repository.trakt.TraktProgressMutationExecutor
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Test

class TraktProgressServiceOptimisticRemovalTest {

    @Test
    fun `applyOptimisticRemoval removes episode from shared episode progress snapshot immediately`() {
        val traktAuthService = mockk<TraktAuthService>(relaxed = true) {
            every { currentTraktProfileId() } returns 1
        }
        val service = TraktProgressService(
            traktIntegrationProvider = TraktIntegrationProvider(
                runtime = passThroughTestRuntime(),
                traktApi = mockk<TraktApi>(relaxed = true),
                traktAuthService = traktAuthService
            ),
            traktProgressMutationExecutor = mockk<TraktProgressMutationExecutor>(relaxed = true),
            metaRepository = mockk<MetaRepository>(relaxed = true)
        )

        val contentId = "tt1190634"
        val cacheKey = invokeCanonicalLookupKey(service, contentId)
        val episodeKey = 1 to 8
        val progress = WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = "The Boys",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$contentId:1:8",
            season = 1,
            episode = 8,
            episodeTitle = "You Found Me",
            position = 1L,
            duration = 1L,
            lastWatched = 1L,
            progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
        )

        seedEpisodeProgressSnapshot(service, cacheKey, mapOf(episodeKey to progress))

        service.applyOptimisticRemoval(contentId = contentId, season = 1, episode = 8)

        val state = episodeProgressState(service).value
        val entry = state[cacheKey] ?: error("missing cache entry")
        val progressMap = readProgressMap(entry)
        assertFalse(progressMap.containsKey(episodeKey))
    }

    private fun invokeCanonicalLookupKey(service: TraktProgressService, contentId: String): String {
        val method = TraktProgressService::class.java.getDeclaredMethod("canonicalLookupKey", String::class.java)
        method.isAccessible = true
        return method.invoke(service, contentId) as String
    }

    @Suppress("UNCHECKED_CAST")
    private fun episodeProgressState(service: TraktProgressService): MutableStateFlow<Map<String, Any>> {
        val registryField = TraktProgressService::class.java.getDeclaredField("runtimeRegistry")
        registryField.isAccessible = true
        val registry = registryField.get(service)!!

        val statesField = registry.javaClass.getDeclaredField("states")
        statesField.isAccessible = true
        val states = statesField.get(registry) as MutableMap<Int, Any>

        val runtimeStateClass = Class.forName("com.nexio.tv.data.repository.TraktProgressService\$TraktProgressRuntimeState")
        val runtimeState = states.getOrPut(1) {
            runtimeStateClass.declaredConstructors.first().let { it.isAccessible = true; it.newInstance() }
        }

        val episodeField = runtimeStateClass.getDeclaredField("episodeProgressState")
        episodeField.isAccessible = true
        return episodeField.get(runtimeState) as MutableStateFlow<Map<String, Any>>
    }

    private fun seedEpisodeProgressSnapshot(
        service: TraktProgressService,
        cacheKey: String,
        progress: Map<Pair<Int, Int>, WatchProgress>
    ) {
        val entryClass = Class.forName("com.nexio.tv.data.repository.TraktProgressService\$EpisodeProgressCacheEntry")
        val ctor = entryClass.declaredConstructors.single()
        ctor.isAccessible = true
        val entry = ctor.newInstance(progress, 1L, 1L, true)
        episodeProgressState(service).value = mapOf(cacheKey to entry)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readProgressMap(entry: Any): Map<Pair<Int, Int>, WatchProgress> {
        val field = entry.javaClass.getDeclaredField("progress")
        field.isAccessible = true
        return field.get(entry) as Map<Pair<Int, Int>, WatchProgress>
    }
}
