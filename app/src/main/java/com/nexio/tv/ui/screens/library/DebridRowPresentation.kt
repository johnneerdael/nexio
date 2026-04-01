package com.nexio.tv.ui.screens.library

import com.nexio.tv.domain.model.LibraryEntry

internal data class DebridRowPresentation(
    val title: String
)

internal fun debridRowPresentation(item: LibraryEntry): DebridRowPresentation {
    val title = item.playbackFilename
        ?.takeIf { it.isNotBlank() }
        ?: item.playbackStreamName?.takeIf { it.isNotBlank() }
        ?: item.name
    return DebridRowPresentation(title = title)
}
