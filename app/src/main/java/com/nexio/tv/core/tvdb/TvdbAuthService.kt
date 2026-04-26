package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.core.metadata.MetadataApiKeyResolver
import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderConfig
import com.nexio.tv.data.local.TvdbSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

typealias TvdbValidationStatus = com.nexio.tv.domain.model.TvdbValidationStatus
typealias TvdbTokenStore = com.nexio.tv.data.local.TvdbTokenStore

const val TVDB_TOKEN_TTL_MS = 30L * 24L * 60L * 60L * 1000L
const val TVDB_TOKEN_REFRESH_SKEW_MS = 24L * 60L * 60L * 1000L

private const val TAG = "TvdbAuthService"

sealed class TvdbAuthResult(open val status: TvdbValidationStatus) {
    class Valid(
        val authorizationHeader: String,
        val expiresAtEpochMillis: Long
    ) : TvdbAuthResult(TvdbValidationStatus.VALID) {
        override fun toString(): String {
            return "TvdbAuthResult.Valid(expiresAtEpochMillis=$expiresAtEpochMillis)"
        }
    }

    class InvalidCredentials(
        val lastFailure: String,
        override val status: TvdbValidationStatus = TvdbValidationStatus.INVALID
    ) : TvdbAuthResult(status) {
        override fun toString(): String = "TvdbAuthResult.InvalidCredentials(status=$status, lastFailure=$lastFailure)"
    }

    class AuthUnavailable(
        val lastFailure: String
    ) : TvdbAuthResult(TvdbValidationStatus.FALLBACK_ACTIVE) {
        override fun toString(): String = "TvdbAuthResult.AuthUnavailable(lastFailure=$lastFailure)"
    }
}

@Singleton
class TvdbAuthService(
    private val loginGateway: TvdbLoginGateway,
    private val settingsDataStore: TvdbSettingsDataStore?,
    private val tokenStore: TvdbTokenStore,
    private val nowMillis: () -> Long,
    private val metadataApiKeyResolver: MetadataApiKeyResolver? = null
) {
    private val refreshMutex = Mutex()

    @Inject
    constructor(
        settingsDataStore: TvdbSettingsDataStore,
        tokenStore: TvdbTokenStore,
        metadataApiKeyResolver: MetadataApiKeyResolver,
        loginGateway: TvdbLoginGateway
    ) : this(
        loginGateway = loginGateway,
        settingsDataStore = settingsDataStore,
        tokenStore = tokenStore,
        nowMillis = { System.currentTimeMillis() },
        metadataApiKeyResolver = metadataApiKeyResolver
    )

    constructor(
        loginGateway: TvdbLoginGateway,
        tokenStore: TvdbTokenStore,
        nowMillis: () -> Long = { System.currentTimeMillis() }
    ) : this(
        loginGateway = loginGateway,
        settingsDataStore = null,
        tokenStore = tokenStore,
        nowMillis = nowMillis
    )

    suspend fun bearerToken(): String? = withContext(Dispatchers.IO) {
        val settingsStore = settingsDataStore ?: return@withContext null
        val credential = metadataApiKeyResolver?.tvdbCredential() ?: settingsStore.settings.first().let { settings ->
            MetadataProviderConfig.resolveCredential(
                customApiKey = settings.apiKey,
                builtInApiKey = MetadataProviderConfig.builtInTvdbApiKey(),
                pin = settings.subscriberPin
            )
        }
        val apiKey = credential.apiKey
        val pin = credential.pin
        if (credential.missing) {
            return@withContext null
        }

        val fingerprint = credentialFingerprint(apiKey, pin)
        readCachedAuthorization(fingerprint)?.let { return@withContext it }

        refreshMutex.withLock {
            readCachedAuthorization(fingerprint)?.let { return@withLock it }

            when (val result = requestToken(apiKey = apiKey, pin = pin)) {
                is TvdbAuthResult.Valid -> {
                    if (credential.source == MetadataCredentialSource.CUSTOM) {
                        settingsStore.saveCredentials(
                            apiKey = apiKey,
                            pin = pin,
                            validationStatus = TvdbValidationStatus.VALID
                        )
                    } else {
                        settingsStore.saveValidationFailure(
                            status = TvdbValidationStatus.VALID,
                            lastFailure = ""
                        )
                    }
                    tokenStore.saveToken(
                        token = result.authorizationHeader.removePrefix("Bearer "),
                        expiresAtEpochMillis = result.expiresAtEpochMillis,
                        credentialFingerprint = fingerprint
                    )
                    result.authorizationHeader
                }

                is TvdbAuthResult.InvalidCredentials -> {
                    tokenStore.clear()
                    settingsStore.saveValidationFailure(
                        status = TvdbValidationStatus.INVALID,
                        lastFailure = result.lastFailure
                    )
                    null
                }

                is TvdbAuthResult.AuthUnavailable -> {
                    settingsStore.saveValidationFailure(
                        status = TvdbValidationStatus.FALLBACK_ACTIVE,
                        lastFailure = result.lastFailure
                    )
                    null
                }
            }
        }
    }

    suspend fun loginAndCacheToken(apiKey: String, pin: String): TvdbAuthResult = withContext(Dispatchers.IO) {
        when (val result = requestToken(apiKey = apiKey.trim(), pin = pin.trim())) {
            is TvdbAuthResult.Valid -> {
                tokenStore.saveToken(
                    token = result.authorizationHeader.removePrefix("Bearer "),
                    expiresAtEpochMillis = result.expiresAtEpochMillis
                )
                result
            }

            is TvdbAuthResult.InvalidCredentials -> {
                tokenStore.clear()
                result
            }

            is TvdbAuthResult.AuthUnavailable -> result
        }
    }

    suspend fun validateCredentials(apiKey: String, subscriberPin: String): Boolean {
        return validateCredentialsResult(apiKey = apiKey, subscriberPin = subscriberPin).status == TvdbValidationStatus.VALID
    }

    suspend fun validateCredentialsResult(apiKey: String, subscriberPin: String): TvdbAuthResult {
        val settingsStore = settingsDataStore
        val trimmedApiKey = apiKey.trim()
        val trimmedPin = subscriberPin.trim()
        if (trimmedApiKey.isBlank()) {
            tokenStore.clear()
            settingsStore?.saveValidationFailure(
                status = TvdbValidationStatus.NOT_CONFIGURED,
                lastFailure = "TVDB API key is required"
            )
            return TvdbAuthResult.InvalidCredentials(
                lastFailure = "TVDB API key is required",
                status = TvdbValidationStatus.NOT_CONFIGURED
            )
        }

        return when (val result = requestToken(apiKey = trimmedApiKey, pin = trimmedPin)) {
            is TvdbAuthResult.Valid -> {
                settingsStore?.saveCredentials(
                    apiKey = trimmedApiKey,
                    pin = trimmedPin,
                    validationStatus = TvdbValidationStatus.VALID
                )
                tokenStore.saveToken(
                    token = result.authorizationHeader.removePrefix("Bearer "),
                    expiresAtEpochMillis = result.expiresAtEpochMillis,
                    credentialFingerprint = credentialFingerprint(trimmedApiKey, trimmedPin)
                )
                result
            }

            is TvdbAuthResult.InvalidCredentials -> {
                tokenStore.clear()
                settingsStore?.saveValidationFailure(
                    status = TvdbValidationStatus.INVALID,
                    lastFailure = result.lastFailure
                )
                result
            }

            is TvdbAuthResult.AuthUnavailable -> {
                settingsStore?.saveValidationFailure(
                    status = TvdbValidationStatus.FALLBACK_ACTIVE,
                    lastFailure = result.lastFailure
                )
                result
            }
        }
    }

    private suspend fun readCachedAuthorization(credentialFingerprint: String): String? {
        val cached = tokenStore.tokenState.first()
        val refreshAfter = nowMillis() + TVDB_TOKEN_REFRESH_SKEW_MS
        return cached.token
            .takeIf { it.isNotBlank() }
            ?.takeIf { cached.expiresAtEpochMs > refreshAfter }
            ?.takeIf { cached.credentialFingerprint == credentialFingerprint }
            ?.let { "Bearer $it" }
    }

    private suspend fun requestToken(apiKey: String, pin: String): TvdbAuthResult {
        if (apiKey.isBlank()) {
            return TvdbAuthResult.InvalidCredentials("TVDB API key is required")
        }
        return loginGateway.requestToken(apiKey = apiKey, pin = pin, nowMillis = nowMillis)
    }

    private fun credentialFingerprint(apiKey: String, pin: String): String {
        return "${apiKey.trim()}:${pin.trim()}".hashCode().toString()
    }
}
