package com.nexio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterReasoningModelsTest {
    @Test
    fun parseSlugsExtractsNormalizedIds() {
        val slugs = OpenRouterReasoningModels.parseSlugs(
            """["OpenAI/GPT-5","anthropic/claude-opus-4-5:nitro","  ",null]"""
        )

        assertTrue("openai/gpt-5" in slugs)
        assertTrue("anthropic/claude-opus-4-5" in slugs)
    }

    @Test
    fun isReasoningModelMatchesKnownIds() {
        val models = OpenRouterReasoningModels(
            slugsProvider = { setOf("openai/gpt-5", "anthropic/claude-opus-4-5") }
        )

        assertTrue(models.isReasoningModel("openai/gpt-5"))
        assertTrue(models.isReasoningModel("OpenAI/GPT-5"))
        assertTrue(models.isReasoningModel("openai/gpt-5:free"))
        assertTrue(models.isReasoningModel("openai/gpt-5:nitro"))
    }

    @Test
    fun isReasoningModelRejectsUnknownOrBlankIds() {
        val models = OpenRouterReasoningModels(
            slugsProvider = { setOf("openai/gpt-5") }
        )

        assertFalse(models.isReasoningModel("openai/gpt-4o"))
        assertFalse(models.isReasoningModel(""))
        assertFalse(models.isReasoningModel(null))
    }

    @Test
    fun bundledAssetParsesAndMatchesCommonReasoningModels() {
        val json = javaClass.classLoader!!
            .getResourceAsStream("openrouter_reasoning_models.json")
            ?.bufferedReader()?.use { it.readText() }
            ?: this::class.java.classLoader!!
                .getResource("openrouter_reasoning_models.json")
                ?.readText()
        if (json != null) {
            val slugs = OpenRouterReasoningModels.parseSlugs(json)
            assertTrue("expected at least a handful of reasoning slugs, got ${slugs.size}", slugs.size > 10)
        }
    }
}
