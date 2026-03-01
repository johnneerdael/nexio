package com.nuvio.tv.data.trailer

import android.content.Context
import android.util.Log
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import com.google.gson.Gson
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "InAppYouTubeExtractor"
private const val EXTRACTOR_TIMEOUT_MS = 45_000L

@Singleton
class InAppYouTubeExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var cachedScript: String? = null

    suspend fun extractPlaybackSource(youtubeUrl: String): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        if (youtubeUrl.isBlank()) return@withContext null

        Log.d(TAG, "Starting in-app extraction for ${summarizeUrl(youtubeUrl)}")
        val script = loadExtractorScript() ?: return@withContext null
        var resultJson = ""

        val inFlightCalls = ConcurrentHashMap.newKeySet<Call>()

        try {
            withTimeout(EXTRACTOR_TIMEOUT_MS) {
                quickJs(Dispatchers.IO) {
                    define("console") {
                        function("log") { args ->
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, args.joinToString(" ") { it?.toString() ?: "null" })
                            }
                            null
                        }
                        function("error") { args ->
                            Log.e(TAG, args.joinToString(" ") { it?.toString() ?: "null" })
                            null
                        }
                        function("warn") { args ->
                            Log.w(TAG, args.joinToString(" ") { it?.toString() ?: "null" })
                            null
                        }
                    }

                    asyncFunction("__native_fetch") { args ->
                        val url = args.getOrNull(0)?.toString() ?: ""
                        val method = args.getOrNull(1)?.toString() ?: "GET"
                        val headersJson = args.getOrNull(2)?.toString() ?: "{}"
                        val body = args.getOrNull(3)?.toString() ?: ""
                        performNativeFetch(url, method, headersJson, body, inFlightCalls)
                    }

                    function("__parse_url") { args ->
                        val url = args.getOrNull(0)?.toString() ?: ""
                        parseUrl(url)
                    }

                    function("__capture_result") { args ->
                        resultJson = args.getOrNull(0)?.toString() ?: ""
                        null
                    }

                    val bootstrap = """
                        var fetch = async function(url, options) {
                            options = options || {};
                            var method = (options.method || 'GET').toUpperCase();
                            var headers = options.headers || {};
                            var body = options.body || '';
                            if (!headers['User-Agent'] && !headers['user-agent']) {
                                headers['User-Agent'] = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36';
                            }

                            var raw = await __native_fetch(url, method, JSON.stringify(headers), body);
                            var parsed = JSON.parse(raw);

                            return {
                                ok: parsed.ok,
                                status: parsed.status,
                                statusText: parsed.statusText,
                                url: parsed.url,
                                headers: {
                                    get: function(name) {
                                        return parsed.headers[(name || '').toLowerCase()] || null;
                                    }
                                },
                                text: function() { return Promise.resolve(parsed.body || ''); },
                                json: function() {
                                    try {
                                        return Promise.resolve(JSON.parse(parsed.body || '{}'));
                                    } catch (e) {
                                        return Promise.reject(e);
                                    }
                                }
                            };
                        };

                        var URLSearchParams = function(init) {
                            this._params = {};
                            var self = this;
                            if (typeof init === 'string') {
                                init.replace(/^\?/, '').split('&').forEach(function(pair) {
                                    if (!pair) return;
                                    var parts = pair.split('=');
                                    var key = decodeURIComponent(parts[0] || '');
                                    if (!key) return;
                                    var value = decodeURIComponent(parts[1] || '');
                                    self._params[key] = value;
                                });
                            }
                        };
                        URLSearchParams.prototype.get = function(k) {
                            return this._params.hasOwnProperty(k) ? this._params[k] : null;
                        };
                        URLSearchParams.prototype.has = function(k) {
                            return this._params.hasOwnProperty(k);
                        };

                        var URL = function(urlString, base) {
                            var fullUrl = urlString;
                            if (base && !/^https?:\/\//i.test(urlString)) {
                                var b = typeof base === 'string' ? base : base.href;
                                if (urlString.charAt(0) === '/') {
                                    var m = b.match(/^(https?:\/\/[^\/]+)/);
                                    fullUrl = m ? m[1] + urlString : urlString;
                                } else {
                                    fullUrl = b.replace(/\/[^\/]*$/, '/') + urlString;
                                }
                            }
                            var parsed = JSON.parse(__parse_url(fullUrl));
                            this.href = fullUrl;
                            this.protocol = parsed.protocol;
                            this.host = parsed.host;
                            this.hostname = parsed.hostname;
                            this.port = parsed.port;
                            this.pathname = parsed.pathname;
                            this.search = parsed.search;
                            this.hash = parsed.hash;
                            this.origin = this.protocol + '//' + this.host;
                            this.searchParams = new URLSearchParams(parsed.search || '');
                        };
                        URL.prototype.toString = function() { return this.href; };

                        var require = function(moduleName) {
                            if (moduleName === 'url') {
                                return { URL: URL };
                            }
                            if (moduleName === 'node-fetch') {
                                return fetch;
                            }
                            throw new Error("Module '" + moduleName + "' is not available");
                        };
                    """.trimIndent()

                    evaluate<Any?>(bootstrap)
                    evaluate<Any?>("var module = { exports: {} }; var exports = module.exports;")
                    evaluate<Any?>(script)

                    val callCode = """
                        (async function() {
                            try {
                                var fn = module.exports.extractBestUrlsNoResolver || globalThis.extractBestUrlsNoResolver;
                                if (!fn) {
                                    __capture_result('');
                                    return;
                                }
                                var result = await fn(${gson.toJson(youtubeUrl)}, { debug: false, separateClient: 'android_vr' });
                                __capture_result(JSON.stringify(result || {}));
                            } catch (e) {
                                __capture_result('');
                            }
                        })();
                    """.trimIndent()
                    evaluate<Any?>(callCode)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Extractor failed for $youtubeUrl: ${error.message}")
        } finally {
            inFlightCalls.forEach { call -> call.cancel() }
            inFlightCalls.clear()
        }

        val source = parsePlaybackSource(resultJson)
        if (source == null) {
            Log.w(TAG, "In-app extraction returned no playable source for ${summarizeUrl(youtubeUrl)}")
        } else {
            Log.d(
                TAG,
                "In-app extraction success for ${summarizeUrl(youtubeUrl)} " +
                    "(video=${summarizeUrl(source.videoUrl)}, audioPresent=${!source.audioUrl.isNullOrBlank()})"
            )
        }
        source
    }

    private fun parsePlaybackSource(resultJson: String): TrailerPlaybackSource? {
        if (resultJson.isBlank()) return null

        return runCatching {
            val root = gson.fromJson(resultJson, Map::class.java) ?: return null
            val combined = root["combined"] as? Map<*, *>
            val separate = root["separate"] as? Map<*, *>
            val separateVideo = separate?.get("video") as? Map<*, *>
            val separateAudio = separate?.get("audio") as? Map<*, *>

            val combinedUrl = combined?.get("url")?.toString()?.takeIf { it.startsWith("http") }
            val videoUrl = (separateVideo?.get("url")?.toString() ?: combinedUrl)
                ?.takeIf { it.startsWith("http") }
                ?: return null
            val audioUrl = separateAudio?.get("url")?.toString()?.takeIf { it.startsWith("http") }

            TrailerPlaybackSource(
                videoUrl = videoUrl,
                audioUrl = audioUrl
            )
        }.getOrNull()
    }

    private fun loadExtractorScript(): String? {
        cachedScript?.let {
            return it
        }

        val loaded = runCatching {
            context.assets.open("js/simple-youtube-extractor.js").bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.e(TAG, "Failed to load extractor script: ${it.message}")
            return null
        }

        cachedScript = loaded
        Log.d(TAG, "Loaded extractor script from assets (chars=${loaded.length})")
        return loaded
    }

    private fun summarizeUrl(url: String): String {
        return runCatching {
            val parsed = URL(url)
            val host = parsed.host ?: "unknown-host"
            val path = parsed.path ?: "/"
            "$host$path"
        }.getOrDefault(url.take(80))
    }

    private fun parseUrl(urlString: String): String {
        return try {
            val url = URL(urlString)
            gson.toJson(
                mapOf(
                    "protocol" to "${url.protocol}:",
                    "host" to if (url.port > 0) "${url.host}:${url.port}" else url.host,
                    "hostname" to url.host,
                    "port" to if (url.port > 0) url.port.toString() else "",
                    "pathname" to (url.path ?: "/"),
                    "search" to if (url.query != null) "?${url.query}" else "",
                    "hash" to if (url.ref != null) "#${url.ref}" else ""
                )
            )
        } catch (_: Exception) {
            gson.toJson(
                mapOf(
                    "protocol" to "",
                    "host" to "",
                    "hostname" to "",
                    "port" to "",
                    "pathname" to "/",
                    "search" to "",
                    "hash" to ""
                )
            )
        }
    }

    private fun performNativeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String,
        inFlightCalls: MutableSet<Call>
    ): String {
        return try {
            val parsedHeaders = runCatching {
                gson.fromJson(headersJson, Map::class.java)
            }.getOrNull()

            val headers = mutableMapOf<String, String>()
            parsedHeaders?.forEach { (k, v) ->
                if (k != null && v != null) {
                    val key = k.toString()
                    if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                        headers[key] = v.toString()
                    }
                }
            }

            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .headers(Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))

            when (method.uppercase()) {
                "POST" -> requestBuilder.post(body.toRequestBody())
                "PUT" -> requestBuilder.put(body.toRequestBody())
                "DELETE" -> requestBuilder.delete()
                else -> requestBuilder.get()
            }

            val call = httpClient.newCall(requestBuilder.build())
            inFlightCalls.add(call)

            try {
                call.execute().use { response ->
                    val responseHeaders = mutableMapOf<String, String>()
                    response.headers.forEach { (name, value) ->
                        responseHeaders[name.lowercase()] = value
                    }

                    gson.toJson(
                        mapOf(
                            "ok" to response.isSuccessful,
                            "status" to response.code,
                            "statusText" to response.message,
                            "url" to response.request.url.toString(),
                            "body" to (response.body?.string() ?: ""),
                            "headers" to responseHeaders
                        )
                    )
                }
            } finally {
                inFlightCalls.remove(call)
            }
        } catch (error: Exception) {
            gson.toJson(
                mapOf(
                    "ok" to false,
                    "status" to 0,
                    "statusText" to (error.message ?: "Fetch failed"),
                    "url" to url,
                    "body" to "",
                    "headers" to emptyMap<String, String>()
                )
            )
        }
    }
}
