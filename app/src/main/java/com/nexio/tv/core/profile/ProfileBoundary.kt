package com.nexio.tv.core.profile

import android.content.Context
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.domain.model.TrackingProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SecondaryProfileRuntimeContext(
    val profileId: Int,
    val languageTag: String,
    val generation: Long
)

enum class ProfileSettingsDomain {
    TRAKT,
    SIMKL,
    PLAYER,
    LAYOUT,
    THEME,
    CATALOGS,
    AUTH
}

enum class ProfileCacheDomain {
    SHARED_ARTWORK,
    SHARED_TEXT_METADATA,
    HOME_SNAPSHOT,
    SYNTHETIC_HOME,
    DISCOVERY,
    CONTINUE_WATCHING,
    CATALOG_ROWS
}

sealed interface AuthRoute {
    val profileId: Int
    val provider: TrackingProvider

    data class ProfileToken(
        override val profileId: Int,
        override val provider: TrackingProvider
    ) : AuthRoute
}

sealed interface SettingsRoute {
    val profileId: Int
    val domain: ProfileSettingsDomain

    data class ProfileBlob(
        override val profileId: Int,
        override val domain: ProfileSettingsDomain
    ) : SettingsRoute
}

sealed interface ProfileCacheScope {
    data object SharedArtwork : ProfileCacheScope

    data class SharedLanguageMetadata(
        val languageTag: String,
        val providerToken: String
    ) : ProfileCacheScope

    data class ProfileSnapshot(
        val profileId: Int,
        val languageTag: String,
        val domain: ProfileCacheDomain
    ) : ProfileCacheScope
}

@Singleton
class ProfileBoundary(
    private val profileManager: ProfileManager,
    private val languageTagProvider: () -> String
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileManager: ProfileManager
    ) : this(
        profileManager = profileManager,
        languageTagProvider = { AppLocaleResolver.resolveEffectiveAppLanguageTag(context) }
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong(0L)
    private val _activeContext = MutableStateFlow<SecondaryProfileRuntimeContext?>(null)
    val activeContext: StateFlow<SecondaryProfileRuntimeContext?> = _activeContext.asStateFlow()

    init {
        scope.launch {
            var lastProfileId: Int? = null
            profileManager.activeProfileId.collect { profileId ->
                if (profileId !in 2..4) {
                    if (_activeContext.value != null) {
                        generation.incrementAndGet()
                        _activeContext.value = null
                    }
                    lastProfileId = null
                    return@collect
                }

                if (lastProfileId != profileId) {
                    lastProfileId = profileId
                    _activeContext.value = buildContext(profileId, generation.incrementAndGet())
                }
            }
        }
    }

    fun contextFor(route: ProfileModeRoute.SecondaryProfileRoute): SecondaryProfileRuntimeContext {
        return buildContext(requireSecondaryProfileId(route.profileId), generation.get())
    }

    fun authRoute(
        route: ProfileModeRoute.SecondaryProfileRoute,
        provider: TrackingProvider
    ): AuthRoute.ProfileToken {
        return AuthRoute.ProfileToken(
            profileId = requireSecondaryProfileId(route.profileId),
            provider = provider
        )
    }

    fun settingsRoute(
        route: ProfileModeRoute.SecondaryProfileRoute,
        domain: ProfileSettingsDomain
    ): SettingsRoute.ProfileBlob {
        return SettingsRoute.ProfileBlob(
            profileId = requireSecondaryProfileId(route.profileId),
            domain = domain
        )
    }

    fun cacheScope(
        route: ProfileModeRoute.SecondaryProfileRoute,
        domain: ProfileCacheDomain,
        languageTag: String? = null,
        providerToken: String = "native"
    ): ProfileCacheScope {
        return when (domain) {
            ProfileCacheDomain.SHARED_ARTWORK -> ProfileCacheScope.SharedArtwork
            ProfileCacheDomain.SHARED_TEXT_METADATA -> ProfileCacheScope.SharedLanguageMetadata(
                languageTag = languageTag ?: currentLanguageTag(),
                providerToken = providerToken
            )
            else -> ProfileCacheScope.ProfileSnapshot(
                profileId = requireSecondaryProfileId(route.profileId),
                languageTag = languageTag ?: currentLanguageTag(),
                domain = domain
            )
        }
    }

    fun currentLanguageTag(): String {
        val languageTag = readCurrentLanguageTag()
        val context = _activeContext.value
        if (context != null && context.languageTag != languageTag) {
            _activeContext.value = context.copy(
                languageTag = languageTag,
                generation = generation.incrementAndGet()
            )
        }
        return languageTag
    }

    fun isCurrent(generation: Long): Boolean {
        return _activeContext.value?.generation == generation
    }

    private fun buildContext(profileId: Int, generation: Long): SecondaryProfileRuntimeContext {
        return SecondaryProfileRuntimeContext(
            profileId = requireSecondaryProfileId(profileId),
            languageTag = readCurrentLanguageTag(),
            generation = generation
        )
    }

    private fun requireSecondaryProfileId(profileId: Int): Int {
        require(profileId in 2..4) {
            "ProfileBoundary only accepts secondary profiles 2-4; profile $profileId must use the legacy/default route"
        }
        return profileId
    }

    private fun readCurrentLanguageTag(): String {
        return languageTagProvider()
    }
}
