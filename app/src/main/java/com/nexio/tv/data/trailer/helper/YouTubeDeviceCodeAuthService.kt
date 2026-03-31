package com.nexio.tv.data.trailer.helper

import android.util.Log
import com.nexio.tv.BuildConfig
import com.nexio.tv.data.trailer.YOUTUBE_STABLE_ACCEPT_LANGUAGE
import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "YouTubeDeviceAuth"
private const val GOOGLE_DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
private const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
private const val YOUTUBE_DEVICE_SCOPE = "https://www.googleapis.com/auth/youtube"
private const val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded"
private const val GOOGLE_DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

private data class YouTubeTrailerClientCredentials(
    val clientId: String,
    val clientSecret: String
)

@JsonClass(generateAdapter = true)
internal data class GoogleDeviceCodeResponseDto(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "verification_url") val verificationUrl: String,
    @Json(name = "verification_url_complete") val verificationUrlComplete: String? = null,
    @Json(name = "expires_in") val expiresInSeconds: Int,
    @Json(name = "interval") val intervalSeconds: Int
)

@JsonClass(generateAdapter = true)
internal data class GoogleTokenResponseDto(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "token_type") val tokenType: String? = null,
    @Json(name = "expires_in") val expiresInSeconds: Int? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null
)

data class YouTubeDeviceCodeSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val verificationUrlComplete: String? = null,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

sealed interface YouTubeTrailerTokenPollResult {
    data object Pending : YouTubeTrailerTokenPollResult
    data class SlowDown(val pollIntervalSeconds: Int) : YouTubeTrailerTokenPollResult
    data object Expired : YouTubeTrailerTokenPollResult
    data object Denied : YouTubeTrailerTokenPollResult
    data class Approved(val session: YouTubeTrailerTokenSession) : YouTubeTrailerTokenPollResult
    data class Failed(val reason: String) : YouTubeTrailerTokenPollResult
}

@Singleton
class YouTubeDeviceCodeAuthService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    private val codeAdapter = moshi.adapter(GoogleDeviceCodeResponseDto::class.java)
    private val tokenAdapter = moshi.adapter(GoogleTokenResponseDto::class.java)

    suspend fun startDeviceFlow(): Result<YouTubeDeviceCodeSession> = withContext(Dispatchers.IO) {
        val credentials = loadYouTubeTrailerCredentials().getOrElse { return@withContext Result.failure(it) }
        val response = executeFormPost(
            url = GOOGLE_DEVICE_CODE_URL,
            "client_id" to credentials.clientId,
            "scope" to YOUTUBE_DEVICE_SCOPE
        ).getOrElse { return@withContext Result.failure(it) }

        val body = runCatching {
            codeAdapter.fromJson(response.body)
                ?: error("Google device code response was empty")
        }.getOrElse { return@withContext Result.failure(it) }

        Result.success(
            YouTubeDeviceCodeSession(
                deviceCode = body.deviceCode,
                userCode = body.userCode,
                verificationUrl = body.verificationUrl,
                verificationUrlComplete = body.verificationUrlComplete,
                expiresInSeconds = body.expiresInSeconds,
                intervalSeconds = body.intervalSeconds
            )
        )
    }

    suspend fun pollDeviceToken(deviceCode: String): YouTubeTrailerTokenPollResult =
        withContext(Dispatchers.IO) {
            val credentials = loadYouTubeTrailerCredentials().getOrElse { error ->
                return@withContext YouTubeTrailerTokenPollResult.Failed(
                    error.message ?: "Failed to load YouTube trailer auth credentials"
                )
            }

            val response = executeFormPost(
                url = GOOGLE_TOKEN_URL,
                "client_id" to credentials.clientId,
                "client_secret" to credentials.clientSecret,
                "device_code" to deviceCode,
                "grant_type" to GOOGLE_DEVICE_GRANT_TYPE
            ).getOrElse { error ->
                return@withContext YouTubeTrailerTokenPollResult.Failed(
                    error.message ?: "Network error while polling YouTube auth token"
                )
            }

            val body = parseTokenResponse(response.body)
            if (response.code in 200..299 && !body?.accessToken.isNullOrBlank()) {
                return@withContext YouTubeTrailerTokenPollResult.Approved(
                    body!!.toSession(persistedRefreshToken = null)
                )
            }

            return@withContext when (body?.error) {
                "authorization_pending" -> YouTubeTrailerTokenPollResult.Pending
                "slow_down" -> YouTubeTrailerTokenPollResult.SlowDown(10)
                "expired_token" -> YouTubeTrailerTokenPollResult.Expired
                "access_denied" -> YouTubeTrailerTokenPollResult.Denied
                else -> YouTubeTrailerTokenPollResult.Failed(
                    body?.errorDescription
                        ?: body?.error
                        ?: "Token polling failed (${response.code})"
                )
            }
        }

    suspend fun refreshAccessToken(refreshToken: String): Result<YouTubeTrailerTokenSession> =
        withContext(Dispatchers.IO) {
            val credentials = loadYouTubeTrailerCredentials().getOrElse { return@withContext Result.failure(it) }
            val response = executeFormPost(
                url = GOOGLE_TOKEN_URL,
                "client_id" to credentials.clientId,
                "client_secret" to credentials.clientSecret,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token"
            ).getOrElse { return@withContext Result.failure(it) }

            val body = parseTokenResponse(response.body)
            if (response.code in 200..299 && !body?.accessToken.isNullOrBlank()) {
                return@withContext Result.success(
                    body!!.toSession(persistedRefreshToken = refreshToken)
                )
            }

            Result.failure(
                IllegalStateException(
                    body?.errorDescription
                        ?: body?.error
                        ?: "Failed to refresh YouTube trailer access token (${response.code})"
                )
            )
        }

    private fun GoogleTokenResponseDto.toSession(
        persistedRefreshToken: String?
    ): YouTubeTrailerTokenSession {
        return YouTubeTrailerTokenSession(
            accessToken = accessToken.orEmpty(),
            refreshToken = refreshToken ?: persistedRefreshToken,
            tokenType = tokenType ?: "Bearer",
            expiresInSeconds = expiresInSeconds,
            delegatedPageId = null,
            authUser = "0"
        )
    }

    private fun parseTokenResponse(raw: String): GoogleTokenResponseDto? {
        if (raw.isBlank()) return null
        return runCatching { tokenAdapter.fromJson(raw) }.getOrNull()
    }

    private suspend fun loadYouTubeTrailerCredentials(): Result<YouTubeTrailerClientCredentials> = withContext(Dispatchers.IO) {
        val clientId = BuildConfig.YOUTUBE_TRAILER_CLIENT_ID.trim()
        val clientSecret = BuildConfig.YOUTUBE_TRAILER_CLIENT_SECRET.trim()
        if (clientId.isBlank() || clientSecret.isBlank()) {
            Log.w(TAG, "Missing YouTube trailer OAuth credentials in build config")
            return@withContext Result.failure(
                IllegalStateException(
                    "Missing YOUTUBE_TRAILER_CLIENT_ID or YOUTUBE_TRAILER_CLIENT_SECRET"
                )
            )
        }
        Result.success(
            YouTubeTrailerClientCredentials(
                clientId = clientId,
                clientSecret = clientSecret
            )
        )
    }

    private suspend fun executeFormPost(
        url: String,
        vararg pairs: Pair<String, String>
    ): Result<SimpleHttpResponse> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", YOUTUBE_STABLE_WEB_USER_AGENT)
            .header("Accept-Language", YOUTUBE_STABLE_ACCEPT_LANGUAGE)
            .post(formBody(*pairs))
            .build()

        runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                SimpleHttpResponse(
                    code = response.code,
                    body = response.body?.string().orEmpty()
                )
            }
        }.recoverCatching { error ->
            if (error is IOException) {
                throw IOException(error.message ?: "Network error", error)
            }
            throw error
        }
    }

    private fun formBody(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (key, value) ->
            "${encodeComponent(key)}=${encodeComponent(value)}"
        }.toRequestBody(FORM_MEDIA_TYPE.toMediaType())

    private fun encodeComponent(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}

private data class SimpleHttpResponse(
    val code: Int,
    val body: String
)
