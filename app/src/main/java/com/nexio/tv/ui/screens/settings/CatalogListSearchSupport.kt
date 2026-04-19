package com.nexio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.data.repository.MDBListListOption
import com.nexio.tv.data.repository.TraktListSource
import com.nexio.tv.data.repository.TraktPopularListOption
import com.nexio.tv.ui.theme.NexioColors

internal fun filterTraktPopularLists(
    lists: List<TraktPopularListOption>,
    query: String
): List<TraktPopularListOption> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return lists
    return lists.filter { option ->
        option.title.lowercase().contains(normalizedQuery) ||
            option.userId.lowercase().contains(normalizedQuery) ||
            option.listId.lowercase().contains(normalizedQuery) ||
            option.key.lowercase().contains(normalizedQuery)
    }
}

internal fun filterMDBListTopLists(
    lists: List<MDBListListOption>,
    query: String
): List<MDBListListOption> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return lists
    return lists.filter { option ->
        option.title.lowercase().contains(normalizedQuery) ||
            option.owner.lowercase().contains(normalizedQuery) ||
            option.listId.lowercase().contains(normalizedQuery)
    }
}

@Composable
internal fun traktListSourceLabel(source: TraktListSource): String {
    return when (source) {
        TraktListSource.PERSONAL -> stringResource(R.string.trakt_list_source_personal)
        TraktListSource.POPULAR,
        TraktListSource.SEARCH -> stringResource(R.string.trakt_list_source_community)
    }
}

@Composable
internal fun SettingsCatalogSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholderTextRes: Int,
    keyboardController: SoftwareKeyboardController?,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { keyEvent ->
                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                            keyboardController?.show()
                        }
                    }
                }
                false
            },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = NexioColors.TextPrimary),
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        placeholder = {
            Text(
                text = stringResource(placeholderTextRes),
                style = MaterialTheme.typography.bodyMedium,
                color = NexioColors.TextTertiary
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = NexioColors.BackgroundElevated,
            unfocusedContainerColor = NexioColors.BackgroundElevated,
            focusedIndicatorColor = NexioColors.FocusRing,
            unfocusedIndicatorColor = NexioColors.Border,
            focusedTextColor = NexioColors.TextPrimary,
            unfocusedTextColor = NexioColors.TextPrimary,
            cursorColor = NexioColors.FocusRing,
            focusedPlaceholderColor = NexioColors.TextTertiary,
            unfocusedPlaceholderColor = NexioColors.TextTertiary,
            focusedLabelColor = Color.Unspecified,
            unfocusedLabelColor = Color.Unspecified
        )
    )
}
