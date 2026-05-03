package com.nexio.tv.core.di

import android.content.Context
import android.util.Log
import com.nexio.tv.core.metadata.MetadataProviderConfig
import com.nexio.tv.BuildConfig
import com.nexio.tv.core.logging.sanitizeRequestTargetForLogs
import com.nexio.tv.core.integration.IntegrationHostClassifier
import com.nexio.tv.core.integration.IntegrationNetworkPermitInterceptor
import com.nexio.tv.core.player.auth.AuthRecoveryInterceptor
import com.nexio.tv.core.player.auth.EgressIpFingerprint
import com.nexio.tv.core.player.auth.PlaybackAuthFingerprintHolder
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.data.remote.api.AniSkipApi
import com.nexio.tv.data.remote.api.AnimeSkipApi
import com.nexio.tv.data.remote.api.ArmApi
import com.nexio.tv.data.integration.imdb.transport.ImdbSearchRestTransport
import com.nexio.tv.data.integration.imdb.transport.ImdbSearchWebSocketTransport
import com.nexio.tv.data.integration.imdb.transport.OkHttpImdbSearchRestTransport
import com.nexio.tv.data.integration.imdb.transport.OkHttpImdbSearchWebSocketTransport
import com.nexio.tv.data.integration.debrid.transport.DirectDiscardBenchmarkTransport
import com.nexio.tv.data.integration.youtube.transport.OkHttpYouTubeTrailerTransport
import com.nexio.tv.data.integration.youtube.transport.YouTubeTrailerTransport
import com.nexio.tv.data.remote.CustomImdbClient
import com.nexio.tv.data.remote.api.EasyDebridApi
import com.nexio.tv.data.remote.api.GitHubReleaseApi
import com.nexio.tv.data.remote.api.ImdbSearchService
import com.nexio.tv.data.remote.api.OkHttpImdbSearchService
import com.nexio.tv.data.repository.benchmark.DebridBenchmarkTransport
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.api.IntroDbApi
import com.nexio.tv.data.remote.api.KitsuApi
import com.nexio.tv.data.remote.api.KitsuAuthApi
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.OkHttpCustomImdbClient
import com.nexio.tv.data.integration.imdb.CustomImdbRatingsIntegrationProvider
import com.nexio.tv.data.remote.api.OmdbApi
import com.nexio.tv.data.remote.api.PremiumizeApi
import com.nexio.tv.data.remote.api.RealDebridApi
import com.nexio.tv.data.remote.api.RpdbApi
import com.nexio.tv.data.remote.api.SimklApi
import com.nexio.tv.data.remote.api.TrailerApi
import com.nexio.tv.data.remote.api.TopPostersApi
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TorBoxApi
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.core.network.IPv4FirstDns
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Named
import javax.inject.Singleton

internal const val NEXIO_PLAYBACK_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private const val KITSU_BROWSER_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
private const val KITSU_JSON_API_MEDIA_TYPE = "application/vnd.api+json"
private const val KITSU_BROWSER_ACCEPT_LANGUAGE = "en-US,en;q=0.9"
private const val KITSU_BROWSER_ORIGIN = "https://kitsu.io"
private const val KITSU_BROWSER_REFERER = "https://kitsu.io/"

private object TraktHttpTrace {
    private val requestCounter = AtomicLong(0L)
    fun nextRequestId(): Long = requestCounter.incrementAndGet()
}

private fun OkHttpClient.Builder.addKitsuBrowserHeaders(
    includeJsonApiHeaders: Boolean
): OkHttpClient.Builder {
    return addInterceptor { chain ->
        val request = chain.request()
        val requestBuilder = request.newBuilder()
            .header("User-Agent", KITSU_BROWSER_USER_AGENT)
            .header("Accept-Language", KITSU_BROWSER_ACCEPT_LANGUAGE)
            .header("Origin", KITSU_BROWSER_ORIGIN)
            .header("Referer", KITSU_BROWSER_REFERER)
        if (includeJsonApiHeaders) {
            requestBuilder
                .header("Accept", KITSU_JSON_API_MEDIA_TYPE)
                .header("Content-Type", KITSU_JSON_API_MEDIA_TYPE)
        }
        chain.proceed(requestBuilder.build())
    }
}

private fun OkHttpClient.Builder.disableDiskCacheForGetRequests(): OkHttpClient.Builder {
    return this
        .cache(null)
        .addInterceptor { chain ->
            val request = chain.request()
            val requestBuilder = request.newBuilder()
            if (request.method.equals("GET", ignoreCase = true)) {
                requestBuilder
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
            }
            chain.proceed(requestBuilder.build())
        }
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (!request.method.equals("GET", ignoreCase = true)) {
                return@addNetworkInterceptor response
            }
            response.newBuilder()
                .removeHeader("Pragma")
                .header("Cache-Control", "no-store")
                .build()
        }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideEgressIpFingerprint(
        okHttpClient: OkHttpClient
    ): EgressIpFingerprint = EgressIpFingerprint(
        client = okHttpClient,
        probeUrl = "https://api.ipify.org/"
    ).also { PlaybackAuthFingerprintHolder.setInstance(it) }

    @Provides
    @Singleton
    fun provideAuthRecoveryInterceptor(): AuthRecoveryInterceptor =
        AuthRecoveryInterceptor()

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        taggingInterceptor: com.nexio.tv.core.trace.RuntimeTraceContextRequestTaggingInterceptor,
        traceInterceptor: com.nexio.tv.core.trace.RuntimeTraceInterceptor,
        traceEventListenerFactory: okhttp3.EventListener.Factory
    ): OkHttpClient = OkHttpClient.Builder()
        // F2-A-02: IntegrationNetworkPermitInterceptor — AUDIT_ONLY vs ENFORCE migration plan
        //
        // Current mode: AUDIT_ONLY
        //   In AUDIT_ONLY, any in-scope provider call that lacks an IntegrationRuntime permit
        //   (i.e. was issued outside of DefaultIntegrationRuntime.open()) is silently allowed
        //   through. The interceptor logs/traces the violation but does not block the request.
        //
        // What ENFORCE would change:
        //   Mode.ENFORCE throws IllegalStateException for any permit-less in-scope request.
        //   This hard-enforces that all provider network calls must go through the integration
        //   runtime (with proper backoff, tracing, and cache-policy enforcement).
        //
        // Migration path — flip to ENFORCE when:
        //   1. All call sites that bypass the runtime have been ported to IntegrationStreamSpec
        //      (tracked by the integration-runtime-phase-* tracks).
        //   2. The AUDIT_ONLY warning metric drops to zero across a full regression pass.
        //   3. A dedicated integration test (IntegrationNetworkPermitInterceptorTest) passes
        //      in ENFORCE mode with the full set of known API shapes.
        //   Expected milestone: integration-runtime-phase-c or later.
        .addInterceptor(
            IntegrationNetworkPermitInterceptor(
                hostClassifier = IntegrationHostClassifier.default(),
                mode = IntegrationNetworkPermitInterceptor.Mode.AUDIT_ONLY
            )
        )
        // Application interceptor: bridges the coroutine-scoped RuntimeTraceContext (carried
        // via thread-local by RuntimeTraceContextElement) onto the OkHttp Request as a tag,
        // so the trace network interceptor can read it. MUST run on the calling thread (i.e.
        // be an application interceptor) since the thread-local is set there.
        .addInterceptor(taggingInterceptor)
        // Trace interceptor is added as a NETWORK interceptor so it observes the final
        // outgoing request shape — including headers added by app-level interceptors on
        // derived clients (e.g. auth interceptors added via newBuilder()).
        .addNetworkInterceptor(traceInterceptor)
        .eventListenerFactory(traceEventListenerFactory)
        .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024 * 1024)) // 50 MB disk cache
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Call timeout for playback OkHttp requests (ms).
     *
     * Chosen to survive a 64 MiB prefetch chunk on a slow link:
     *   64 MiB ÷ 5 Mbps ≈ 102 s → rounded up to 120 s with margin.
     *
     * Overridable by injecting a different value in tests via a test Hilt module.
     */
    @Provides
    @Named("playback.callTimeoutMs")
    fun providePlaybackCallTimeoutMs(): Long = 120_000L

    /**
     * Shared OkHttpClient for all playback and benchmark traffic.
     *
     * Both playback ([PlayerMediaSourceFactory]) and benchmark transports derive their clients
     * from this instance via [OkHttpClient.newBuilder], which means they share the same
     * [ConnectionPool] and [Dispatcher] — connection pool reuse is the key win.
     *
     * [Dispatcher.maxRequestsPerHost] is set to 12 (the playback value) rather than the
     * OkHttp default of 5. The locked-envelope work verified that 12 concurrent connections
     * per host does not break RD/PM CDN behaviour; keeping it at 5 for benchmark would
     * have undersold per-host concurrency and made benchmark numbers incomparable to playback.
     */
    @Provides
    @Singleton
    @Named("playback")
    fun providePlaybackOkHttpClient(
        @ApplicationContext context: Context,
        @Named("playback.callTimeoutMs") callTimeoutMs: Long,
        taggingInterceptor: com.nexio.tv.core.trace.RuntimeTraceContextRequestTaggingInterceptor,
        traceInterceptor: com.nexio.tv.core.trace.RuntimeTraceInterceptor,
        traceEventListenerFactory: okhttp3.EventListener.Factory,
        authRecoveryInterceptor: AuthRecoveryInterceptor
    ): OkHttpClient {
        // Shared dispatcher: maxRequestsPerHost=12 proved safe by locked-envelope work.
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 12
        }
        // Shared connection pool: 5 idle connections, 5-minute keep-alive.
        val connectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .eventListenerFactory(traceEventListenerFactory)
            // Application interceptor: bridges the coroutine-scoped RuntimeTraceContext onto
            // the OkHttp Request tag so the trace network interceptor below can read it. MUST
            // run on the calling thread (application interceptor) where the thread-local is set.
            .addInterceptor(taggingInterceptor)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val response = chain.proceed(originalRequest)
                if (response.isRedirect) {
                    val location = response.header("Location") ?: return@addInterceptor response
                    val newRequest = originalRequest.newBuilder()
                        .url(location)
                        .build()
                    response.close()
                    return@addInterceptor chain.proceed(newRequest)
                }
                response
            }
            .addInterceptor { chain ->
                val original = chain.request()
                val request = if (original.header("User-Agent") == null) {
                    original.newBuilder().header("User-Agent", NEXIO_PLAYBACK_USER_AGENT).build()
                } else {
                    original
                }
                chain.proceed(request)
            }
            .addInterceptor(authRecoveryInterceptor)
            // Trace interceptor as NETWORK interceptor so it observes the final outgoing
            // request shape — including headers added by app interceptors on derived clients.
            .addNetworkInterceptor(traceInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("trakt")
    fun provideTraktOkHttpClient(
        okHttpClient: OkHttpClient
    ): OkHttpClient {
        // Rate limiting is now handled by TraktRequestGate at the coroutine layer (500ms serial
        // queue) instead of Thread.sleep in this interceptor. This interceptor only injects
        // required headers and provides debug logging.
        return okHttpClient.newBuilder()
            .disableDiskCacheForGetRequests()
            .addInterceptor { chain ->
                val request = chain.request()
                val version = BuildConfig.VERSION_NAME.ifBlank { "dev" }

                val requestBuilder = request.newBuilder()
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "NEXIO/$version")
                    .header("trakt-api-key", BuildConfig.TRAKT_CLIENT_ID)
                    .header("trakt-api-version", "2")
                val newRequest = requestBuilder.build()

                if (!BuildConfig.DEBUG) {
                    return@addInterceptor chain.proceed(newRequest)
                }

                val requestId = TraktHttpTrace.nextRequestId()
                val target = sanitizeRequestTargetForLogs(
                    encodedPath = newRequest.url.encodedPath,
                    encodedQuery = newRequest.url.encodedQuery
                )
                val startNs = System.nanoTime()
                Log.d("TraktHttp", "REQ #$requestId ${newRequest.method} $target")

                try {
                    val response = chain.proceed(newRequest)
                    val durationMs = (System.nanoTime() - startNs) / 1_000_000L
                    val retryAfter = response.header("Retry-After")
                    val rateLimit = response.header("X-Ratelimit")
                    val page = response.header("X-Pagination-Page")
                    val pageCount = response.header("X-Pagination-Page-Count")
                    val pageInfo = if (page != null || pageCount != null) {
                        " page=${page ?: "-"} pageCount=${pageCount ?: "-"}"
                    } else {
                        ""
                    }
                    val retryInfo = retryAfter?.let { " retryAfter=${it}s" } ?: ""
                    val rateInfo = rateLimit?.let { " rate=$it" } ?: ""
                    Log.d(
                        "TraktHttp",
                        "RES #$requestId ${response.code} ${newRequest.method} $target ${durationMs}ms$retryInfo$pageInfo$rateInfo"
                    )
                    response
                } catch (error: Exception) {
                    val durationMs = (System.nanoTime() - startNs) / 1_000_000L
                    Log.w(
                        "TraktHttp",
                        "ERR #$requestId ${newRequest.method} $target ${durationMs}ms ${error.javaClass.simpleName}: ${error.message}"
                    )
                    throw error
                }
            }
            .build()
    }

    @Provides
    @Singleton
    @Named("simkl")
    fun provideSimklOkHttpClient(
        okHttpClient: OkHttpClient
    ): OkHttpClient {
        // Rate limiting is now handled by SimklRequestGate at the coroutine layer (500ms serial
        // queue) instead of Thread.sleep in this interceptor. This interceptor only injects
        // required headers and query parameters.
        return okHttpClient.newBuilder()
            .disableDiskCacheForGetRequests()
            .addInterceptor { chain ->
                val request = chain.request()
                val version = BuildConfig.VERSION_NAME.ifBlank { "dev" }
                val appName = "NEXIO"

                val urlBuilder = request.url.newBuilder()
                if (request.url.queryParameter("client_id").isNullOrBlank() && BuildConfig.SIMKL_CLIENT_ID.isNotBlank()) {
                    urlBuilder.addQueryParameter("client_id", BuildConfig.SIMKL_CLIENT_ID)
                }
                if (request.url.queryParameter("app-name").isNullOrBlank()) {
                    urlBuilder.addQueryParameter("app-name", appName)
                }
                if (request.url.queryParameter("app-version").isNullOrBlank()) {
                    urlBuilder.addQueryParameter("app-version", version)
                }
                val updatedRequest = request.newBuilder()
                    .url(urlBuilder.build())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "$appName/$version")
                    .header("simkl-api-key", BuildConfig.SIMKL_CLIENT_ID)
                    .build()
                chain.proceed(updatedRequest)
            }
            .build()
    }

    @Provides
    @Singleton
    @Named("addonCatalog")
    fun provideAddonCatalogOkHttpClient(
        okHttpClient: OkHttpClient
    ): OkHttpClient = okHttpClient.newBuilder()
        // Catalog/meta rails have app-level cache semantics; bypass OkHttp disk cache for freshness.
        .disableDiskCacheForGetRequests()
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(@Named("addonCatalog") okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://placeholder.Nexio.tv/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("addonStreams")
    fun provideAddonStreamsOkHttpClient(
        okHttpClient: OkHttpClient
    ): OkHttpClient = okHttpClient.newBuilder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 128
                maxRequestsPerHost = 32
            }
        )
        .build()

    /**
     * Benchmark OkHttpClient — derives from the shared playback client via [newBuilder] so that
     * both benchmark and playback share the same [ConnectionPool] and [Dispatcher]. Only the
     * benchmark-specific call timeout (4 min) and cache bypass are applied on top.
     */
    @Provides
    @Singleton
    @Named("benchmark")
    fun provideBenchmarkOkHttpClient(
        @Named("playback") playbackClient: OkHttpClient
    ): OkHttpClient = playbackClient.newBuilder()
        .disableDiskCacheForGetRequests()
        .callTimeout(4, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideDebridBenchmarkTransport(
        @Named("benchmark") okHttpClient: OkHttpClient
    ): DebridBenchmarkTransport = DirectDiscardBenchmarkTransport(okHttpClient)

    @Provides
    @Singleton
    @Named("addonStreams")
    fun provideAddonStreamsRetrofit(
        @Named("addonStreams") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://placeholder.Nexio.tv/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("tmdb")
    fun provideTmdbRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(MetadataProviderConfig.tmdbBaseUrl())
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("tvdb")
    fun provideTvdbRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(MetadataProviderConfig.tvdbBaseUrl())
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("kitsu")
    fun provideKitsuOkHttpClient(
        okHttpClient: OkHttpClient
    ): OkHttpClient = okHttpClient.newBuilder()
        .disableDiskCacheForGetRequests()
        .addKitsuBrowserHeaders(includeJsonApiHeaders = true)
        .build()

    @Provides
    @Singleton
    @Named("kitsuOauth")
    fun provideKitsuOauthOkHttpClient(
        okHttpClient: OkHttpClient
    ): OkHttpClient = okHttpClient.newBuilder()
        .addKitsuBrowserHeaders(includeJsonApiHeaders = false)
        .build()

    @Provides
    @Singleton
    @Named("kitsu")
    fun provideKitsuRetrofit(@Named("kitsu") okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://kitsu.io/api/edge/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("kitsuOauth")
    fun provideKitsuOauthRetrofit(@Named("kitsuOauth") okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://kitsu.io/api/oauth/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("trakt")
    fun provideTraktRetrofit(
        @Named("trakt") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.TRAKT_API_URL.ifBlank { "https://api.trakt.tv/" })
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    @Named("simkl")
    fun provideSimklRetrofit(
        @Named("simkl") okHttpClient: OkHttpClient,
        moshi: Moshi
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.SIMKL_API_URL.ifBlank { "https://api.simkl.com/" })
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideAddonApi(retrofit: Retrofit): AddonApi =
        retrofit.create(AddonApi::class.java)

    @Provides
    @Singleton
    @Named("addonStreams")
    fun provideAddonStreamsApi(
        @Named("addonStreams") retrofit: Retrofit
    ): AddonApi = retrofit.create(AddonApi::class.java)

    @Provides
    @Singleton
    fun provideTmdbApi(@Named("tmdb") retrofit: Retrofit): TmdbApi =
        retrofit.create(TmdbApi::class.java)

    @Provides
    @Singleton
    fun provideTvdbApi(@Named("tvdb") retrofit: Retrofit): TvdbApi =
        retrofit.create(TvdbApi::class.java)

    @Provides
    @Singleton
    fun provideKitsuApi(@Named("kitsu") retrofit: Retrofit): KitsuApi =
        retrofit.create(KitsuApi::class.java)

    @Provides
    @Singleton
    fun provideKitsuAuthApi(@Named("kitsuOauth") retrofit: Retrofit): KitsuAuthApi =
        retrofit.create(KitsuAuthApi::class.java)

    @Provides
    @Singleton
    fun provideTraktApi(@Named("trakt") retrofit: Retrofit): TraktApi =
        retrofit.create(TraktApi::class.java)


    @Provides
    @Singleton
    fun provideSimklApi(@Named("simkl") retrofit: Retrofit): SimklApi =
        retrofit.create(SimklApi::class.java)

    // --- Skip Intro APIs ---

    @Provides
    @Singleton
    @Named("introDb")
    fun provideIntroDbRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.INTRODB_API_URL.ifEmpty { "https://api.theintrodb.org/v2/" })
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideIntroDbApi(@Named("introDb") retrofit: Retrofit): IntroDbApi =
        retrofit.create(IntroDbApi::class.java)

    @Provides
    @Singleton
    @Named("trailer")
    fun provideTrailerRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.TRAILER_API_URL.ifEmpty { "https://localhost/" })
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideTrailerApi(@Named("trailer") retrofit: Retrofit): TrailerApi =
        retrofit.create(TrailerApi::class.java)

    // --- Wyzie subtitles ---

    @Provides
    @Singleton
    @Named("wyzie")
    fun provideWyzieOkHttpClient(
        okHttpClient: OkHttpClient,
        wyzieKeyInterceptor: com.nexio.tv.data.integration.subtitles.wyzie.transport.WyzieKeyInterceptor,
    ): OkHttpClient {
        return okHttpClient.newBuilder()
            .addInterceptor(wyzieKeyInterceptor)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("wyzie")
    fun provideWyzieRetrofit(
        @Named("wyzie") okHttpClient: OkHttpClient,
        moshi: Moshi,
    ): Retrofit {
        // Add the source string-or-array adapter to a per-call Moshi instance so it's not
        // shared with other Retrofit clients that don't expect it.
        val wyzieMoshi = moshi.newBuilder()
            .add(com.nexio.tv.data.remote.dto.WyzieSourceJsonAdapter())
            .build()
        return Retrofit.Builder()
            .baseUrl("https://sub.wyzie.io/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(wyzieMoshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideWyzieSubtitleApi(
        @Named("wyzie") retrofit: Retrofit,
    ): com.nexio.tv.data.integration.subtitles.wyzie.transport.WyzieSubtitleApi =
        retrofit.create(com.nexio.tv.data.integration.subtitles.wyzie.transport.WyzieSubtitleApi::class.java)

    // --- OpenSubtitles REST subtitles ---

    @Provides
    @Singleton
    @Named("openSubtitles")
    fun provideOpenSubtitlesRetrofit(
        okHttpClient: OkHttpClient,
        moshi: Moshi,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl("${com.nexio.tv.data.remote.api.OpenSubtitlesApiClient.DEFAULT_BASE_URL}/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideOpenSubtitlesRestApi(
        @Named("openSubtitles") retrofit: Retrofit,
    ): com.nexio.tv.data.remote.api.OpenSubtitlesRestApi =
        retrofit.create(com.nexio.tv.data.remote.api.OpenSubtitlesRestApi::class.java)

    @Provides
    @Singleton
    fun provideOpenSubtitlesApiClient(
        api: com.nexio.tv.data.remote.api.OpenSubtitlesRestApi,
    ): com.nexio.tv.data.remote.api.OpenSubtitlesApiClient =
        com.nexio.tv.data.remote.api.OpenSubtitlesApiClient(
            api = api,
            userAgent = NEXIO_PLAYBACK_USER_AGENT,
        )

    @Provides
    @Singleton
    @Named("aniSkip")
    fun provideAniSkipRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.aniskip.com/v2/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideAniSkipApi(@Named("aniSkip") retrofit: Retrofit): AniSkipApi =
        retrofit.create(AniSkipApi::class.java)

    @Provides
    @Singleton
    @Named("arm")
    fun provideArmRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://arm.haglund.dev/api/v2/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideArmApi(@Named("arm") retrofit: Retrofit): ArmApi =
        retrofit.create(ArmApi::class.java)

    @Provides
    @Singleton
    @Named("animeSkipGql")
    fun provideAnimeSkipGqlRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.anime-skip.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideAnimeSkipApi(@Named("animeSkipGql") retrofit: Retrofit): AnimeSkipApi =
        retrofit.create(AnimeSkipApi::class.java)

    // --- GitHub Releases API (in-app updates) ---

    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideGitHubReleaseApi(@Named("github") retrofit: Retrofit): GitHubReleaseApi =
        retrofit.create(GitHubReleaseApi::class.java)

    // --- MDBList API ---

    @Provides
    @Singleton
    @Named("mdblist")
    fun provideMDBListOkHttpClient(
        okHttpClient: OkHttpClient
    ): OkHttpClient = okHttpClient.newBuilder()
        // MDBList discovery rows should refresh from network and update snapshot cache promptly.
        .disableDiskCacheForGetRequests()
        .build()

    @Provides
    @Singleton
    @Named("mdblist")
    fun provideMDBListRetrofit(@Named("mdblist") okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.mdblist.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideMDBListApi(@Named("mdblist") retrofit: Retrofit): MDBListApi =
        retrofit.create(MDBListApi::class.java)

    // --- OMDB API ---

    @Provides
    @Singleton
    @Named("omdb")
    fun provideOmdbRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://www.omdbapi.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideOmdbApi(@Named("omdb") retrofit: Retrofit): OmdbApi =
        retrofit.create(OmdbApi::class.java)

    @Provides
    @Singleton
    fun provideCustomImdbClient(
        integrationProvider: CustomImdbRatingsIntegrationProvider,
        moshi: Moshi
    ): CustomImdbClient = OkHttpCustomImdbClient(
        integrationProvider = integrationProvider,
        moshi = moshi
    )

    // --- Debrid APIs ---

    @Provides
    @Singleton
    @Named("realDebrid")
    fun provideRealDebridRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.real-debrid.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideRealDebridApi(@Named("realDebrid") retrofit: Retrofit): RealDebridApi =
        retrofit.create(RealDebridApi::class.java)

    @Provides
    @Singleton
    @Named("premiumize")
    fun providePremiumizeRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://www.premiumize.me/api/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun providePremiumizeApi(@Named("premiumize") retrofit: Retrofit): PremiumizeApi =
        retrofit.create(PremiumizeApi::class.java)

    @Provides
    @Singleton
    @Named("torBox")
    fun provideTorBoxRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.torbox.app/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideTorBoxApi(@Named("torBox") retrofit: Retrofit): TorBoxApi =
        retrofit.create(TorBoxApi::class.java)

    @Provides
    @Singleton
    @Named("easyDebrid")
    fun provideEasyDebridRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://easydebrid.com/api/v1/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideEasyDebridApi(@Named("easyDebrid") retrofit: Retrofit): EasyDebridApi =
        retrofit.create(EasyDebridApi::class.java)

    // --- Poster ratings APIs ---

    @Provides
    @Singleton
    @Named("rpdb")
    fun provideRpdbRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.ratingposterdb.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideRpdbApi(@Named("rpdb") retrofit: Retrofit): RpdbApi =
        retrofit.create(RpdbApi::class.java)

    @Provides
    @Singleton
    @Named("topPosters")
    fun provideTopPostersRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://api.top-posters.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideTopPostersApi(@Named("topPosters") retrofit: Retrofit): TopPostersApi =
        retrofit.create(TopPostersApi::class.java)

    @Provides
    @Singleton
    fun provideImdbSearchRestTransport(
        okHttpClient: OkHttpClient,
    ): ImdbSearchRestTransport = OkHttpImdbSearchRestTransport(okHttpClient = okHttpClient)

    @Provides
    @Singleton
    fun provideImdbSearchWebSocketTransport(
        okHttpClient: OkHttpClient
    ): ImdbSearchWebSocketTransport = OkHttpImdbSearchWebSocketTransport(okHttpClient = okHttpClient)

    @Provides
    @Singleton
    @Named("youtubeTrailer.main")
    fun provideYouTubeTrailerMainOkHttpClient(
        taggingInterceptor: com.nexio.tv.core.trace.RuntimeTraceContextRequestTaggingInterceptor,
        traceInterceptor: com.nexio.tv.core.trace.RuntimeTraceInterceptor
    ): OkHttpClient =
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // F-I-05: tag request with coroutine-scoped trace context (application interceptor),
            // then observe outgoing request shape (network interceptor) — same pattern as base provider.
            .addInterceptor(taggingInterceptor)
            .addNetworkInterceptor(traceInterceptor)
            .build()

    @Provides
    @Singleton
    @Named("youtubeTrailer.probe")
    fun provideYouTubeTrailerProbeOkHttpClient(
        taggingInterceptor: com.nexio.tv.core.trace.RuntimeTraceContextRequestTaggingInterceptor,
        traceInterceptor: com.nexio.tv.core.trace.RuntimeTraceInterceptor
    ): OkHttpClient =
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // F-I-05: same trace wiring as the main client.
            .addInterceptor(taggingInterceptor)
            .addNetworkInterceptor(traceInterceptor)
            .build()

    @Provides
    @Singleton
    fun provideYouTubeTrailerTransport(
        @Named("youtubeTrailer.main") mainClient: OkHttpClient,
        @Named("youtubeTrailer.probe") probeClient: OkHttpClient
    ): YouTubeTrailerTransport = OkHttpYouTubeTrailerTransport(
        mainClient = mainClient,
        probeClient = probeClient
    )

    @Provides
    @Singleton
    fun provideImdbSearchService(
        imdbSearchRestTransport: ImdbSearchRestTransport,
        imdbSearchWebSocketTransport: ImdbSearchWebSocketTransport,
        moshi: Moshi
    ): ImdbSearchService = OkHttpImdbSearchService(
        imdbSearchRestTransport = imdbSearchRestTransport,
        imdbSearchWebSocketTransport = imdbSearchWebSocketTransport,
        moshi = moshi
    )

}
