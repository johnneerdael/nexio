package com.nexio.tv.ui.screens.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.ui.theme.NexioColors

/**
 * 3x4 D-pad-navigable PIN numpad.
 *
 * Layout (phone-style grid):
 *   Row 1: 1  2  3
 *   Row 2: 4  5  6
 *   Row 3: 7  8  9
 *   Row 4: C  0  OK
 *
 * All 12 cells are individually focusable via [FocusRequester] for D-pad navigation.
 * Hardware number keys (KEYCODE_0–9, KEYCODE_NUMPAD_0–9) are intercepted on the
 * outer Column so fire TV remotes with number keys work without D-pad selection.
 * DEL/CLEAR hardware keys call [onClear].
 *
 * @param enabled Whether cells accept input. Set to false during rate-limit lockout.
 * @param onDigit Called with the digit Char when a digit cell is activated.
 * @param onClear Called when the Clear cell is activated or DEL hardware key pressed.
 * @param onConfirm Called when the Confirm cell is activated.
 */
@Composable
internal fun ProfilePinNumpad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 12 focus requesters: indices 0-11 map to cells row-by-row
    // Row1: 0=1, 1=2, 2=3
    // Row2: 3=4, 4=5, 5=6
    // Row3: 6=7, 7=8, 8=9
    // Row4: 9=Clear, 10=0, 11=Confirm
    val focusRequesters = remember { List(12) { FocusRequester() } }

    // Give initial focus to the "1" cell after 2-frame settle
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { focusRequesters[0].requestFocus() }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                val digit = keyCodeToDigit(event.key.nativeKeyCode)
                when {
                    digit != null && enabled -> {
                        onDigit(digit)
                        true
                    }
                    event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_DEL ||
                    event.key.nativeKeyCode == android.view.KeyEvent.KEYCODE_CLEAR -> {
                        if (enabled) onClear()
                        true
                    }
                    else -> false
                }
            } else false
        }
    ) {
        // Row 1: 1, 2, 3
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumpadCell(label = "1", enabled = enabled, focusRequester = focusRequesters[0], onClick = { onDigit('1') })
            NumpadCell(label = "2", enabled = enabled, focusRequester = focusRequesters[1], onClick = { onDigit('2') })
            NumpadCell(label = "3", enabled = enabled, focusRequester = focusRequesters[2], onClick = { onDigit('3') })
        }

        // Row 2: 4, 5, 6
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumpadCell(label = "4", enabled = enabled, focusRequester = focusRequesters[3], onClick = { onDigit('4') })
            NumpadCell(label = "5", enabled = enabled, focusRequester = focusRequesters[4], onClick = { onDigit('5') })
            NumpadCell(label = "6", enabled = enabled, focusRequester = focusRequesters[5], onClick = { onDigit('6') })
        }

        // Row 3: 7, 8, 9
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumpadCell(label = "7", enabled = enabled, focusRequester = focusRequesters[6], onClick = { onDigit('7') })
            NumpadCell(label = "8", enabled = enabled, focusRequester = focusRequesters[7], onClick = { onDigit('8') })
            NumpadCell(label = "9", enabled = enabled, focusRequester = focusRequesters[8], onClick = { onDigit('9') })
        }

        // Row 4: Clear, 0, Confirm
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumpadCell(
                label = null,
                icon = { size ->
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Clear",
                        modifier = Modifier.size(size),
                        tint = if (enabled) NexioColors.TextPrimary else NexioColors.TextPrimary.copy(alpha = 0.4f)
                    )
                },
                enabled = enabled,
                focusRequester = focusRequesters[9],
                onClick = { onClear() }
            )

            NumpadCell(
                label = "0",
                enabled = enabled,
                focusRequester = focusRequesters[10],
                onClick = { onDigit('0') }
            )

            NumpadCell(
                label = null,
                icon = { size ->
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Confirm",
                        modifier = Modifier.size(size),
                        tint = if (enabled) NexioColors.TextPrimary else NexioColors.TextPrimary.copy(alpha = 0.4f)
                    )
                },
                enabled = enabled,
                focusRequester = focusRequesters[11],
                onClick = { onConfirm() }
            )
        }
    }
}

/**
 * Single numpad cell. Renders a 72x64dp rounded rectangle that is D-pad focusable.
 * The border transitions to [NexioColors.FocusRing] on focus using [animateColorAsState].
 */
@Composable
private fun NumpadCell(
    enabled: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    label: String? = null,
    icon: (@Composable (size: androidx.compose.ui.unit.Dp) -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = if (isFocused && enabled) NexioColors.FocusRing else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(150),
        label = "numpadCellBorder"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 72.dp, height = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NexioColors.BackgroundCard)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(enabled = enabled)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
    ) {
        when {
            icon != null -> icon(20.dp)
            label != null -> Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = if (enabled) NexioColors.TextPrimary else NexioColors.TextPrimary.copy(alpha = 0.4f)
            )
        }
    }
}

/**
 * Maps Android hardware key codes to digit Chars.
 * Handles both main keyboard number row and numpad keys.
 */
private fun keyCodeToDigit(keyCode: Int): Char? = when (keyCode) {
    android.view.KeyEvent.KEYCODE_0, android.view.KeyEvent.KEYCODE_NUMPAD_0 -> '0'
    android.view.KeyEvent.KEYCODE_1, android.view.KeyEvent.KEYCODE_NUMPAD_1 -> '1'
    android.view.KeyEvent.KEYCODE_2, android.view.KeyEvent.KEYCODE_NUMPAD_2 -> '2'
    android.view.KeyEvent.KEYCODE_3, android.view.KeyEvent.KEYCODE_NUMPAD_3 -> '3'
    android.view.KeyEvent.KEYCODE_4, android.view.KeyEvent.KEYCODE_NUMPAD_4 -> '4'
    android.view.KeyEvent.KEYCODE_5, android.view.KeyEvent.KEYCODE_NUMPAD_5 -> '5'
    android.view.KeyEvent.KEYCODE_6, android.view.KeyEvent.KEYCODE_NUMPAD_6 -> '6'
    android.view.KeyEvent.KEYCODE_7, android.view.KeyEvent.KEYCODE_NUMPAD_7 -> '7'
    android.view.KeyEvent.KEYCODE_8, android.view.KeyEvent.KEYCODE_NUMPAD_8 -> '8'
    android.view.KeyEvent.KEYCODE_9, android.view.KeyEvent.KEYCODE_NUMPAD_9 -> '9'
    else -> null
}
