package com.nexio.tv.core.metadata.router

object MetadataParentIdNormalizer {
    fun parentIdOf(contentId: String): String {
        val id = contentId.trim()
        if (id.isBlank()) return ""

        val parts = id.split(":")
        return when {
            isProviderObjectId(id, parts) -> id
            id.startsWith("imdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("kitsu:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("mal:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("anilist:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("anidb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tmdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tvdb:", ignoreCase = true) && parts.size >= 2 -> "${parts[0]}:${parts[1]}"
            id.startsWith("tt", ignoreCase = true) && parts.size >= 3 -> parts[0]
            else -> id
        }
    }

    private fun isProviderObjectId(id: String, parts: List<String>): Boolean {
        if (parts.size < 3) return false
        val provider = parts[0]
        val objectType = parts[1]
        val isSupportedProvider = provider.equals("tmdb", ignoreCase = true) ||
            provider.equals("tvdb", ignoreCase = true)
        val isSupportedObject = objectType.equals("person", ignoreCase = true) ||
            objectType.equals("company", ignoreCase = true) ||
            objectType.equals("network", ignoreCase = true) ||
            objectType.equals("org", ignoreCase = true)
        return isSupportedProvider && isSupportedObject && id.startsWith("$provider:", ignoreCase = true)
    }
}
