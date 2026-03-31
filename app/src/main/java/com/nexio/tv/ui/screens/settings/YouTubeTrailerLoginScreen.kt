@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.data.trailer.YOUTUBE_STABLE_WEB_USER_AGENT
import com.nexio.tv.ui.theme.NexioColors

@Composable
fun YouTubeTrailerLoginScreen(
    viewModel: YouTubeTrailerLoginViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.youtube_trailer_login_title),
        subtitle = stringResource(R.string.youtube_trailer_login_subtitle)
    ) {
        YouTubeTrailerLoginContent(viewModel = viewModel)
    }
}

@Composable
fun YouTubeTrailerLoginContent(
    viewModel: YouTubeTrailerLoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "youtube_trailer_auth_header") {
            SettingsDetailHeader(
                title = stringResource(R.string.youtube_trailer_login_title),
                subtitle = stringResource(R.string.youtube_trailer_login_subtitle)
            )
        }

        item(key = "youtube_trailer_auth_status") {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsActionRow(
                    title = stringResource(R.string.youtube_trailer_login_status_title),
                    subtitle = uiState.sessionStatusMessage,
                    value = if (uiState.isSignedIn) {
                        stringResource(R.string.youtube_trailer_login_status_signed_in)
                    } else {
                        stringResource(R.string.youtube_trailer_login_status_signed_out)
                    },
                    enabled = false,
                    onClick = {}
                )
                SettingsActionRow(
                    title = stringResource(R.string.youtube_trailer_login_last_refresh_title),
                    subtitle = uiState.lastCookieRefreshEpochMs?.toString()
                        ?: stringResource(R.string.youtube_trailer_login_not_available),
                    value = if (uiState.sessionVersion > 0) {
                        stringResource(
                            R.string.youtube_trailer_login_session_version,
                            uiState.sessionVersion
                        )
                    } else {
                        stringResource(R.string.youtube_trailer_login_not_available)
                    },
                    enabled = false,
                    onClick = {}
                )
            }
        }

        item(key = "youtube_trailer_auth_actions") {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsActionRow(
                    title = stringResource(R.string.youtube_trailer_login_sign_in_title),
                    subtitle = stringResource(R.string.youtube_trailer_login_sign_in_subtitle),
                    onClick = { viewModel.onEvent(YouTubeTrailerAuthEvent.SignIn) }
                )
                SettingsActionRow(
                    title = stringResource(R.string.youtube_trailer_login_refresh_title),
                    subtitle = stringResource(R.string.youtube_trailer_login_refresh_subtitle),
                    enabled = uiState.isSignedIn,
                    onClick = { viewModel.onEvent(YouTubeTrailerAuthEvent.RefreshSession) }
                )
                SettingsActionRow(
                    title = stringResource(R.string.youtube_trailer_login_sign_out_title),
                    subtitle = stringResource(R.string.youtube_trailer_login_sign_out_subtitle),
                    enabled = uiState.isSignedIn,
                    onClick = { viewModel.onEvent(YouTubeTrailerAuthEvent.SignOut) }
                )
            }
        }

        if (uiState.showEmbeddedLoginSurface) {
            item(key = "youtube_trailer_auth_placeholder") {
                EmbeddedYouTubeLoginSurface(
                    onPageFinished = viewModel::onAuthPageFinished,
                    onClose = {
                        viewModel.onEvent(YouTubeTrailerAuthEvent.DismissEmbeddedLoginSurface)
                    }
                )
            }
        }
    }
}

@Composable
private fun EmbeddedYouTubeLoginSurface(
    onPageFinished: (String?) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val loginUrl = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F"

    SettingsGroupCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = stringResource(R.string.youtube_trailer_login_placeholder_title))
        Text(text = stringResource(R.string.youtube_trailer_login_placeholder_subtitle))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(SettingsSecondaryCardRadius))
                .background(NexioColors.BackgroundCard)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = YOUTUBE_STABLE_WEB_USER_AGENT
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                onPageFinished(url)
                            }
                        }
                        loadUrl(loginUrl)
                    }
                },
                update = { webView ->
                    if (webView.url.isNullOrBlank()) {
                        webView.loadUrl(loginUrl)
                    }
                }
            )
        }
        SettingsActionRow(
            title = stringResource(R.string.action_close),
            subtitle = stringResource(
                R.string.youtube_trailer_login_placeholder_close_subtitle
            ),
            onClick = onClose
        )
    }
}
