package com.nuvio.tv.ui.screens.search

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (catalogId: String, addonId: String, type: String) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    val discoverFirstItemFocusRequester = remember { FocusRequester() }
    var focusResults by remember { mutableStateOf(false) }
    var discoverFocusedItemIndex by rememberSaveable { mutableStateOf(0) }
    var restoreDiscoverFocus by rememberSaveable { mutableStateOf(false) }
    var pendingDiscoverRestoreOnResume by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
    val posterCardStyle = PosterCardStyle(
        width = uiState.posterCardWidthDp.dp,
        height = computedHeightDp.dp,
        cornerRadius = uiState.posterCardCornerRadiusDp.dp,
        focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
        focusedScale = PosterCardDefaults.Style.focusedScale
    )

    LaunchedEffect(uiState.query) {
        focusResults = false
    }

    val isDiscoverMode = uiState.discoverEnabled && uiState.query.trim().isEmpty()
    val hasPendingUnsubmittedQuery = !isDiscoverMode &&
        uiState.query.trim().length >= 2 &&
        uiState.query.trim() != uiState.submittedQuery.trim()
    val canMoveToResults = if (isDiscoverMode) {
        uiState.discoverResults.isNotEmpty()
    } else {
        uiState.submittedQuery.trim().length >= 2 && uiState.catalogRows.any { it.items.isNotEmpty() }
    }

    LaunchedEffect(focusResults, isDiscoverMode, uiState.discoverResults.size) {
        if (focusResults && isDiscoverMode && uiState.discoverResults.isNotEmpty()) {
            kotlinx.coroutines.delay(100)
            try {
                discoverFirstItemFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
            focusResults = false
        }
    }

    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { searchFocusRequester.requestFocus() }
    }

    DisposableEffect(lifecycleOwner, pendingDiscoverRestoreOnResume) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (pendingDiscoverRestoreOnResume) {
                    restoreDiscoverFocus = true
                    pendingDiscoverRestoreOnResume = false
                } else {
                    coroutineScope.launch {
                        repeat(2) { withFrameNanos { } }
                        runCatching { searchFocusRequester.requestFocus() }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Search",
                        style = androidx.tv.material3.MaterialTheme.typography.headlineMedium,
                        color = if (showBuiltInHeader) NuvioColors.TextPrimary else NuvioColors.TextPrimary.copy(alpha = 0f),
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = if (isDiscoverMode) "DISCOVER" else "RESULTS",
                        style = androidx.tv.material3.MaterialTheme.typography.labelLarge,
                        color = if (showBuiltInHeader) NuvioColors.TextTertiary else NuvioColors.TextTertiary.copy(alpha = 0f),
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 2.sp
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = { viewModel.onEvent(SearchEvent.QueryChanged(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp)
                        .focusRequester(searchFocusRequester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                                    KeyEvent.KEYCODE_DPAD_CENTER -> {
                                        viewModel.onEvent(SearchEvent.SubmitSearch)
                                        return@onPreviewKeyEvent true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN,
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        if (canMoveToResults) {
                                            focusResults = true
                                            return@onPreviewKeyEvent true
                                        }
                                    }
                                }
                            }
                            false
                        },
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            viewModel.onEvent(SearchEvent.SubmitSearch)
                            keyboardController?.hide()
                        }
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = {
                        Text(
                            text = "Search movies & series",
                            color = NuvioColors.TextTertiary
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = NuvioColors.BackgroundCard,
                        unfocusedContainerColor = NuvioColors.BackgroundCard,
                        focusedIndicatorColor = NuvioColors.FocusRing,
                        unfocusedIndicatorColor = NuvioColors.Border,
                        focusedTextColor = NuvioColors.TextPrimary,
                        unfocusedTextColor = NuvioColors.TextPrimary,
                        cursorColor = NuvioColors.FocusRing
                    )
                )
            }

            if (!isDiscoverMode && (uiState.submittedQuery.trim().length < 2 || hasPendingUnsubmittedQuery)) {
                item {
                    Text(
                        text = "Press Done on the keyboard to search",
                        style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 52.dp)
                    )
                }
            }

            if (isDiscoverMode) {
                item {
                    DiscoverSection(
                        uiState = uiState,
                        posterCardStyle = posterCardStyle,
                        focusResults = focusResults,
                        firstItemFocusRequester = discoverFirstItemFocusRequester,
                        focusedItemIndex = discoverFocusedItemIndex,
                        shouldRestoreFocusedItem = restoreDiscoverFocus,
                        onRestoreFocusedItemHandled = { restoreDiscoverFocus = false },
                        onNavigateToDetail = { id, type, addonBaseUrl ->
                            pendingDiscoverRestoreOnResume = true
                            onNavigateToDetail(id, type, addonBaseUrl)
                        },
                        onDiscoverItemFocused = { index ->
                            discoverFocusedItemIndex = index
                        },
                        onRequestRestoreFocus = { index ->
                            discoverFocusedItemIndex = index
                            restoreDiscoverFocus = true
                        },
                        onSelectType = { viewModel.onEvent(SearchEvent.SelectDiscoverType(it)) },
                        onSelectCatalog = { viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(it)) },
                        onSelectGenre = { viewModel.onEvent(SearchEvent.SelectDiscoverGenre(it)) },
                        onShowMore = { viewModel.onEvent(SearchEvent.ShowMoreDiscoverResults) },
                        onLoadMore = { viewModel.onEvent(SearchEvent.LoadMoreDiscoverResults) }
                    )
                }
            } else {
                when {
                    uiState.submittedQuery.trim().length < 2 && !hasPendingUnsubmittedQuery -> {
                        item {
                            EmptyScreenState(
                                title = "Start Searching",
                                subtitle = if (uiState.discoverEnabled) {
                                    "Enter at least 2 characters"
                                } else {
                                    "Discover is disabled. Enter at least 2 characters"
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

                    uiState.catalogRows.isEmpty() || uiState.catalogRows.none { it.items.isNotEmpty() } -> {
                        item {
                            EmptyScreenState(
                                title = "No Results",
                                subtitle = "Try searching with different keywords",
                                icon = Icons.Default.Search
                            )
                        }
                    }

                    else -> {
                        val visibleCatalogRows = uiState.catalogRows.filter { it.items.isNotEmpty() }

                        itemsIndexed(
                            items = visibleCatalogRows,
                            key = { index, item ->
                                "${item.addonId}_${item.type}_${item.catalogId}_${uiState.submittedQuery.trim()}_$index"
                            }
                        ) { index, catalogRow ->
                            CatalogRowSection(
                                catalogRow = catalogRow,
                                showPosterLabels = uiState.posterLabelsEnabled,
                                showAddonName = uiState.catalogAddonNameEnabled,
                                enableRowFocusRestorer = false,
                                focusedItemIndex = if (focusResults && index == 0) 0 else -1,
                                onItemFocused = {
                                    if (focusResults) {
                                        focusResults = false
                                    }
                                },
                                upFocusRequester = if (index == 0) searchFocusRequester else null,
                                onItemClick = { id, type, addonBaseUrl ->
                                    onNavigateToDetail(id, type, addonBaseUrl)
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
}
