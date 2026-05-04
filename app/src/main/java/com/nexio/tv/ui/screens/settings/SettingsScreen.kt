@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.components.ProfileAvatarCircle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.domain.model.UserProfile
import com.nexio.tv.BuildConfig
import com.nexio.tv.R
import com.nexio.tv.ui.theme.NexioColors
import kotlinx.coroutines.delay

internal enum class SettingsCategory {
    ACCOUNT,
    APPEARANCE,
    LAYOUT,
    INTEGRATION,
    PLAYBACK,
    CATALOGS,
    ABOUT,
    DEBUG
}

private enum class IntegrationSettingsSection {
    Hub,
    Debrid,
    Trakt,
    Simkl,
    Kitsu,
    TheIntroDb,
    Tmdb,
    Tvdb,
    Omdb,
    MdbList,
    AnimeSkip,
    SubtitleTranslation,
    OpenSubtitles,
    WyzieSubtitles,
    PosterRatings
}

internal enum class SettingsSectionDestination {
    Inline,
    External
}

internal data class SettingsSectionSpec(
    val category: SettingsCategory,
    val title: String,
    val icon: ImageVector? = null,
    @param:RawRes val rawIconRes: Int? = null,
    val subtitle: String,
    val destination: SettingsSectionDestination
)

private const val SETTINGS_DETAIL_FOCUS_DELAY_MS = 120L
private const val SETTINGS_DETAIL_ANIM_IN_DURATION_MS = 200
private const val SETTINGS_DETAIL_ANIM_OUT_DURATION_MS = 180

@Composable
private fun rememberSettingsSectionSpecs() = listOf(
    SettingsSectionSpec(
        category = SettingsCategory.ACCOUNT,
        title = stringResource(R.string.settings_account),
        icon = Icons.Default.Person,
        subtitle = stringResource(R.string.settings_account_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.APPEARANCE,
        title = stringResource(R.string.appearance_title),
        icon = Icons.Default.Palette,
        subtitle = stringResource(R.string.appearance_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.LAYOUT,
        title = stringResource(R.string.settings_layout),
        icon = Icons.Default.GridView,
        subtitle = stringResource(R.string.settings_layout_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.INTEGRATION,
        title = stringResource(R.string.settings_integration),
        icon = Icons.Default.Link,
        subtitle = "",
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.PLAYBACK,
        title = stringResource(R.string.settings_playback),
        icon = Icons.Default.Settings,
        subtitle = stringResource(R.string.settings_playback_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.CATALOGS,
        title = stringResource(R.string.settings_catalogs),
        icon = Icons.Default.GridView,
        subtitle = stringResource(R.string.settings_catalogs_subtitle),
        destination = SettingsSectionDestination.External
    ),
    SettingsSectionSpec(
        category = SettingsCategory.ABOUT,
        title = stringResource(R.string.about_title),
        icon = Icons.Default.Info,
        subtitle = stringResource(R.string.settings_about_subtitle),
        destination = SettingsSectionDestination.Inline
    ),
    SettingsSectionSpec(
        category = SettingsCategory.DEBUG,
        title = stringResource(R.string.settings_debug),
        icon = Icons.Default.BugReport,
        subtitle = stringResource(R.string.settings_debug_subtitle),
        destination = SettingsSectionDestination.Inline
    )
)

@Composable
fun SettingsScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToCatalogs: () -> Unit = {},
    onNavigateToAuthQrSignIn: () -> Unit = {}
) {
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val isPrimaryProfile by settingsViewModel.isPrimaryProfile.collectAsStateWithLifecycle()
    val activeProfile by settingsViewModel.activeProfile.collectAsStateWithLifecycle()
    val profileToDelete by settingsViewModel.showDeleteDialog.collectAsStateWithLifecycle()
    val deleteInProgress by settingsViewModel.deleteInProgress.collectAsStateWithLifecycle()

    val allSectionSpecs = rememberSettingsSectionSpecs()
    val visibleSections = remember(allSectionSpecs) {
        allSectionSpecs.filter { section ->
            when (section.category) {
                SettingsCategory.DEBUG -> BuildConfig.IS_DEBUG_BUILD
                SettingsCategory.ACCOUNT -> true
                SettingsCategory.CATALOGS -> true
                else -> true
            }
        }
    }

    var selectedCategory by remember(visibleSections) {
        mutableStateOf(
            visibleSections.firstOrNull()?.category ?: SettingsCategory.APPEARANCE
        )
    }
    val railFocusRequesters = remember(visibleSections) {
        visibleSections.associate { it.category to FocusRequester() }
    }
    val contentFocusRequesters = remember {
            mapOf(
                SettingsCategory.APPEARANCE to FocusRequester(),
                SettingsCategory.LAYOUT to FocusRequester(),
                SettingsCategory.INTEGRATION to FocusRequester(),
                SettingsCategory.PLAYBACK to FocusRequester(),
                SettingsCategory.ABOUT to FocusRequester()
            )
    }
    val railContainerFocusRequester = remember { FocusRequester() }
    val integrationHubFocusRequester = remember { FocusRequester() }
    val integrationDebridFocusRequester = remember { FocusRequester() }
    val integrationTraktFocusRequester = remember { FocusRequester() }
    val integrationSimklFocusRequester = remember { FocusRequester() }
    val integrationKitsuFocusRequester = remember { FocusRequester() }
    val integrationTheIntroDbFocusRequester = remember { FocusRequester() }
    val integrationTmdbFocusRequester = remember { FocusRequester() }
    val integrationTvdbFocusRequester = remember { FocusRequester() }
    val integrationOmdbFocusRequester = remember { FocusRequester() }
    val integrationMdbListFocusRequester = remember { FocusRequester() }
    val integrationAnimeSkipFocusRequester = remember { FocusRequester() }
    val integrationSubtitleTranslationFocusRequester = remember { FocusRequester() }
    val integrationOpenSubtitlesFocusRequester = remember { FocusRequester() }
    val integrationWyzieSubtitlesFocusRequester = remember { FocusRequester() }
    val integrationPosterRatingsFocusRequester = remember { FocusRequester() }
    var integrationSection by remember { mutableStateOf(IntegrationSettingsSection.Hub) }
    var pendingContentFocusCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var pendingContentFocusRequestId by remember { mutableLongStateOf(0L) }
    var allowDetailAutofocus by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    LaunchedEffect(visibleSections) {
        if (visibleSections.none { it.category == selectedCategory }) {
            selectedCategory = visibleSections.firstOrNull()?.category ?: SettingsCategory.APPEARANCE
        }
    }

    LaunchedEffect(Unit) {
        runCatching { railContainerFocusRequester.requestFocus() }
    }

    LaunchedEffect(pendingContentFocusRequestId) {
        val category = pendingContentFocusCategory ?: return@LaunchedEffect
        delay(SETTINGS_DETAIL_FOCUS_DELAY_MS)
        val requester = contentFocusRequesters[category]
        val requested = if (requester != null) {
            runCatching { requester.requestFocus() }.isSuccess
        } else {
            false
        }
        if (!requested) {
            focusManager.moveFocus(FocusDirection.Right)
        }
        pendingContentFocusCategory = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexioColors.Background)
            .padding(
                start = 32.dp,
                end = 32.dp,
                top = if (showBuiltInHeader) 24.dp else 68.dp,
                bottom = 24.dp
            )
    ) {
        SettingsWorkspaceSurface(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Profile header — always visible, even for single-profile users (D-16)
                activeProfile?.let { profile ->
                    ProfileHeaderRow(profile = profile)
                }

                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                var railHadFocus by remember { mutableStateOf(false) }

                LazyColumn(
                    modifier = Modifier
                        .focusRequester(railContainerFocusRequester)
                        .width(282.dp)
                        .fillMaxHeight()
                        .onFocusChanged { state ->
                            val justGainedFocus = !railHadFocus && state.hasFocus
                            railHadFocus = state.hasFocus
                            if (justGainedFocus) {
                                val requester = railFocusRequesters[selectedCategory]
                                val requested = if (requester != null) {
                                    runCatching { requester.requestFocus() }.isSuccess
                                } else {
                                    false
                                }
                                if (!requested) {
                                    focusManager.moveFocus(FocusDirection.Down)
                                }
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                allowDetailAutofocus = true
                                false
                            } else {
                                false
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                ) {
                    items(
                        items = visibleSections,
                        key = { it.category }
                    ) { section ->
                        SettingsRailButton(
                            title = section.title,
                            icon = section.icon,
                            rawIconRes = section.rawIconRes,
                            isSelected = selectedCategory == section.category,
                            focusRequester = railFocusRequesters[section.category],
                            onClick = {
                                if (section.destination == SettingsSectionDestination.External) {
                                    when (section.category) {
                                        SettingsCategory.ACCOUNT -> onNavigateToAuthQrSignIn()
                                        SettingsCategory.CATALOGS -> onNavigateToCatalogs()
                                        else -> Unit
                                    }
                                } else {
                                    if (section.category == SettingsCategory.INTEGRATION) {
                                        integrationSection = IntegrationSettingsSection.Hub
                                    }
                                    allowDetailAutofocus = true
                                    selectedCategory = section.category
                                    pendingContentFocusCategory = section.category
                                    pendingContentFocusRequestId += 1L
                                }
                            }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                                val movedLeft = focusManager.moveFocus(FocusDirection.Left)
                                if (!movedLeft) {
                                    allowDetailAutofocus = false
                                    val requested = railFocusRequesters[selectedCategory]?.let { requester ->
                                        runCatching { requester.requestFocus() }.isSuccess
                                    } ?: false
                                    if (!requested) {
                                        runCatching { railContainerFocusRequester.requestFocus() }
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }
                        .onFocusChanged { state ->
                            if (state.hasFocus && !allowDetailAutofocus) {
                                railFocusRequesters[selectedCategory]?.let { requester ->
                                    runCatching { requester.requestFocus() }
                                }
                            }
                        }
                ) {
                    when (selectedCategory) {
                        SettingsCategory.APPEARANCE -> ThemeSettingsContent(
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.APPEARANCE]
                            } else {
                                null
                            }
                        )
                        SettingsCategory.LAYOUT -> LayoutSettingsContent(
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.LAYOUT]
                            } else {
                                null
                            }
                        )
                        SettingsCategory.PLAYBACK -> PlaybackSettingsContent(
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.PLAYBACK]
                            } else {
                                null
                            }
                        )
                        SettingsCategory.INTEGRATION -> IntegrationSettingsContent(
                            selectedSection = integrationSection,
                            onSelectSection = { integrationSection = it },
                            isPrimaryProfile = isPrimaryProfile,
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.INTEGRATION]
                            } else {
                                null
                            },
                            hubFocusRequester = integrationHubFocusRequester,
                            debridFocusRequester = integrationDebridFocusRequester,
                            traktFocusRequester = integrationTraktFocusRequester,
                            simklFocusRequester = integrationSimklFocusRequester,
                            kitsuFocusRequester = integrationKitsuFocusRequester,
                            theIntroDbFocusRequester = integrationTheIntroDbFocusRequester,
                            tmdbFocusRequester = integrationTmdbFocusRequester,
                            tvdbFocusRequester = integrationTvdbFocusRequester,
                            omdbFocusRequester = integrationOmdbFocusRequester,
                            mdbListFocusRequester = integrationMdbListFocusRequester,
                            animeSkipFocusRequester = integrationAnimeSkipFocusRequester,
                            subtitleTranslationFocusRequester = integrationSubtitleTranslationFocusRequester,
                            openSubtitlesFocusRequester = integrationOpenSubtitlesFocusRequester,
                            wyzieSubtitlesFocusRequester = integrationWyzieSubtitlesFocusRequester,
                            posterRatingsFocusRequester = integrationPosterRatingsFocusRequester,
                            autoFocusEnabled = allowDetailAutofocus
                        )
                        SettingsCategory.ABOUT -> AboutSettingsContent(
                            initialFocusRequester = if (allowDetailAutofocus) {
                                contentFocusRequesters[SettingsCategory.ABOUT]
                            } else {
                                null
                            }
                        )
                        SettingsCategory.ACCOUNT -> AccountSettingsInline(
                            settingsViewModel = settingsViewModel,
                            onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn
                        )
                        SettingsCategory.DEBUG -> DebugSettingsContent()
                        SettingsCategory.CATALOGS -> Unit
                    }
                }
            }
            } // end Column wrapper
        }
    }

    profileToDelete?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            deleteInProgress = deleteInProgress,
            onKeepProfile = { settingsViewModel.dismissDeleteDialog() },
            onDeleteProfile = { settingsViewModel.confirmDeleteProfile() }
        )
    }
}

@Composable
private fun ProfileHeaderRow(
    profile: UserProfile,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProfileAvatarCircle(
            name = profile.name,
            colorHex = profile.avatarColorHex,
            size = 40.dp,
            avatarImageUrl = profile.avatarUrl
        )
        Column {
            androidx.tv.material3.Text(
                text = profile.name,
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                color = NexioColors.TextPrimary
            )
        }
    }
}

@Composable
private fun AccountSettingsInline(
    settingsViewModel: SettingsViewModel,
    onNavigateToAuthQrSignIn: () -> Unit
) {
    val accountViewModel: com.nexio.tv.ui.screens.account.AccountViewModel = hiltViewModel()
    val accountUiState by accountViewModel.uiState.collectAsStateWithLifecycle()
    val activeProfile by settingsViewModel.activeProfile.collectAsStateWithLifecycle()
    val isPrimaryProfile by settingsViewModel.isPrimaryProfile.collectAsStateWithLifecycle()
    val hasLiveFullAccountSession by settingsViewModel.hasLiveFullAccountSession.collectAsStateWithLifecycle()
    val syncStatus by settingsViewModel.syncStatus.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_account),
            subtitle = stringResource(R.string.settings_account_section_subtitle)
        )
        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = "Profiles"
        ) {
            if (hasLiveFullAccountSession) {
                SyncNowRow(
                    syncStatus = syncStatus,
                    onSyncNow = { settingsViewModel.triggerSyncNow() }
                )
            }
            activeProfile?.takeUnless { isPrimaryProfile }?.let { profile ->
                Button(
                    onClick = { settingsViewModel.requestDeleteProfile(profile) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0xFF4A2323),
                        contentColor = NexioColors.TextPrimary
                    )
                ) {
                    Text("Delete Profile", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            com.nexio.tv.ui.screens.account.AccountSettingsContent(
                uiState = accountUiState,
                viewModel = accountViewModel,
                onNavigateToAuthQrSignIn = onNavigateToAuthQrSignIn
            )
        }
    }
}

@Composable
private fun SyncNowRow(
    syncStatus: SyncStatus,
    onSyncNow: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when (syncStatus) {
                SyncStatus.IDLE -> ""
                SyncStatus.SYNCING -> "Syncing..."
                SyncStatus.SUCCESS -> "Synced successfully"
                SyncStatus.ERROR -> "Sync failed. Check your connection and try again."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when (syncStatus) {
                SyncStatus.ERROR -> NexioColors.Error
                SyncStatus.SUCCESS -> NexioColors.Success
                else -> NexioColors.TextSecondary
            },
            modifier = Modifier.weight(1f)
        )

        if (syncStatus == SyncStatus.SYNCING) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = NexioColors.TextPrimary
            )
        } else {
            Button(
                onClick = onSyncNow,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.Background,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text("Sync Now", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DeleteProfileDialog(
    profile: UserProfile,
    deleteInProgress: Boolean,
    onKeepProfile: () -> Unit,
    onDeleteProfile: () -> Unit
) {
    val keepFocusRequester = remember { FocusRequester() }

    NexioDialog(
        onDismiss = onKeepProfile,
        title = "Delete Profile",
        subtitle = "This will permanently delete ${profile.name} and remove all associated data from this device and the cloud. This cannot be undone.",
        width = 420.dp
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onKeepProfile,
                enabled = !deleteInProgress,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(keepFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.Background,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text("Keep Profile", style = MaterialTheme.typography.labelLarge)
            }

            Button(
                onClick = onDeleteProfile,
                enabled = !deleteInProgress,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = Color(0xFF4A2323),
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text("Delete Profile", style = MaterialTheme.typography.labelLarge)
            }
        }

        LaunchedEffect(profile.id) {
            runCatching { keepFocusRequester.requestFocus() }
        }
    }
}

@Composable
private fun IntegrationSettingsContent(
    selectedSection: IntegrationSettingsSection,
    onSelectSection: (IntegrationSettingsSection) -> Unit,
    isPrimaryProfile: Boolean,
    initialFocusRequester: FocusRequester?,
    hubFocusRequester: FocusRequester,
    debridFocusRequester: FocusRequester,
    traktFocusRequester: FocusRequester,
    simklFocusRequester: FocusRequester,
    kitsuFocusRequester: FocusRequester,
    theIntroDbFocusRequester: FocusRequester,
    tmdbFocusRequester: FocusRequester,
    tvdbFocusRequester: FocusRequester,
    omdbFocusRequester: FocusRequester,
    mdbListFocusRequester: FocusRequester,
    animeSkipFocusRequester: FocusRequester,
    subtitleTranslationFocusRequester: FocusRequester,
    openSubtitlesFocusRequester: FocusRequester,
    wyzieSubtitlesFocusRequester: FocusRequester,
    posterRatingsFocusRequester: FocusRequester,
    autoFocusEnabled: Boolean
) {
    BackHandler(enabled = selectedSection != IntegrationSettingsSection.Hub) {
        onSelectSection(IntegrationSettingsSection.Hub)
    }

    val sharedSections = remember {
        setOf(
            IntegrationSettingsSection.Debrid, IntegrationSettingsSection.TheIntroDb,
            IntegrationSettingsSection.Tmdb, IntegrationSettingsSection.Tvdb,
            IntegrationSettingsSection.Omdb,
            IntegrationSettingsSection.MdbList,
            IntegrationSettingsSection.AnimeSkip, IntegrationSettingsSection.SubtitleTranslation,
            IntegrationSettingsSection.OpenSubtitles,
            IntegrationSettingsSection.WyzieSubtitles,
            IntegrationSettingsSection.PosterRatings
        )
    }
    LaunchedEffect(isPrimaryProfile, selectedSection) {
        if (!isPrimaryProfile && selectedSection in sharedSections) {
            onSelectSection(IntegrationSettingsSection.Hub)
        }
    }

    val hubEntryFocusRequester = initialFocusRequester ?: hubFocusRequester

    LaunchedEffect(selectedSection, autoFocusEnabled) {
        if (!autoFocusEnabled) return@LaunchedEffect
        val requester = when (selectedSection) {
            IntegrationSettingsSection.Hub -> hubEntryFocusRequester
            IntegrationSettingsSection.Debrid -> debridFocusRequester
            IntegrationSettingsSection.Trakt -> traktFocusRequester
            IntegrationSettingsSection.Simkl -> simklFocusRequester
            IntegrationSettingsSection.Kitsu -> kitsuFocusRequester
            IntegrationSettingsSection.TheIntroDb -> theIntroDbFocusRequester
            IntegrationSettingsSection.Tmdb -> tmdbFocusRequester
            IntegrationSettingsSection.Tvdb -> tvdbFocusRequester
            IntegrationSettingsSection.Omdb -> omdbFocusRequester
            IntegrationSettingsSection.MdbList -> mdbListFocusRequester
            IntegrationSettingsSection.AnimeSkip -> animeSkipFocusRequester
            IntegrationSettingsSection.SubtitleTranslation -> subtitleTranslationFocusRequester
            IntegrationSettingsSection.OpenSubtitles -> openSubtitlesFocusRequester
            IntegrationSettingsSection.WyzieSubtitles -> wyzieSubtitlesFocusRequester
            IntegrationSettingsSection.PosterRatings -> posterRatingsFocusRequester
        }
        runCatching { requester.requestFocus() }
    }

    when (selectedSection) {
        IntegrationSettingsSection.Hub -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingsDetailHeader(
                    title = stringResource(R.string.settings_integrations_section),
                    subtitle = stringResource(R.string.settings_integrations_section_subtitle)
                )

                SettingsGroupCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isPrimaryProfile) {
                            item(key = "integration_hub_debrid") {
                                SettingsActionRow(
                                    title = stringResource(R.string.debrid_title),
                                    subtitle = stringResource(R.string.debrid_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.Debrid) },
                                    modifier = Modifier.focusRequester(hubEntryFocusRequester)
                                )
                            }
                        }
                        item(key = "integration_hub_trakt") {
                            SettingsActionRow(
                                title = "Trakt",
                                subtitle = stringResource(R.string.settings_trakt_subtitle),
                                onClick = { onSelectSection(IntegrationSettingsSection.Trakt) },
                                modifier = if (!isPrimaryProfile) Modifier.focusRequester(hubEntryFocusRequester) else Modifier
                            )
                        }
                        item(key = "integration_hub_simkl") {
                            SettingsActionRow(
                                title = stringResource(R.string.simkl_title),
                                subtitle = stringResource(R.string.settings_simkl_subtitle),
                                onClick = { onSelectSection(IntegrationSettingsSection.Simkl) }
                            )
                        }
                        item(key = "integration_hub_kitsu") {
                            SettingsActionRow(
                                title = "Kitsu",
                                subtitle = "Authenticate anime metadata requests.",
                                onClick = { onSelectSection(IntegrationSettingsSection.Kitsu) }
                            )
                        }
                        if (isPrimaryProfile) {
                            item(key = "integration_hub_theintrodb") {
                                SettingsActionRow(
                                    title = stringResource(R.string.theid_title),
                                    subtitle = stringResource(R.string.settings_theintrodb_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.TheIntroDb) }
                                )
                            }
                            item(key = "integration_hub_tmdb") {
                                SettingsActionRow(
                                    title = "TMDB",
                                    subtitle = stringResource(R.string.settings_tmdb_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.Tmdb) }
                                )
                            }
                            item(key = "integration_hub_tvdb") {
                                SettingsActionRow(
                                    title = stringResource(R.string.tvdb_hub_title),
                                    subtitle = stringResource(R.string.settings_tvdb_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.Tvdb) }
                                )
                            }
                            item(key = "integration_hub_omdb") {
                                SettingsActionRow(
                                    title = "OMDB",
                                    subtitle = stringResource(R.string.settings_omdb_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.Omdb) }
                                )
                            }
                            item(key = "integration_hub_mdblist") {
                                SettingsActionRow(
                                    title = "MDBList",
                                    subtitle = stringResource(R.string.settings_mdblist_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.MdbList) }
                                )
                            }
                            item(key = "integration_hub_animeskip") {
                                SettingsActionRow(
                                    title = "Anime-Skip",
                                    subtitle = stringResource(R.string.settings_animeskip_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.AnimeSkip) }
                                )
                            }
                            item(key = "integration_hub_subtitle_translation") {
                                SettingsActionRow(
                                    title = stringResource(R.string.subtitle_translation_title),
                                    subtitle = stringResource(R.string.settings_subtitle_translation_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.SubtitleTranslation) }
                                )
                            }
                            item(key = "integration_hub_open_subtitles") {
                                SettingsActionRow(
                                    title = "OpenSubtitles",
                                    subtitle = "Native subtitle search and exact file-hash matching",
                                    onClick = { onSelectSection(IntegrationSettingsSection.OpenSubtitles) }
                                )
                            }
                            item(key = "integration_hub_wyzie_subtitles") {
                                SettingsActionRow(
                                    title = stringResource(R.string.wyzie_subtitles_title),
                                    subtitle = stringResource(R.string.settings_wyzie_subtitles_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.WyzieSubtitles) }
                                )
                            }
                            item(key = "integration_hub_poster_ratings") {
                                SettingsActionRow(
                                    title = stringResource(R.string.poster_ratings_title),
                                    subtitle = stringResource(R.string.poster_ratings_subtitle),
                                    onClick = { onSelectSection(IntegrationSettingsSection.PosterRatings) }
                                )
                            }
                        }
                    }
                }
            }
        }

        IntegrationSettingsSection.Debrid -> {
            DebridSettingsContent(
                initialFocusRequester = debridFocusRequester
            )
        }

        IntegrationSettingsSection.Trakt -> {
            TraktSettingsContent(
                initialFocusRequester = traktFocusRequester
            )
        }

        IntegrationSettingsSection.Simkl -> {
            SimklSettingsContent(
                initialFocusRequester = simklFocusRequester
            )
        }

        IntegrationSettingsSection.Kitsu -> {
            KitsuSettingsContent(
                initialFocusRequester = kitsuFocusRequester
            )
        }

        IntegrationSettingsSection.TheIntroDb -> {
            TheIntroDbSettingsContent(
                initialFocusRequester = theIntroDbFocusRequester
            )
        }

        IntegrationSettingsSection.Tmdb -> {
            TmdbSettingsContent(
                initialFocusRequester = tmdbFocusRequester
            )
        }

        IntegrationSettingsSection.Tvdb -> {
            TvdbSettingsContent(
                initialFocusRequester = tvdbFocusRequester
            )
        }

        IntegrationSettingsSection.Omdb -> {
            OmdbSettingsContent(
                initialFocusRequester = omdbFocusRequester
            )
        }

        IntegrationSettingsSection.MdbList -> {
            MDBListSettingsContent(
                initialFocusRequester = mdbListFocusRequester
            )
        }

        IntegrationSettingsSection.AnimeSkip -> {
            AnimeSkipSettingsContent(
                initialFocusRequester = animeSkipFocusRequester
            )
        }

        IntegrationSettingsSection.SubtitleTranslation -> {
            SubtitleTranslationSettingsContent(
                initialFocusRequester = subtitleTranslationFocusRequester
            )
        }

        IntegrationSettingsSection.OpenSubtitles -> {
            OpenSubtitlesSettingsContent(
                initialFocusRequester = openSubtitlesFocusRequester
            )
        }

        IntegrationSettingsSection.WyzieSubtitles -> {
            WyzieSubtitleSettingsContent(
                initialFocusRequester = wyzieSubtitlesFocusRequester
            )
        }

        IntegrationSettingsSection.PosterRatings -> {
            PosterRatingsSettingsContent(
                initialFocusRequester = posterRatingsFocusRequester
            )
        }
    }
}
