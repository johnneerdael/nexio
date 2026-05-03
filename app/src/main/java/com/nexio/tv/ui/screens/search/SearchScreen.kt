package com.nexio.tv.ui.screens.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nexio.tv.ui.components.CatalogRowSection
import com.nexio.tv.data.repository.TmdbDiscoveryService
import com.nexio.tv.ui.components.EmptyScreenState
import com.nexio.tv.ui.components.ErrorState
import com.nexio.tv.ui.components.LoadingIndicator
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.components.PosterCardDefaults
import com.nexio.tv.ui.components.PosterCardStyle
import com.nexio.tv.ui.theme.NexioColors
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import com.nexio.tv.data.remote.api.ImdbSuggestion
import com.nexio.tv.domain.model.MetaPreview

private val SearchScreenHorizontalPadding = 48.dp
private val SearchInputButtonSize = 56.dp
private val SearchInputButtonSpacing = 12.dp
private val SearchPanelSpacing = 12.dp
private val SearchSelectableItemShape = RoundedCornerShape(12.dp)

internal enum class SearchFieldDownTarget {
    None,
    RecentActions,
    Results
}

internal fun resolveSearchFieldDownTarget(
    canMoveToResults: Boolean,
    showRecentSearches: Boolean
): SearchFieldDownTarget = when {
    canMoveToResults -> SearchFieldDownTarget.Results
    showRecentSearches -> SearchFieldDownTarget.RecentActions
    else -> SearchFieldDownTarget.None
}

internal data class SearchManualStreamSelectionTarget(
    val item: MetaPreview,
    val addonBaseUrl: String
)

private const val TMDB_PERSON_ID_PREFIX = TmdbDiscoveryService.TMDB_PERSON_ID_PREFIX

internal fun shouldShowSearchManualStreamSelection(
    deterministicAutoplayEnabled: Boolean,
    apiType: String
): Boolean {
    return deterministicAutoplayEnabled && (
        apiType.equals("movie", ignoreCase = true) ||
            apiType.equals("series", ignoreCase = true)
    )
}

internal fun searchKeyboardCompletionLabels(suggestions: List<String>): List<String> = suggestions

internal fun searchDropdownStartPadding(showVoiceSearch: Boolean): Dp {
    val voiceButtonWidth = if (showVoiceSearch) {
        SearchInputButtonSize + SearchInputButtonSpacing
    } else {
        0.dp
    }
    return SearchScreenHorizontalPadding +
        SearchInputButtonSize +
        SearchInputButtonSpacing +
        voiceButtonWidth
}

private fun searchDropdownHorizontalPadding(showVoiceSearch: Boolean): PaddingValues = PaddingValues(
    start = searchDropdownStartPadding(showVoiceSearch),
    end = SearchScreenHorizontalPadding
)

private fun buildSearchKeyboardCompletions(suggestions: List<String>): Array<CompletionInfo> {
    return searchKeyboardCompletionLabels(suggestions).mapIndexed { index, name ->
        CompletionInfo(index.toLong(), index, name)
    }.toTypedArray()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onPlayWithManualStreamSelection: (MetaPreview, String) -> Unit = { _, _ -> },
    onNavigateToSeeAll: (catalogId: String, addonId: String, type: String) -> Unit = { _, _, _ -> },
    onNavigateToCastDetail: (personId: Int, personName: String) -> Unit = { _, _ -> },
    onOpenDiscover: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val strVoiceNoSpeech = stringResource(R.string.search_voice_no_speech)
    val strVoiceMicPermission = stringResource(R.string.search_voice_mic_permission)
    val strVoiceFailed = stringResource(R.string.search_voice_failed)
    val strVoiceUnavailable = stringResource(R.string.search_voice_unavailable)
    val voiceFailedWithCodeTemplate = stringResource(R.string.search_voice_failed_with_code)
    val voiceFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val recentClearFocusRequester = remember { FocusRequester() }
    val recentFirstItemFocusRequester = remember { FocusRequester() }
    val discoverFirstItemFocusRequester = remember { FocusRequester() }
    var isSearchFieldAttached by remember { mutableStateOf(false) }
    var isSearchAreaFocused by remember { mutableStateOf(false) }
    var focusResults by remember { mutableStateOf(false) }
    var pendingFocusMoveToResultsQuery by remember { mutableStateOf<String?>(null) }
    var pendingFocusMoveSawSearching by remember { mutableStateOf(false) }
    var pendingFocusMoveHadExistingSearchRows by remember { mutableStateOf(false) }
    var isVoiceListening by remember { mutableStateOf(false) }
    var discoverFocusedItemIndex by rememberSaveable { mutableStateOf(0) }
    var restoreDiscoverFocus by rememberSaveable { mutableStateOf(false) }
    var pendingDiscoverRestoreOnResume by rememberSaveable { mutableStateOf(false) }
    var searchManualStreamSelectionTarget by remember { mutableStateOf<SearchManualStreamSelectionTarget?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val onVoiceQueryResultState = rememberUpdatedState<(String) -> Unit> { recognized ->
        if (recognized.isNotBlank()) {
            viewModel.onEvent(SearchEvent.QueryChanged(recognized))
            viewModel.onEvent(SearchEvent.SubmitSearch)
            focusResults = false
            pendingFocusMoveToResultsQuery = recognized
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows =
                uiState.submittedQuery.trim().length >= 2 && uiState.catalogRows.any { it.items.isNotEmpty() }
        } else {
            Toast.makeText(context, strVoiceNoSpeech, Toast.LENGTH_SHORT).show()
        }
    }
    val isVoiceSearchAvailable = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    val speechRecognizer = remember(context, isVoiceSearchAvailable) {
        if (isVoiceSearchAvailable) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
    }
    var recordAudioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val latestOnVoiceQueryResult by rememberUpdatedState(onVoiceQueryResultState.value)
    val startListeningNow: () -> Unit = remember(speechRecognizer) {
        {
            val recognizer = speechRecognizer
            if (recognizer == null) {
                isVoiceListening = false
                Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
            } else {
                isVoiceListening = true
                runCatching {
                    recognizer.startListening(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                        }
                    )
                }.onFailure {
                    isVoiceListening = false
                    Toast.makeText(context, strVoiceFailed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val requestAudioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        recordAudioPermissionGranted = granted
        if (granted) {
            startListeningNow()
        } else {
            Toast.makeText(context, strVoiceMicPermission, Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                isVoiceListening = false
                when (error) {
                    SpeechRecognizer.ERROR_CLIENT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Unit
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        Toast.makeText(context, strVoiceNoSpeech, Toast.LENGTH_SHORT).show()
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Toast.makeText(context, strVoiceMicPermission, Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        val msg = voiceFailedWithCodeTemplate.format(error)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isVoiceListening = false
                val recognized = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                latestOnVoiceQueryResult(recognized)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
        }

        speechRecognizer?.setRecognitionListener(listener)
        onDispose {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
        }
    }
    val topInputFocusRequester = remember(isVoiceSearchAvailable) {
        if (isVoiceSearchAvailable) voiceFocusRequester else searchFocusRequester
    }
    val launchVoiceSearch: () -> Unit = {
        if (!isVoiceSearchAvailable || speechRecognizer == null) {
            Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
        } else if (!recordAudioPermissionGranted) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startListeningNow()
        }
    }
    val cancelVoiceSearch: () -> Unit = {
        runCatching { speechRecognizer?.cancel() }
        isVoiceListening = false
    }

    val posterCardStyle = remember(uiState.posterCardWidthDp, uiState.posterCardCornerRadiusDp) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val trimmedQuery = remember(uiState.query) { uiState.query.trim() }
    val trimmedSubmittedQuery = remember(uiState.submittedQuery) { uiState.submittedQuery.trim() }
    val isDiscoverMode = remember(uiState.discoverEnabled, trimmedSubmittedQuery) {
        uiState.discoverEnabled && trimmedSubmittedQuery.isEmpty()
    }
    val dropdownContentPadding = remember(isVoiceSearchAvailable) {
        searchDropdownHorizontalPadding(showVoiceSearch = isVoiceSearchAvailable)
    }
    val hasPendingUnsubmittedQuery = remember(isDiscoverMode, trimmedQuery, trimmedSubmittedQuery) {
        !isDiscoverMode && trimmedQuery.length >= 2 && trimmedQuery != trimmedSubmittedQuery
    }
    val showRecentSearches = remember(isSearchAreaFocused, trimmedQuery, uiState.recentSearches) {
        isSearchAreaFocused && trimmedQuery.isEmpty() && uiState.recentSearches.isNotEmpty()
    }
    val canMoveToResults = remember(
        isDiscoverMode,
        uiState.discoverResults,
        trimmedSubmittedQuery,
        uiState.catalogRows
    ) {
        if (isDiscoverMode) false else trimmedSubmittedQuery.length >= 2 && uiState.catalogRows.any { it.items.isNotEmpty() }
    }
    val searchFieldDownTarget = remember(canMoveToResults, showRecentSearches) {
        resolveSearchFieldDownTarget(
            canMoveToResults = canMoveToResults,
            showRecentSearches = showRecentSearches
        )
    }
    val submitCurrentQuery: (String) -> Unit = { submittedQuery ->
        viewModel.onEvent(SearchEvent.SubmitSearch)
        focusResults = false
        if (submittedQuery.length >= 2) {
            pendingFocusMoveToResultsQuery = submittedQuery
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows =
                trimmedSubmittedQuery.length >= 2 && uiState.catalogRows.any { row -> row.items.isNotEmpty() }
        } else {
            pendingFocusMoveToResultsQuery = null
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows = false
        }
    }
    val handleQueryChanged: (String) -> Unit = { nextQuery ->
        val previousQuery = uiState.query.trim()
        val trimmedNextQuery = nextQuery.trim()
        val selectedSuggestion = trimmedNextQuery.length >= 2 &&
            trimmedNextQuery != trimmedSubmittedQuery &&
            uiState.suggestions.any { it.equals(trimmedNextQuery, ignoreCase = true) } &&
            trimmedNextQuery.startsWith(previousQuery, ignoreCase = true) &&
            trimmedNextQuery.length - previousQuery.length > 1

        focusResults = false
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
        viewModel.onEvent(SearchEvent.QueryChanged(nextQuery))
        if (selectedSuggestion) {
            submitCurrentQuery(trimmedNextQuery)
        }
    }
    val submitRecentSearch: (String) -> Unit = { recentQuery ->
        val trimmedRecentQuery = recentQuery.trim()
        if (trimmedRecentQuery.isNotEmpty()) {
            viewModel.onEvent(SearchEvent.QueryChanged(trimmedRecentQuery))
            submitCurrentQuery(trimmedRecentQuery)
        }
    }

    LaunchedEffect(focusResults, isDiscoverMode, uiState.discoverResults.size) {
        if (focusResults && isDiscoverMode && uiState.discoverResults.isNotEmpty()) {
            delay(100)
            runCatching { discoverFirstItemFocusRequester.requestFocus() }
            focusResults = false
            pendingFocusMoveToResultsQuery = null
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows = false
        }
    }

    LaunchedEffect(
        pendingFocusMoveToResultsQuery,
        pendingFocusMoveSawSearching,
        pendingFocusMoveHadExistingSearchRows,
        uiState.isSearching,
        uiState.submittedQuery,
        canMoveToResults,
        isDiscoverMode
    ) {
        val pendingQuery = pendingFocusMoveToResultsQuery ?: return@LaunchedEffect
        val currentSubmittedQuery = uiState.submittedQuery.trim()
        if (currentSubmittedQuery != pendingQuery) return@LaunchedEffect

        if (uiState.isSearching) {
            pendingFocusMoveSawSearching = true
            return@LaunchedEffect
        }

        val shouldRequireSeenSearching = pendingFocusMoveHadExistingSearchRows
        if ((shouldRequireSeenSearching && !pendingFocusMoveSawSearching) || !canMoveToResults) {
            return@LaunchedEffect
        }

        if (isDiscoverMode) {
            focusResults = true
        } else {
            // Use explicit first-item focus for deterministic landing on row 1 / column 1.
            delay(80)
            focusResults = true
        }
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
    }

    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { topInputFocusRequester.requestFocus() }
    }

    // Push search suggestions to the native keyboard suggestion bar
    LaunchedEffect(uiState.suggestions) {
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return@LaunchedEffect
        imm.displayCompletions(view, buildSearchKeyboardCompletions(uiState.suggestions))
    }

    val latestPendingDiscoverRestore by rememberUpdatedState(pendingDiscoverRestoreOnResume)
    val latestShouldKeepSearchFocus by rememberUpdatedState(
        focusResults || uiState.isSearching || isVoiceListening
    )
    val latestVoiceSearchAvailable by rememberUpdatedState(isVoiceSearchAvailable)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (latestPendingDiscoverRestore) {
                    restoreDiscoverFocus = true
                    pendingDiscoverRestoreOnResume = false
                } else if (!latestShouldKeepSearchFocus) {
                    coroutineScope.launch {
                        repeat(2) { withFrameNanos { } }
                        runCatching {
                            if (latestVoiceSearchAvailable) {
                                voiceFocusRequester.requestFocus()
                            } else {
                                searchFocusRequester.requestFocus()
                            }
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val latestLaunchVoiceSearch by rememberUpdatedState(launchVoiceSearch)
    val latestIsVoiceListening by rememberUpdatedState(isVoiceListening)
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            if (!latestIsVoiceListening) latestLaunchVoiceSearch()
            true
        }
        com.nexio.tv.MainActivity.voiceKeyHandler = handler
        onDispose {
            if (com.nexio.tv.MainActivity.voiceKeyHandler === handler) {
                com.nexio.tv.MainActivity.voiceKeyHandler = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexioColors.Background)
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_VOICE_ASSIST,
                        KeyEvent.KEYCODE_ASSIST,
                        KeyEvent.KEYCODE_SEARCH -> {
                            if (!isVoiceListening) launchVoiceSearch()
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            },
        contentAlignment = Alignment.TopCenter
    ) {
        if (isDiscoverMode) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 10.dp)
                    .onFocusChanged { isSearchAreaFocused = it.hasFocus }
            ) {
                SearchInputField(
                    query = uiState.query,
                    moveDownTarget = searchFieldDownTarget,
                    recentActionsFocusRequester = if (showRecentSearches) recentClearFocusRequester else null,
                    voiceFocusRequester = if (isVoiceSearchAvailable) voiceFocusRequester else null,
                    searchFocusRequester = searchFocusRequester,
                    onAttached = { isSearchFieldAttached = true },
                    onQueryChanged = handleQueryChanged,
                    onSubmit = {
                        submitCurrentQuery(uiState.query.trim())
                    },
                    showVoiceSearch = isVoiceSearchAvailable,
                    onVoiceSearch = launchVoiceSearch,
                    onMoveToResults = { focusResults = true },
                    onOpenDiscover = onOpenDiscover,
                    keyboardController = keyboardController
                )

                if (uiState.imdbSuggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(SearchPanelSpacing))
                    ImdbSuggestionDropdown(
                        suggestions = uiState.imdbSuggestions,
                        posterUrls = uiState.imdbSuggestionPosters,
                        posterPreviewEnabled = uiState.searchPosterPreviewEnabled,
                        onSelect = { suggestion ->
                            val type = if (suggestion.titleType.equals("movie", ignoreCase = true)) "movie" else "series"
                            onNavigateToDetail(suggestion.tconst, type, "")
                        },
                        listMaxHeight = 280.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dropdownContentPadding)
                    )
                }

                if (showRecentSearches) {
                    Spacer(modifier = Modifier.height(SearchPanelSpacing))
                    RecentSearchesSection(
                        recentSearches = uiState.recentSearches,
                        onRecentSearch = submitRecentSearch,
                        onClear = { viewModel.onEvent(SearchEvent.ClearRecentSearches) },
                        searchFocusRequester = searchFocusRequester,
                        clearFocusRequester = recentClearFocusRequester,
                        firstRecentSearchFocusRequester = recentFirstItemFocusRequester,
                        listMaxHeight = 280.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dropdownContentPadding)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyScreenState(
                            title = stringResource(R.string.search_start_title),
                            subtitle = stringResource(R.string.search_start_subtitle),
                            icon = Icons.Default.Search
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { isSearchAreaFocused = it.hasFocus },
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SearchInputField(
                        query = uiState.query,
                        moveDownTarget = searchFieldDownTarget,
                        recentActionsFocusRequester = if (showRecentSearches) recentClearFocusRequester else null,
                        voiceFocusRequester = if (isVoiceSearchAvailable) voiceFocusRequester else null,
                        searchFocusRequester = searchFocusRequester,
                        onAttached = { isSearchFieldAttached = true },
                        onQueryChanged = handleQueryChanged,
                        onSubmit = {
                            submitCurrentQuery(uiState.query.trim())
                        },
                        showVoiceSearch = isVoiceSearchAvailable,
                        onVoiceSearch = launchVoiceSearch,
                        onMoveToResults = {
                            focusResults = true
                        },
                        onOpenDiscover = onOpenDiscover,
                        keyboardController = keyboardController
                    )
                }

                if (uiState.imdbSuggestions.isNotEmpty()) {
                    item {
                        ImdbSuggestionDropdown(
                            suggestions = uiState.imdbSuggestions,
                            posterUrls = uiState.imdbSuggestionPosters,
                            posterPreviewEnabled = uiState.searchPosterPreviewEnabled,
                            onSelect = { suggestion ->
                                val type = if (suggestion.titleType.equals("movie", ignoreCase = true)) "movie" else "series"
                                onNavigateToDetail(suggestion.tconst, type, "")
                            },
                            listMaxHeight = 280.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(dropdownContentPadding)
                        )
                    }
                }

                if (trimmedSubmittedQuery.length < 2 || hasPendingUnsubmittedQuery) {
                    item {
                        Text(
                            text = stringResource(R.string.search_keyboard_hint),
                            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                            color = NexioColors.TextSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 52.dp)
                        )
                    }
                }

                if (showRecentSearches) {
                    item {
                        RecentSearchesSection(
                            recentSearches = uiState.recentSearches,
                            onRecentSearch = submitRecentSearch,
                            onClear = { viewModel.onEvent(SearchEvent.ClearRecentSearches) },
                            searchFocusRequester = searchFocusRequester,
                            clearFocusRequester = recentClearFocusRequester,
                            firstRecentSearchFocusRequester = recentFirstItemFocusRequester,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(dropdownContentPadding)
                        )
                    }
                }

                when {
                    trimmedSubmittedQuery.length < 2 && !hasPendingUnsubmittedQuery && !showRecentSearches -> {
                        item {
                            EmptyScreenState(
                                title = stringResource(R.string.search_start_title),
                                subtitle = if (uiState.discoverEnabled) {
                                    stringResource(R.string.search_start_subtitle)
                                } else {
                                    stringResource(R.string.search_start_subtitle_no_discover)
                                },
                                icon = Icons.Default.Search
                            )
                        }
                    }

                    uiState.isSearching && uiState.catalogRows.isEmpty() -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 80.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator()
                            }
                        }
                    }

                    uiState.error != null && uiState.catalogRows.isEmpty() -> {
                        item {
                            ErrorState(
                                message = uiState.error ?: "Search failed",
                                onRetry = { viewModel.onEvent(SearchEvent.Retry) }
                            )
                        }
                    }

                    !showRecentSearches && (uiState.catalogRows.isEmpty() || uiState.catalogRows.none { it.items.isNotEmpty() }) -> {
                        item {
                            EmptyScreenState(
                                title = stringResource(R.string.search_no_results_title),
                                subtitle = stringResource(R.string.search_no_results_subtitle),
                                icon = Icons.Default.Search
                            )
                        }
                    }

                    else -> {
                        val visibleCatalogRows = uiState.catalogRows.filter { it.items.isNotEmpty() }

                        itemsIndexed(
                            items = visibleCatalogRows,
                            key = { index, item ->
                                "${item.addonId}_${item.type}_${item.catalogId}_${trimmedSubmittedQuery}_$index"
                            }
                        ) { index, catalogRow ->
                            CatalogRowSection(
                                catalogRow = catalogRow,
                                showPosterLabels = uiState.posterLabelsEnabled,
                                showAddonName = uiState.catalogAddonNameEnabled,
                                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                                enableRowFocusRestorer = false,
                                focusedItemIndex = if (focusResults && index == 0) 0 else -1,
                                onItemFocused = {
                                    if (focusResults) {
                                        focusResults = false
                                    }
                                },
                                onItemClick = { id, type, addonBaseUrl ->
                                    val personId = id.takeIf { it.startsWith(TMDB_PERSON_ID_PREFIX) }
                                        ?.removePrefix(TMDB_PERSON_ID_PREFIX)
                                        ?.toIntOrNull()
                                    if (personId != null) {
                                        val personName = catalogRow.items
                                            .firstOrNull { it.id == id }
                                            ?.name
                                            .orEmpty()
                                        onNavigateToCastDetail(personId, personName)
                                    } else {
                                        onNavigateToDetail(id, type, addonBaseUrl)
                                    }
                                },
                                onItemLongPress = { item, addonBaseUrl ->
                                    if (
                                        shouldShowSearchManualStreamSelection(
                                            deterministicAutoplayEnabled = uiState.deterministicAutoplayEnabled,
                                            apiType = item.apiType
                                        )
                                    ) {
                                        searchManualStreamSelectionTarget = SearchManualStreamSelectionTarget(
                                            item = item,
                                            addonBaseUrl = addonBaseUrl
                                        )
                                    }
                                },
                                onSeeAll = {
                                    onNavigateToSeeAll(
                                        catalogRow.catalogId,
                                        catalogRow.addonId,
                                        catalogRow.apiType
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (isVoiceListening) {
        VoiceListeningOverlay(
            title = stringResource(R.string.search_voice_listening_title),
            subtitle = stringResource(R.string.search_voice_listening_subtitle),
            cancelLabel = stringResource(R.string.search_voice_cancel),
            onCancel = cancelVoiceSearch
        )
    }

    val selectedManualTarget = searchManualStreamSelectionTarget
    if (selectedManualTarget != null) {
        SearchManualStreamSelectionDialog(
            title = selectedManualTarget.item.name,
            onDismiss = { searchManualStreamSelectionTarget = null },
            onPlayWithManualStreamSelection = {
                onPlayWithManualStreamSelection(
                    selectedManualTarget.item,
                    selectedManualTarget.addonBaseUrl
                )
                searchManualStreamSelectionTarget = null
            },
            onDetails = {
                onNavigateToDetail(
                    selectedManualTarget.item.id,
                    selectedManualTarget.item.apiType,
                    selectedManualTarget.addonBaseUrl
                )
                searchManualStreamSelectionTarget = null
            }
        )
    }
}

@Composable
private fun ImdbSuggestionDropdown(
    suggestions: List<ImdbSuggestion>,
    posterUrls: Map<String, String> = emptyMap(),
    posterPreviewEnabled: Boolean = false,
    onSelect: (ImdbSuggestion) -> Unit,
    modifier: Modifier = Modifier,
    listMaxHeight: Dp? = null
) {
    val listModifier = if (listMaxHeight != null) {
        Modifier
            .fillMaxWidth()
            .heightIn(max = listMaxHeight)
            .verticalScroll(rememberScrollState())
    } else {
        Modifier.fillMaxWidth()
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = listModifier,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            suggestions.forEach { suggestion ->
                Button(
                    onClick = { onSelect(suggestion) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = searchSelectableButtonColors(),
                    border = searchSelectableButtonBorder(),
                    scale = searchSelectableButtonScale(),
                    shape = ButtonDefaults.shape(SearchSelectableItemShape)
                ) {
                    val year = suggestion.startYear?.let { " ($it)" }.orEmpty()
                    val posterUrl = if (posterPreviewEnabled) posterUrls[suggestion.tconst] else null
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (posterPreviewEnabled) {
                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .height(42.dp)
                            ) {
                                if (posterUrl != null) {
                                    coil.compose.AsyncImage(
                                        model = coil.request.ImageRequest.Builder(LocalContext.current)
                                            .data(posterUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                NexioColors.Background,
                                                RoundedCornerShape(4.dp)
                                            )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Text(
                            text = suggestion.primaryTitle + year,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSearchesSection(
    recentSearches: List<String>,
    onRecentSearch: (String) -> Unit,
    onClear: () -> Unit,
    searchFocusRequester: FocusRequester,
    clearFocusRequester: FocusRequester,
    firstRecentSearchFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    listMaxHeight: Dp? = null
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_recent_title),
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                color = NexioColors.TextPrimary
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onClear,
                modifier = Modifier
                    .focusRequester(clearFocusRequester)
                    .focusProperties {
                        up = searchFocusRequester
                        down = firstRecentSearchFocusRequester
                    },
                colors = searchSelectableButtonColors(),
                border = searchSelectableButtonBorder(),
                scale = searchSelectableButtonScale(),
                shape = ButtonDefaults.shape(SearchSelectableItemShape)
            ) {
                Text(stringResource(R.string.search_recent_clear))
            }
        }

        val listModifier = if (listMaxHeight != null) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = listMaxHeight)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth()
        }

        Column(
            modifier = listModifier,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            recentSearches.forEachIndexed { index, query ->
                Button(
                    onClick = { onRecentSearch(query) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (index == 0) {
                                Modifier
                                    .focusRequester(firstRecentSearchFocusRequester)
                                    .focusProperties { up = clearFocusRequester }
                            } else {
                                Modifier
                            }
                        ),
                    colors = searchSelectableButtonColors(),
                    border = searchSelectableButtonBorder(),
                    scale = searchSelectableButtonScale(),
                    shape = ButtonDefaults.shape(SearchSelectableItemShape)
                ) {
                    Text(
                        text = query,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchManualStreamSelectionDialog(
    title: String,
    onDismiss: () -> Unit,
    onPlayWithManualStreamSelection: () -> Unit,
    onDetails: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.cw_dialog_subtitle)
    ) {
        Button(
            onClick = onPlayWithManualStreamSelection,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NexioColors.BackgroundCard,
                contentColor = NexioColors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.play_with_manual_stream_selection))
        }

        Button(
            onClick = onDetails,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NexioColors.BackgroundCard,
                contentColor = NexioColors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.cw_action_go_to_details))
        }
    }
}

@Composable
private fun SearchInputField(
    query: String,
    moveDownTarget: SearchFieldDownTarget,
    recentActionsFocusRequester: FocusRequester?,
    voiceFocusRequester: FocusRequester?,
    searchFocusRequester: FocusRequester,
    onAttached: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    showVoiceSearch: Boolean,
    onVoiceSearch: () -> Unit,
    onMoveToResults: () -> Unit,
    onOpenDiscover: () -> Unit,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?
) {
    var isDiscoverButtonFocused by remember { mutableStateOf(false) }
    var isVoiceButtonFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SearchScreenHorizontalPadding)
            .onGloballyPositioned { onAttached() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onOpenDiscover,
            modifier = Modifier
                .onFocusChanged { isDiscoverButtonFocused = it.isFocused }
                .size(SearchInputButtonSize)
                .searchInputButtonChrome(
                    isFocused = isDiscoverButtonFocused,
                    fillColor = NexioColors.BackgroundCard,
                    focusedBorderColor = NexioColors.FocusRing,
                    unfocusedBorderColor = NexioColors.Border
                )
        ) {
            Icon(
                imageVector = Icons.Default.Explore,
                contentDescription = stringResource(R.string.cd_open_discover),
                tint = NexioColors.TextPrimary
            )
        }

        Spacer(modifier = Modifier.width(SearchInputButtonSpacing))

        if (showVoiceSearch) {
            IconButton(
                onClick = onVoiceSearch,
                modifier = Modifier
                    .then(
                        if (voiceFocusRequester != null) {
                            Modifier.focusRequester(voiceFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                    .onFocusChanged { isVoiceButtonFocused = it.isFocused }
                    .size(SearchInputButtonSize)
                    .searchInputButtonChrome(
                        isFocused = isVoiceButtonFocused,
                        fillColor = NexioColors.BackgroundCard,
                        focusedBorderColor = NexioColors.FocusRing,
                        unfocusedBorderColor = NexioColors.Border
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.cd_voice_search),
                    tint = NexioColors.TextPrimary
                )
            }

            Spacer(modifier = Modifier.width(SearchInputButtonSpacing))
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .weight(1f)
                .focusRequester(searchFocusRequester)
                .focusProperties {
                    if (moveDownTarget == SearchFieldDownTarget.RecentActions && recentActionsFocusRequester != null) {
                        down = recentActionsFocusRequester
                    }
                }
                .onPreviewKeyEvent { keyEvent ->
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                onSubmit()
                            }
                            return@onPreviewKeyEvent true
                        }

                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            when (moveDownTarget) {
                                SearchFieldDownTarget.Results -> {
                                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                        onMoveToResults()
                                    }
                                    return@onPreviewKeyEvent true
                                }

                                SearchFieldDownTarget.RecentActions -> {
                                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                        runCatching { recentActionsFocusRequester?.requestFocus() }
                                    }
                                    return@onPreviewKeyEvent true
                                }

                                SearchFieldDownTarget.None -> Unit
                            }
                        }
                    }
                    false
                },
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Search,
                autoCorrectEnabled = false
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    onSubmit()
                    keyboardController?.hide()
                }
            ),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.search_placeholder),
                    color = NexioColors.TextTertiary
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = NexioColors.BackgroundCard,
                unfocusedContainerColor = NexioColors.BackgroundCard,
                focusedIndicatorColor = NexioColors.FocusRing,
                unfocusedIndicatorColor = NexioColors.Border,
                focusedTextColor = NexioColors.TextPrimary,
                unfocusedTextColor = NexioColors.TextPrimary,
                cursorColor = NexioColors.FocusRing
            )
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun VoiceListeningOverlay(
    title: String,
    subtitle: String,
    cancelLabel: String,
    onCancel: () -> Unit
) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "voice-mic-pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 700),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "voice-mic-pulse-scale"
    )

    NexioDialog(
        onDismiss = onCancel,
        title = title,
        subtitle = subtitle,
        width = 420.dp,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
                    .background(Color(0xFFE53935), RoundedCornerShape(48.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text(cancelLabel)
            }
        }
    }
}

private fun Modifier.searchInputButtonChrome(
    isFocused: Boolean,
    fillColor: Color,
    focusedBorderColor: Color,
    unfocusedBorderColor: Color,
    cornerRadius: Dp = 12.dp
): Modifier = this.drawWithCache {
    val radius = cornerRadius.toPx()
    val borderWidthPx = if (isFocused) 2.dp.toPx() else 1.dp.toPx()
    val borderColor = if (isFocused) focusedBorderColor else unfocusedBorderColor
    val stroke = Stroke(width = borderWidthPx)
    onDrawBehind {
        drawRoundRect(
            color = fillColor,
            cornerRadius = CornerRadius(radius, radius)
        )
        drawRoundRect(
            color = borderColor,
            cornerRadius = CornerRadius(radius, radius),
            style = stroke
        )
    }
}

@Composable
private fun searchSelectableButtonColors() = ButtonDefaults.colors(
    containerColor = NexioColors.BackgroundCard,
    contentColor = NexioColors.TextPrimary,
    focusedContainerColor = NexioColors.BackgroundCard,
    focusedContentColor = NexioColors.TextPrimary
)

@Composable
private fun searchSelectableButtonBorder() = ButtonDefaults.border(
    border = Border(
        border = BorderStroke(1.dp, NexioColors.Border),
        shape = SearchSelectableItemShape
    ),
    focusedBorder = Border(
        border = BorderStroke(2.dp, NexioColors.FocusRing),
        shape = SearchSelectableItemShape
    )
)

private fun searchSelectableButtonScale() = ButtonDefaults.scale(
    focusedScale = 1f,
    pressedScale = 1f
)
