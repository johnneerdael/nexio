package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text as CoreText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayMessage = buildErrorStatePresentation(message).annotated

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CoreText(
            text = displayMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary,
                focusedContainerColor = NuvioColors.FocusBackground,
                focusedContentColor = NuvioColors.Primary
            ),
            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp))
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

internal fun formatErrorStateMessage(message: String): String {
    return buildErrorStatePresentation(message).text
}

internal fun buildErrorStateAnnotatedMessage(message: String): AnnotatedString {
    return buildErrorStatePresentation(message).annotated
}

private data class ErrorStatePresentation(
    val text: String,
    val boldRanges: List<IntRange>
) {
    val annotated: AnnotatedString
        get() = buildAnnotatedString {
            append(text)
            boldRanges.forEach { range ->
                addStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = NuvioColors.TextPrimary
                    ),
                    start = range.first,
                    end = range.last + 1
                )
            }
        }
}

private fun buildErrorStatePresentation(message: String): ErrorStatePresentation {
    val trimmed = message.trim()
    if (trimmed.isBlank()) {
        return ErrorStatePresentation(
            text = "Something went wrong.\n\nPossible fix: retry in a moment.",
            boldRanges = emptyList()
        )
    }

    val addonName = trimmed.substringBefore(':', missingDelimiterValue = "").trim()
        .takeIf { it.isNotBlank() && it.length < trimmed.length }
    val reason = trimmed.substringAfter(':', missingDelimiterValue = trimmed).trim()

    fun messageWithFix(issue: String, fix: String): String {
        return "$issue\n\nPossible fix: $fix"
    }

    val displayMessage = when {
        trimmed.equals("Meta not found in any addon", ignoreCase = true) ||
            trimmed.equals("Meta not found", ignoreCase = true) ||
            trimmed.equals("No installed addon could provide metadata for this title.", ignoreCase = true) -> {
            messageWithFix(
                issue = "This id could not load details because none of the installed addons returned metadata.",
                fix = "try another addon, disable \"Prefer external meta addon\" in Layout settings, or confirm an installed addon supports this id."
            )
        }

        trimmed.startsWith("No installed addon supports metadata for ", ignoreCase = true) -> {
            messageWithFix(
                issue = trimmed,
                fix = "install or update an addon that supports this content type, then retry."
            )
        }

        trimmed.startsWith("Tried meta addons:", ignoreCase = true) -> {
            messageWithFix(
                issue = trimmed,
                fix = "install an addon that supports this id, or reconfigure/update the addon and retry."
            )
        }

        reason.equals("returned no metadata for this id", ignoreCase = true) ||
            reason.equals("returned no metadata for this title", ignoreCase = true) ||
            reason.equals("metadata was not found", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, "does not have metadata for this id"),
                fix = "open this id from a different addon, disable \"Prefer external meta addon\", or check that the addon supports this id."
            )
        }

        reason.contains("could not reach the addon server", ignoreCase = true) ||
            trimmed.contains("Unable to resolve host", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, "could not be reached"),
                fix = "check your internet connection, verify the addon URL still works, then retry."
            )
        }

        reason.contains("connection to the addon failed", ignoreCase = true) ||
            trimmed.contains("Failed to connect", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, "rejected the connection"),
                fix = "make sure the addon server is online and reachable, then retry."
            )
        }

        reason.contains("timed out", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, "took too long to respond"),
                fix = "retry in a moment, or try a different addon if this keeps happening."
            )
        }

        reason.contains("insecure HTTP connection blocked by Android", ignoreCase = true) ||
            trimmed.contains("CLEARTEXT communication", ignoreCase = true) -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, "uses an insecure HTTP connection that Android blocked"),
                fix = "switch the addon URL to HTTPS or update the addon configuration."
            )
        }

        addonName != null -> {
            messageWithFix(
                issue = buildAddonIssue(addonName, reason),
                fix = "retry, update or reinstall the addon, or try a different addon."
            )
        }

        else -> trimmed
    }

    val boldRanges = linkedSetOf<IntRange>()

    val aggregatePrefix = "Tried meta addons: "
    if (displayMessage.startsWith(aggregatePrefix)) {
        val listStart = aggregatePrefix.length
        displayMessage.indexOf('.', startIndex = listStart)
            .takeIf { it > listStart }
            ?.let { boldRanges += listStart until it }
    }

    displayMessage.indexOf(':')
        .takeIf {
            it > 0 &&
                !displayMessage.startsWith("Tried meta addons:", ignoreCase = true) &&
                !displayMessage.startsWith("No installed addon", ignoreCase = true)
        }
        ?.let { boldRanges += 0 until it }

    Regex("""id=[^\s.,)]+""").findAll(displayMessage).forEach { match ->
        boldRanges += match.range.first..match.range.last
    }

    return ErrorStatePresentation(
        text = displayMessage,
        boldRanges = boldRanges.toList()
    )
}

private fun buildAddonIssue(addonName: String?, issue: String): String {
    val normalizedIssue = issue.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }
    return if (addonName.isNullOrBlank()) {
        normalizedIssue
    } else {
        "$addonName: $normalizedIssue."
    }
}
