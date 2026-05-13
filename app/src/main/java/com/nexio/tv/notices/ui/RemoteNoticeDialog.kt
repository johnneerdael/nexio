package com.nexio.tv.notices.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.nexio.tv.R
import com.nexio.tv.notices.model.RemoteNoticeDisplay
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.theme.NexioColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RemoteNoticeDialog(
    notice: RemoteNoticeDisplay,
    onDismiss: () -> Unit
) {
    val closeFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    NexioDialog(
        onDismiss = onDismiss,
        title = notice.title,
        width = 760.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

                    val target = when (native.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (scrollState.value >= scrollState.maxValue) return@onPreviewKeyEvent false
                            (scrollState.value + NOTICE_SCROLL_STEP_PX).coerceAtMost(scrollState.maxValue)
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            if (scrollState.value <= 0) return@onPreviewKeyEvent false
                            (scrollState.value - NOTICE_SCROLL_STEP_PX).coerceAtLeast(0)
                        }
                        else -> return@onPreviewKeyEvent false
                    }

                    coroutineScope.launch {
                        scrollState.animateScrollTo(target)
                    }
                    true
                },
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(scrollState)
                    .padding(bottom = 4.dp)
            ) {
                Markdown(
                    content = notice.markdown,
                    modifier = Modifier.fillMaxWidth(),
                    colors = markdownColor(text = NexioColors.TextSecondary),
                    typography = markdownTypography(
                        paragraph = MaterialTheme.typography.bodyMedium,
                        h1 = MaterialTheme.typography.titleLarge,
                        h2 = MaterialTheme.typography.titleMedium,
                        h3 = MaterialTheme.typography.titleSmall
                    )
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(closeFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NexioColors.Background,
                        contentColor = NexioColors.TextPrimary,
                        focusedContainerColor = NexioColors.FocusBackground,
                        focusedContentColor = NexioColors.Primary
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
                ) {
                    Text(stringResource(R.string.notice_close))
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        var focused = false
        repeat(5) {
            if (!focused) {
                withFrameNanos { }
                focused = runCatching {
                    closeFocusRequester.requestFocus()
                }.isSuccess
            }
        }
    }
}

private const val NOTICE_SCROLL_STEP_PX = 240
