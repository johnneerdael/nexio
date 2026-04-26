package com.nexio.tv.data.remote.api

import com.nexio.tv.BuildConfig
import com.nexio.tv.data.integration.imdb.transport.ImdbSearchRestTransport
import com.nexio.tv.data.integration.imdb.transport.ImdbSearchRestTransportResult
import com.nexio.tv.data.integration.imdb.transport.ImdbSearchWebSocketTransport
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@JsonClass(generateAdapter = true)
data class ImdbSuggestion(
    val tconst: String,
    val titleType: String,
    val primaryTitle: String,
    val startYear: Int? = null
)

interface ImdbSearchService {
    suspend fun search(query: String, types: Set<String> = DEFAULT_TYPES): List<ImdbSuggestion>

    companion object {
        val DEFAULT_TYPES: Set<String> = setOf("movie", "tvSeries")
        const val REST_TIMEOUT_MS: Long = 400L
        const val WS_TIMEOUT_MS: Long = 400L
        const val MAX_RESULTS: Int = 10
        const val CACHE_SIZE: Int = 50
    }
}

class OkHttpImdbSearchService(
    private val imdbSearchRestTransport: ImdbSearchRestTransport,
    private val imdbSearchWebSocketTransport: ImdbSearchWebSocketTransport,
    moshi: Moshi,
    private val restBaseUrl: String = BuildConfig.IMDB_API_URL,
    private val wsUrl: String = BuildConfig.IMDB_WS_URL,
    private val apiKey: String = BuildConfig.IMDB_API_KEY
) : ImdbSearchService {

    private val searchFrameAdapter = moshi.adapter(SearchFrame::class.java)
    private val incomingFrameAdapter = moshi.adapter(IncomingFrame::class.java)
    private val restResponseAdapter = moshi.adapter(RestSearchResponse::class.java)

    private val cache = LruCache<String, List<ImdbSuggestion>>(ImdbSearchService.CACHE_SIZE)
    private val cacheLock = Any()

    private val seqCounter = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<List<ImdbSuggestion>>>()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wsReady: Boolean = false
    @Volatile private var wsDisabled: Boolean = false
    private var reconnectAttempt: Int = 0
    private val connectLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun search(query: String, types: Set<String>): List<ImdbSuggestion> {
        if (query.length < 2) return emptyList()
        if (apiKey.isBlank() || restBaseUrl.isBlank()) return emptyList()

        val cacheKey = buildCacheKey(query, types)
        synchronized(cacheLock) {
            cache.get(cacheKey)?.let { return it }
        }

        val wsResult = if (!wsDisabled && wsUrl.isNotBlank()) {
            runCatching { searchOverWebSocket(query, types) }.getOrNull()
        } else null
        if (wsResult != null) {
            putCache(cacheKey, wsResult)
            return wsResult
        }

        val restResult = runCatching { searchOverRest(query, types) }.getOrNull().orEmpty()
        putCache(cacheKey, restResult)
        return restResult
    }

    private fun putCache(key: String, value: List<ImdbSuggestion>) {
        synchronized(cacheLock) { cache.put(key, value) }
    }

    private fun buildCacheKey(query: String, types: Set<String>): String {
        val sortedTypes = types.sorted().joinToString(",")
        return "$sortedTypes|${query.lowercase()}"
    }

    private suspend fun searchOverWebSocket(query: String, types: Set<String>): List<ImdbSuggestion> {
        val socket = ensureConnection() ?: throw IllegalStateException("WebSocket unavailable")
        val seq = seqCounter.incrementAndGet()
        val deferred = CompletableDeferred<List<ImdbSuggestion>>()
        pending[seq] = deferred
        try {
            val frame = SearchFrame(seq = seq, q = query, types = types.toList())
            val sent = socket.send(searchFrameAdapter.toJson(frame))
            if (!sent) throw IllegalStateException("WebSocket send failed")
            return withTimeout(ImdbSearchService.WS_TIMEOUT_MS) { deferred.await() }
        } finally {
            pending.remove(seq)
        }
    }

    private suspend fun ensureConnection(): WebSocket? {
        ws?.takeIf { wsReady }?.let { return it }
        return connectLock.withLock {
            ws?.takeIf { wsReady }?.let { return@withLock it }
            openConnection()
        }
    }

    private suspend fun openConnection(): WebSocket? {
        val ready = CompletableDeferred<Boolean>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsReady = true
                reconnectAttempt = 0
                if (!ready.isCompleted) ready.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncoming(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsReady = false
                ws = null
                failAllPending(t)
                val code = response?.code
                if (code == 401 || code == 403) wsDisabled = true
                if (!ready.isCompleted) ready.complete(false)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsReady = false
                ws = null
                failAllPending(IllegalStateException("closed: $code"))
                if (!ready.isCompleted) ready.complete(false)
                scheduleReconnect()
            }
        }
        val created = imdbSearchWebSocketTransport.open(
            wsUrl = wsUrl,
            apiKey = apiKey,
            listener = listener
        )
        ws = created
        val opened = withTimeoutOrNull(1_000L) { ready.await() } ?: false
        return if (opened) created else null
    }

    private fun handleIncoming(text: String) {
        val frame = runCatching { incomingFrameAdapter.fromJson(text) }.getOrNull() ?: return
        val seq = frame.seq ?: return
        val deferred = pending[seq] ?: return
        when (frame.type) {
            "result" -> deferred.complete((frame.results ?: emptyList()).take(ImdbSearchService.MAX_RESULTS))
            "cancelled" -> deferred.complete(emptyList())
            "error" -> deferred.completeExceptionally(IllegalStateException(frame.message ?: frame.code ?: "error"))
            "pong" -> Unit
            else -> Unit
        }
    }

    private fun failAllPending(t: Throwable) {
        pending.values.forEach { it.completeExceptionally(t) }
        pending.clear()
    }

    private fun scheduleReconnect() {
        if (wsDisabled) return
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
        val delayMs = (500L shl (reconnectAttempt - 1)).coerceAtMost(10_000L)
        scope.launch {
            delay(delayMs)
            connectLock.withLock {
                if (ws == null && !wsDisabled) openConnection()
            }
        }
    }

    private suspend fun searchOverRest(query: String, types: Set<String>): List<ImdbSuggestion> {
        return withTimeout(ImdbSearchService.REST_TIMEOUT_MS) {
            when (val result = imdbSearchRestTransport.search(
                restBaseUrl = restBaseUrl,
                query = query,
                types = types,
                apiKey = apiKey,
                limit = ImdbSearchService.MAX_RESULTS
            )) {
                is ImdbSearchRestTransportResult.Success -> {
                    val parsed = runCatching { restResponseAdapter.fromJson(result.body) }.getOrNull()
                    return@withTimeout parsed?.results.orEmpty().take(ImdbSearchService.MAX_RESULTS)
                }

                is ImdbSearchRestTransportResult.HttpError,
                is ImdbSearchRestTransportResult.NetworkError -> emptyList()
            }
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class SearchFrame(
        val type: String = "search",
        val seq: Long,
        val q: String,
        val types: List<String>
    )

    @JsonClass(generateAdapter = true)
    internal data class IncomingFrame(
        val type: String,
        val seq: Long? = null,
        val results: List<ImdbSuggestion>? = null,
        val code: String? = null,
        val message: String? = null
    )

    @JsonClass(generateAdapter = true)
    internal data class RestSearchResponse(
        val results: List<ImdbSuggestion> = emptyList(),
        val meta: Meta? = null
    )

    @JsonClass(generateAdapter = true)
    internal data class Meta(
        val snapshotId: Long? = null,
        val count: Int? = null
    )

    private class LruCache<K, V>(private val maxEntries: Int) : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxEntries
    }
}
