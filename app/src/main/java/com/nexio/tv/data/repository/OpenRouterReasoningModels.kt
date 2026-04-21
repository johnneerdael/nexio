package com.nexio.tv.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

private const val OPENROUTER_REASONING_MODELS_ASSET = "openrouter_reasoning_models.json"

@Singleton
class OpenRouterReasoningModels(
    private val slugsProvider: () -> Set<String>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(assetLoader(context))

    private val slugs: Set<String> by lazy { slugsProvider() }

    fun isReasoningModel(modelId: String?): Boolean {
        val normalized = normalize(modelId) ?: return false
        return normalized in slugs
    }

    companion object {
        internal fun parseSlugs(json: String): Set<String> {
            val array = JSONArray(json)
            val out = HashSet<String>(array.length())
            for (index in 0 until array.length()) {
                normalize(array.optString(index))?.let(out::add)
            }
            return out
        }

        private fun assetLoader(context: Context): () -> Set<String> = {
            runCatching {
                context.assets.open(OPENROUTER_REASONING_MODELS_ASSET).bufferedReader().use { reader ->
                    parseSlugs(reader.readText())
                }
            }.getOrDefault(emptySet())
        }

        private fun normalize(value: String?): String? {
            if (value.isNullOrBlank()) return null
            return value.trim().substringBefore(':').lowercase().takeIf { it.isNotEmpty() }
        }
    }
}
