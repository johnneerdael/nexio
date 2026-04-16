package com.nexio.tv.ui.screens.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.domain.model.UserProfile
import com.nexio.tv.ui.theme.NexioColors
import kotlinx.coroutines.delay

/**
 * Full-screen PIN entry overlay shown when selecting a PIN-locked profile.
 *
 * Manages local PIN digit accumulation ([pin] state) and delegates verification to
 * the caller via [onPinSubmit]. Shake animation is driven locally by [isError] changes.
 * Rate-limit countdown display is driven by [retryAfterSeconds] from the ViewModel.
 *
 * @param profile The PIN-locked profile being unlocked.
 * @param onPinSubmit Called with the 4-digit PIN string when auto-submit fires or Confirm pressed.
 * @param onDismiss Called when the user presses Back to cancel PIN entry.
 * @param isVerifying True while network verification is in progress — disables numpad.
 * @param isError True when the last PIN attempt was rejected (triggers shake + "Wrong PIN" text).
 * @param errorMessage Optional override message for rejected PINs.
 * @param retryAfterSeconds Remaining rate-limit seconds from ViewModel. >0 disables numpad and shows countdown.
 * @param onErrorConsumed Called after the shake animation completes to clear the error flag.
 */
@Composable
internal fun ProfilePinOverlay(
    profile: UserProfile,
    onPinSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    isVerifying: Boolean,
    isError: Boolean,
    errorMessage: String?,
    retryAfterSeconds: Int,
    onErrorConsumed: () -> Unit
) {
    // Back key dismisses the overlay
    BackHandler(enabled = true) { onDismiss() }

    // Local PIN digit accumulation (up to 4 chars)
    var pin by remember { mutableStateOf("") }

    // Shake animation for wrong PIN
    val shakeOffset = remember { Animatable(0f) }

    // Auto-submit when 4th digit is entered
    LaunchedEffect(pin, isVerifying) {
        if (pin.length == 4 && !isVerifying) {
            onPinSubmit(pin)
        }
    }

    // Shake animation + pin reset on error
    LaunchedEffect(isError) {
        if (isError) {
            shakeOffset.snapTo(0f)
            listOf(-22f, 18f, -14f, 10f, -6f, 0f).forEach { offset ->
                shakeOffset.animateTo(offset, animationSpec = tween(42))
            }
            delay(600)
            pin = ""
            onErrorConsumed()
        }
    }

    val numpadEnabled = retryAfterSeconds <= 0 && !isVerifying

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexioColors.Background.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = "Enter PIN",
                style = MaterialTheme.typography.headlineLarge,
                color = NexioColors.TextPrimary
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyMedium,
                color = NexioColors.TextSecondary
            )

            Spacer(Modifier.height(32.dp))

            ProfilePinBoxes(
                enteredLength = pin.length,
                isError = isError,
                isDisabled = retryAfterSeconds > 0,
                shakeOffset = shakeOffset.value
            )

            // Reserve fixed height for error/rate-limit text to prevent layout shift
            Spacer(Modifier.height(16.dp))

            when {
                retryAfterSeconds > 0 -> Text(
                    text = "Try again in ${retryAfterSeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexioColors.TextSecondary
                )
                isError -> Text(
                    text = errorMessage ?: "Wrong PIN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexioColors.Error
                )
                else -> Spacer(Modifier.height(20.dp))
            }

            Spacer(Modifier.height(24.dp))

            ProfilePinNumpad(
                enabled = numpadEnabled,
                onDigit = { digit ->
                    if (pin.length < 4) pin += digit
                },
                onClear = {
                    if (pin.isNotEmpty()) pin = pin.dropLast(1)
                },
                onConfirm = {
                    if (pin.length == 4) onPinSubmit(pin)
                }
            )
        }
    }
}
