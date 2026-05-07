# Vendor Agnostic Subtitle Translation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Gemini-only auto-translate integration with a vendor-neutral subtitle translation provider configuration that supports OpenAI-compatible, Anthropic-compatible, and legacy Gemini endpoints across the Android app, Supabase sync, and `nexio-web`.

**Architecture:** Introduce a neutral `subtitleTranslation` settings model with `enabled`, `provider`, `model`, and `baseUrl`, while keeping the API key in the existing account-secret channel. Refactor the current `GeminiSubtitleTranslationService` into a provider-aware subtitle translation service that keeps the existing chunking, caching, SRT/VTT parsing, and player overlay behavior, and selects the HTTP wire format from the configured provider. Sync reads legacy `integrations.gemini` and `gemini_api_key` for migration, but writes the new `integrations.subtitleTranslation` shape and generic translation secret going forward.

**Tech Stack:** Android Kotlin, Jetpack DataStore, OkHttp, kotlinx.serialization, org.json, Hilt, Media3 subtitle translation hooks, Supabase SQL RPCs, Nuxt/Vue/TypeScript `nexio-web`, node:test, Gradle unit tests.

---

## Revision Notes From Architecture Review

This plan has been revised to close the review findings:

- Contract v6 must be SQL-deployed before v6 app/web clients. Pull responses for contract v5 and below strip `subtitleTranslation` and map it back to `gemini.enabled`; v5 push payloads must merge into existing v6 settings instead of overwriting provider/model/base URL.
- Provider responses are parsed through one sanitized JSON path. OpenAI and Anthropic response text is extracted, markdown fences are stripped, and then the existing translation JSON parser is used.
- Disk cache files include provider, model, and base URL in the hash so switching providers does not serve stale file translations.
- Endpoint normalization handles full OpenAI/Anthropic paths, bare native host URLs without `/v1`, and Gemini full `/models/...:generateContent` URLs.
- Supabase includes a rollback migration and a secret cleanup lifecycle note for the legacy `gemini_api_key` secret.
- HTTP provider errors distinguish auth failures and rate limits from generic transient failures.

Implementation trade-off: request bodies continue using `org.json` because the existing service already uses it for dynamic request/response shapes, this avoids adding serialization-only DTOs for provider-specific HTTP payloads, and the provider request builder tests cover the hand-built JSON trees.

## External API Facts Checked

- OpenAI native default endpoint for the compatible path should be `https://api.openai.com/v1`, with the app appending `/chat/completions` for maximum OpenAI-compatible provider support. The OpenAI Responses API exists at `/v1/responses`, but OpenRouter documents OpenAI SDK compatibility through the Chat Completions shape, so this plan uses Chat Completions for the OpenAI-compatible provider path.
- Product-requested OpenAI default model: `gpt-5-nano`. OpenAI's current model page also lists newer `gpt-5.4-nano`; do not hard-code validation against a closed model enum because compatible endpoints such as OpenRouter require arbitrary model IDs.
- Anthropic native default endpoint should be `https://api.anthropic.com/v1`, with the app appending `/messages`. Anthropic docs list `claude-haiku-4-5` as an alias for `claude-haiku-4-5-20251001`, so use the requested alias default.
- OpenRouter's quickstart uses `base_url="https://openrouter.ai/api/v1"` and an OpenAI-compatible Chat Completions call. Users must be able to set that URL and any OpenRouter model ID.

References:

- OpenAI API models: https://developers.openai.com/api/docs/models
- OpenAI Responses reference: https://platform.openai.com/docs/api-reference/responses/create
- OpenAI Chat Completions reference: https://platform.openai.com/docs/api-reference/chat/create
- Anthropic models overview: https://platform.claude.com/docs/en/about-claude/models/overview
- Anthropic API overview: https://docs.anthropic.com/en/api/overview
- OpenRouter quickstart: https://openrouter.ai/docs/quickstart

## File Map

- `app/src/main/java/com/nexio/tv/domain/model/GeminiSettings.kt`: replace with neutral subtitle translation settings types, keeping a compatibility typealias only if existing call sites need a staged compile.
- `app/src/main/java/com/nexio/tv/data/local/GeminiSettingsDataStore.kt`: rename to `SubtitleTranslationSettingsDataStore.kt`; keep the DataStore file name and old API key preference key so installed users keep their local key.
- `app/src/main/java/com/nexio/tv/data/repository/GeminiSubtitleTranslationService.kt`: rename to `SubtitleTranslationService.kt`; preserve document parsing/chunking, split request rendering into provider-specific request builders, and make cache keys include provider/model/base URL.
- `app/src/main/java/com/nexio/tv/ui/screens/player/GeminiBuiltInSubtitleCueTranslator.kt`: rename to `BuiltInSubtitleCueTranslator.kt` and pass a complete `SubtitleTranslationSettings` instead of only an API key.
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController*.kt`: rename `gemini*` state to `subtitleTranslation*`, observe the new data store, and pass settings through addon and built-in translation flows.
- `app/src/main/java/com/nexio/tv/ui/screens/settings/GeminiSettingsScreen.kt` and `GeminiSettingsViewModel.kt`: rename to `SubtitleTranslationSettingsScreen.kt` and `SubtitleTranslationSettingsViewModel.kt`; add provider, model, endpoint, and API key controls.
- `app/src/main/res/values/strings.xml` plus localized string files: replace Gemini-specific visible text with provider-neutral copy and add labels for provider/model/endpoint.
- `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`: add `SubtitleTranslationSyncSettings` and include it under `IntegrationSettings`.
- `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`: bump the app/web contract constant, include subtitle translation fields in payload build/apply, and keep legacy Gemini read behavior.
- `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`: sync the generic translation secret, resolve new secret first and legacy Gemini secret second.
- `supabase/account_settings_sync.sql` plus forward and rollback migrations under `supabase/migrations/`: add `subtitleTranslation` defaults, extraction, snapshot behavior, contract support, and `translation_api_key` to the account secret check.
- `nexio-web/types/portal.ts`, `nexio-web/utils/portal-defaults.ts`, `nexio-web/utils/portal-settings.ts`, `nexio-web/utils/portal-metadata.ts`, `nexio-web/utils/integration-secret-bindings.ts`, `nexio-web/utils/integration-delete.ts`, `nexio-web/utils/account-secrets.ts`: mirror the new portal settings and secret binding.
- `nexio-web/components/portal/SettingsWorkspace.vue`: rename the Gemini integration card/modal to Subtitle Translation and add provider/model/endpoint controls.
- `docs/settings/settings-sync.schema.json`, `docs-site/playback/subtitles-and-auto-translate.md`, `docs-site/web/admin-workspaces/integrations.md`, `docs-site/troubleshooting/index.md`, `docs-site/features/index.md`, `docs-site/android/screens/player.md`, `docs-site/android/screens/settings.md`: update user-facing docs.

## Provider Contract

Use this neutral model everywhere:

```kotlin
enum class SubtitleTranslationProvider {
    OPENAI,
    ANTHROPIC,
    GEMINI
}

data class SubtitleTranslationSettings(
    val enabled: Boolean = false,
    val provider: SubtitleTranslationProvider = SubtitleTranslationProvider.OPENAI,
    val apiKey: String = "",
    val model: String = SubtitleTranslationDefaults.OPENAI_MODEL,
    val baseUrl: String = SubtitleTranslationDefaults.OPENAI_BASE_URL
)

object SubtitleTranslationDefaults {
    const val OPENAI_MODEL = "gpt-5-nano"
    const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    const val ANTHROPIC_MODEL = "claude-haiku-4-5"
    const val ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
    const val GEMINI_MODEL = "gemini-2.5-flash"
    const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
}
```

Endpoint normalization rule:

```kotlin
fun providerEndpoint(settings: SubtitleTranslationSettings): String {
    val rawRoot = settings.baseUrl.trim().trimEnd('/').ifBlank {
        when (settings.provider) {
            SubtitleTranslationProvider.OPENAI -> SubtitleTranslationDefaults.OPENAI_BASE_URL
            SubtitleTranslationProvider.ANTHROPIC -> SubtitleTranslationDefaults.ANTHROPIC_BASE_URL
            SubtitleTranslationProvider.GEMINI -> SubtitleTranslationDefaults.GEMINI_BASE_URL
        }
    }
    return when (settings.provider) {
        SubtitleTranslationProvider.OPENAI -> {
            val root = if (rawRoot == "https://api.openai.com") SubtitleTranslationDefaults.OPENAI_BASE_URL else rawRoot
            if (root.endsWith("/chat/completions")) root else "$root/chat/completions"
        }
        SubtitleTranslationProvider.ANTHROPIC -> {
            val root = if (rawRoot == "https://api.anthropic.com") SubtitleTranslationDefaults.ANTHROPIC_BASE_URL else rawRoot
            if (root.endsWith("/messages")) root else "$root/messages"
        }
        SubtitleTranslationProvider.GEMINI ->
            if (rawRoot.contains("/models/") || rawRoot.endsWith(":generateContent")) {
                if (rawRoot.endsWith(":generateContent")) rawRoot else "$rawRoot:generateContent"
            } else {
                "$rawRoot/models/${settings.model}:generateContent"
            }
    }
}
```

## Tasks

### Task 1: Add Neutral Settings Model and DataStore

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/domain/model/GeminiSettings.kt`
- Move/rename: `app/src/main/java/com/nexio/tv/data/local/GeminiSettingsDataStore.kt` to `app/src/main/java/com/nexio/tv/data/local/SubtitleTranslationSettingsDataStore.kt`
- Test: `app/src/test/java/com/nexio/tv/data/local/SubtitleTranslationSettingsDataStoreTest.kt`

- [ ] **Step 1: Write the failing defaults and migration tests**

Create `app/src/test/java/com/nexio/tv/data/local/SubtitleTranslationSettingsDataStoreTest.kt`:

```kotlin
package com.nexio.tv.data.local

import com.nexio.tv.domain.model.SubtitleTranslationDefaults
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTranslationSettingsDataStoreTest {
    @Test
    fun defaultsUseOpenAiNativeEndpointAndRequestedNanoModel() {
        val settings = defaultSubtitleTranslationSettings()

        assertEquals(SubtitleTranslationProvider.OPENAI, settings.provider)
        assertEquals(SubtitleTranslationDefaults.OPENAI_MODEL, settings.model)
        assertEquals("gpt-5-nano", settings.model)
        assertEquals("https://api.openai.com/v1", settings.baseUrl)
    }

    @Test
    fun defaultsForAnthropicUseHaikuAliasAndNativeEndpoint() {
        val settings = defaultSubtitleTranslationSettings(SubtitleTranslationProvider.ANTHROPIC)

        assertEquals("claude-haiku-4-5", settings.model)
        assertEquals("https://api.anthropic.com/v1", settings.baseUrl)
    }

    @Test
    fun legacyGeminiKeyWithoutStoredProviderMigratesToGeminiProvider() {
        val settings = normalizeSubtitleTranslationSettings(
            enabled = true,
            providerName = "",
            apiKey = "legacy-gemini-key",
            model = "",
            baseUrl = ""
        )

        assertEquals(SubtitleTranslationProvider.GEMINI, settings.provider)
        assertEquals("gemini-2.5-flash", settings.model)
        assertEquals("https://generativelanguage.googleapis.com/v1beta", settings.baseUrl)
    }

    @Test
    fun openRouterEndpointKeepsCustomModelAndBaseUrl() {
        val settings = normalizeSubtitleTranslationSettings(
            enabled = true,
            providerName = "OPENAI",
            apiKey = "openrouter-key",
            model = "openai/gpt-5.2",
            baseUrl = "https://openrouter.ai/api/v1/"
        )

        assertEquals(SubtitleTranslationProvider.OPENAI, settings.provider)
        assertEquals("openai/gpt-5.2", settings.model)
        assertEquals("https://openrouter.ai/api/v1", settings.baseUrl)
    }

    @Test
    fun invalidStoredProviderFallsBackToOpenAiNotGemini() {
        val settings = normalizeSubtitleTranslationSettings(
            enabled = true,
            providerName = "BROKEN_PROVIDER",
            apiKey = "existing-key",
            model = "",
            baseUrl = ""
        )

        assertEquals(SubtitleTranslationProvider.OPENAI, settings.provider)
        assertEquals("gpt-5-nano", settings.model)
        assertEquals("https://api.openai.com/v1", settings.baseUrl)
    }
}
```

- [ ] **Step 2: Run the failing settings tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.SubtitleTranslationSettingsDataStoreTest
```

Expected: FAIL because the neutral model and helper functions do not exist.

- [ ] **Step 3: Replace the domain model**

Replace `app/src/main/java/com/nexio/tv/domain/model/GeminiSettings.kt` with:

```kotlin
package com.nexio.tv.domain.model

enum class SubtitleTranslationProvider {
    OPENAI,
    ANTHROPIC,
    GEMINI
}

object SubtitleTranslationDefaults {
    const val OPENAI_MODEL = "gpt-5-nano"
    const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    const val ANTHROPIC_MODEL = "claude-haiku-4-5"
    const val ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
    const val GEMINI_MODEL = "gemini-2.5-flash"
    const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
}

data class SubtitleTranslationSettings(
    val enabled: Boolean = false,
    val provider: SubtitleTranslationProvider = SubtitleTranslationProvider.OPENAI,
    val apiKey: String = "",
    val model: String = SubtitleTranslationDefaults.OPENAI_MODEL,
    val baseUrl: String = SubtitleTranslationDefaults.OPENAI_BASE_URL
)
```

- [ ] **Step 4: Rename and implement the DataStore**

Rename `GeminiSettingsDataStore.kt` to `SubtitleTranslationSettingsDataStore.kt` and keep the backing DataStore name `gemini_settings` to avoid losing installed settings. Use these keys:

```kotlin
private val enabledKey = booleanPreferencesKey("gemini_enabled")
private val apiKeyKey = stringPreferencesKey("gemini_api_key")
private val providerKey = stringPreferencesKey("subtitle_translation_provider")
private val modelKey = stringPreferencesKey("subtitle_translation_model")
private val baseUrlKey = stringPreferencesKey("subtitle_translation_base_url")
```

Implement these helpers in the same file so they can be unit tested:

```kotlin
internal fun defaultSubtitleTranslationSettings(
    provider: SubtitleTranslationProvider = SubtitleTranslationProvider.OPENAI
): SubtitleTranslationSettings {
    return when (provider) {
        SubtitleTranslationProvider.OPENAI -> SubtitleTranslationSettings(
            provider = provider,
            model = SubtitleTranslationDefaults.OPENAI_MODEL,
            baseUrl = SubtitleTranslationDefaults.OPENAI_BASE_URL
        )
        SubtitleTranslationProvider.ANTHROPIC -> SubtitleTranslationSettings(
            provider = provider,
            model = SubtitleTranslationDefaults.ANTHROPIC_MODEL,
            baseUrl = SubtitleTranslationDefaults.ANTHROPIC_BASE_URL
        )
        SubtitleTranslationProvider.GEMINI -> SubtitleTranslationSettings(
            provider = provider,
            model = SubtitleTranslationDefaults.GEMINI_MODEL,
            baseUrl = SubtitleTranslationDefaults.GEMINI_BASE_URL
        )
    }
}

internal fun normalizeSubtitleTranslationSettings(
    enabled: Boolean,
    providerName: String?,
    apiKey: String?,
    model: String?,
    baseUrl: String?
): SubtitleTranslationSettings {
    val trimmedApiKey = apiKey?.trim().orEmpty()
    val trimmedProvider = providerName?.trim().orEmpty()
    val provider = if (trimmedProvider.isBlank()) {
        if (trimmedApiKey.isNotBlank()) SubtitleTranslationProvider.GEMINI else SubtitleTranslationProvider.OPENAI
    } else {
        runCatching { SubtitleTranslationProvider.valueOf(trimmedProvider.uppercase()) }
            .getOrDefault(SubtitleTranslationProvider.OPENAI)
    }
    val defaults = defaultSubtitleTranslationSettings(provider)
    return defaults.copy(
        enabled = enabled,
        apiKey = trimmedApiKey,
        model = model?.trim()?.takeIf(String::isNotBlank) ?: defaults.model,
        baseUrl = baseUrl?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: defaults.baseUrl
    )
}
```

Add setters:

```kotlin
suspend fun setProvider(provider: SubtitleTranslationProvider)
suspend fun setModel(model: String)
suspend fun setBaseUrl(baseUrl: String)
suspend fun saveConfiguration(
    provider: SubtitleTranslationProvider,
    apiKey: String,
    model: String,
    baseUrl: String
)
```

- [ ] **Step 5: Run the settings tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.SubtitleTranslationSettingsDataStoreTest
```

Expected: PASS.

### Task 2: Add Provider-Aware Translation Request Builders

**Files:**
- Create: `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationProviderRequests.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationProviderRequestsTest.kt`

- [ ] **Step 1: Write failing request builder tests**

Create `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationProviderRequestsTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationProviderRequestsTest {
    @Test
    fun openAiEndpointAppendsChatCompletionsToBaseUrl() {
        val endpoint = providerEndpoint(
            SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                model = "openai/gpt-5.2",
                baseUrl = "https://openrouter.ai/api/v1"
            )
        )

        assertEquals("https://openrouter.ai/api/v1/chat/completions", endpoint)
    }

    @Test
    fun anthropicEndpointAppendsMessagesToBaseUrl() {
        val endpoint = providerEndpoint(
            SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.ANTHROPIC,
                model = "claude-haiku-4-5",
                baseUrl = "https://anthropic-compatible.example/v1/"
            )
        )

        assertEquals("https://anthropic-compatible.example/v1/messages", endpoint)
    }

    @Test
    fun openAiEndpointAddsV1ForBareNativeHost() {
        val endpoint = providerEndpoint(
            SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                model = "gpt-5-nano",
                baseUrl = "https://api.openai.com"
            )
        )

        assertEquals("https://api.openai.com/v1/chat/completions", endpoint)
    }

    @Test
    fun geminiEndpointKeepsFullModelEndpoint() {
        val endpoint = providerEndpoint(
            SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.GEMINI,
                model = "gemini-2.5-flash",
                baseUrl = "https://my-proxy.com/v1beta/models/custom-model:generateContent"
            )
        )

        assertEquals("https://my-proxy.com/v1beta/models/custom-model:generateContent", endpoint)
    }

    @Test
    fun geminiEndpointAppendsGenerateContentToModelPath() {
        val endpoint = providerEndpoint(
            SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.GEMINI,
                model = "gemini-2.5-flash",
                baseUrl = "https://my-proxy.com/v1beta/models/custom-model"
            )
        )

        assertEquals("https://my-proxy.com/v1beta/models/custom-model:generateContent", endpoint)
    }

    @Test
    fun openAiRequestUsesChatCompletionMessagesAndJsonObjectFormat() {
        val body = buildOpenAiChatCompletionRequest(
            settings = SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.OPENAI,
                model = "gpt-5-nano"
            ),
            systemPrompt = "system",
            userPayload = JSONObject().put("items", emptyList<String>()).toString()
        )

        assertEquals("gpt-5-nano", body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun anthropicRequestUsesMessagesApiShape() {
        val body = buildAnthropicMessagesRequest(
            settings = SubtitleTranslationSettings(
                provider = SubtitleTranslationProvider.ANTHROPIC,
                model = "claude-haiku-4-5"
            ),
            systemPrompt = "system",
            userPayload = """{"items":[]}"""
        )

        assertEquals("claude-haiku-4-5", body.getString("model"))
        assertEquals("system", body.getString("system"))
        assertTrue(body.getInt("max_tokens") > 0)
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test
    fun sanitizeJsonResponseStripsMarkdownFence() {
        val sanitized = sanitizeJsonResponse(
            """
            ```json
            {"items":[{"id":1,"text":"Hallo"}]}
            ```
            """.trimIndent()
        )

        assertEquals("""{"items":[{"id":1,"text":"Hallo"}]}""", sanitized)
    }
}
```

- [ ] **Step 2: Run the failing request tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.SubtitleTranslationProviderRequestsTest
```

Expected: FAIL because the request builder file does not exist.

- [ ] **Step 3: Implement request builders and response parsers**

Create `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationProviderRequests.kt` with:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.SubtitleTranslationDefaults
import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject

private const val ANTHROPIC_VERSION = "2023-06-01"

internal fun providerEndpoint(settings: SubtitleTranslationSettings): String {
    val rawRoot = settings.baseUrl.trim().trimEnd('/').ifBlank {
        when (settings.provider) {
            SubtitleTranslationProvider.OPENAI -> SubtitleTranslationDefaults.OPENAI_BASE_URL
            SubtitleTranslationProvider.ANTHROPIC -> SubtitleTranslationDefaults.ANTHROPIC_BASE_URL
            SubtitleTranslationProvider.GEMINI -> SubtitleTranslationDefaults.GEMINI_BASE_URL
        }
    }
    return when (settings.provider) {
        SubtitleTranslationProvider.OPENAI -> {
            val root = if (rawRoot == "https://api.openai.com") SubtitleTranslationDefaults.OPENAI_BASE_URL else rawRoot
            if (root.endsWith("/chat/completions")) root else "$root/chat/completions"
        }
        SubtitleTranslationProvider.ANTHROPIC -> {
            val root = if (rawRoot == "https://api.anthropic.com") SubtitleTranslationDefaults.ANTHROPIC_BASE_URL else rawRoot
            if (root.endsWith("/messages")) root else "$root/messages"
        }
        SubtitleTranslationProvider.GEMINI ->
            if (rawRoot.contains("/models/") || rawRoot.endsWith(":generateContent")) {
                if (rawRoot.endsWith(":generateContent")) rawRoot else "$rawRoot:generateContent"
            } else {
                "$rawRoot/models/${settings.model}:generateContent"
            }
    }
}

internal fun buildOpenAiChatCompletionRequest(
    settings: SubtitleTranslationSettings,
    systemPrompt: String,
    userPayload: String,
    includeJsonMode: Boolean = true
): JSONObject {
    return JSONObject()
        .put("model", settings.model)
        .put("temperature", 0.2)
        .put(
            "messages",
            JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userPayload))
        )
        .also { body ->
            if (includeJsonMode) {
                body.put("response_format", JSONObject().put("type", "json_object"))
            }
        }
}

internal fun buildAnthropicMessagesRequest(
    settings: SubtitleTranslationSettings,
    systemPrompt: String,
    userPayload: String
): JSONObject {
    return JSONObject()
        .put("model", settings.model)
        .put("max_tokens", 8192)
        .put("temperature", 0.2)
        .put("system", systemPrompt)
        .put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", userPayload)))
            )
        )
}

internal fun openAiRequest(endpoint: String, apiKey: String, body: JSONObject): Request {
    return Request.Builder()
        .url(endpoint)
        .header("Authorization", "Bearer ${apiKey.trim()}")
        .header("Content-Type", "application/json")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
}

internal fun anthropicRequest(endpoint: String, apiKey: String, body: JSONObject): Request {
    return Request.Builder()
        .url(endpoint)
        .header("x-api-key", apiKey.trim())
        .header("anthropic-version", ANTHROPIC_VERSION)
        .header("Content-Type", "application/json")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
}

internal fun parseOpenAiResponseText(raw: String): String? {
    val payload = JSONObject(raw)
    return payload.optJSONArray("choices")
        ?.optJSONObject(0)
        ?.optJSONObject("message")
        ?.optString("content")
        ?.let(::sanitizeJsonResponse)
        ?.takeIf(String::isNotBlank)
}

internal fun parseAnthropicResponseText(raw: String): String? {
    val content = JSONObject(raw).optJSONArray("content") ?: return null
    for (index in 0 until content.length()) {
        val item = content.optJSONObject(index) ?: continue
        val text = item.optString("text").takeIf(String::isNotBlank)?.let(::sanitizeJsonResponse)
        if (text != null) return text
    }
    return null
}

internal fun sanitizeJsonResponse(text: String): String {
    val trimmed = text.trim()
    if (!trimmed.startsWith("```")) return trimmed
    val withoutOpeningFence = trimmed
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .trimStart()
    return withoutOpeningFence
        .removeSuffix("```")
        .trim()
}
```

- [ ] **Step 4: Run the request tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.SubtitleTranslationProviderRequestsTest
```

Expected: PASS.

### Task 3: Refactor the Translation Service

**Files:**
- Move/rename: `app/src/main/java/com/nexio/tv/data/repository/GeminiSubtitleTranslationService.kt` to `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
- Test: `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceProviderTest.kt`

- [ ] **Step 1: Write failing service-level tests**

Create `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceProviderTest.kt`:

```kotlin
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.SubtitleTranslationProvider
import com.nexio.tv.domain.model.SubtitleTranslationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubtitleTranslationServiceProviderTest {
    @Test
    fun cueCacheKeyIncludesProviderModelAndEndpoint() {
        val openAi = SubtitleTranslationSettings(
            provider = SubtitleTranslationProvider.OPENAI,
            model = "gpt-5-nano",
            baseUrl = "https://api.openai.com/v1"
        )
        val openRouter = openAi.copy(
            model = "anthropic/claude-haiku-4.5",
            baseUrl = "https://openrouter.ai/api/v1"
        )

        assertNotEquals(
            subtitleTranslationCueCacheKey("Hello", "nl", openAi),
            subtitleTranslationCueCacheKey("Hello", "nl", openRouter)
        )
    }

    @Test
    fun translatedSubtitleIdIncludesProviderAndTargetLanguage() {
        val id = translatedSubtitleId(
            sourceSubtitleId = "addon-sub-1",
            targetLanguage = "nl",
            settings = SubtitleTranslationSettings(provider = SubtitleTranslationProvider.ANTHROPIC)
        )

        assertEquals("ai:anthropic:addon-sub-1:nl", id)
    }

    @Test
    fun diskCacheKeyIncludesProviderModelAndEndpoint() {
        val nativeOpenAi = SubtitleTranslationSettings(
            provider = SubtitleTranslationProvider.OPENAI,
            model = "gpt-5-nano",
            baseUrl = "https://api.openai.com/v1"
        )
        val openRouter = nativeOpenAi.copy(
            model = "anthropic/claude-haiku-4.5",
            baseUrl = "https://openrouter.ai/api/v1"
        )

        assertNotEquals(
            subtitleTranslationDiskCacheKey("https://subs.example/movie.srt", "nl", nativeOpenAi),
            subtitleTranslationDiskCacheKey("https://subs.example/movie.srt", "nl", openRouter)
        )
    }

    @Test
    fun providerErrorClassificationDistinguishesAuthAndRateLimit() {
        assertEquals(
            SubtitleTranslationProviderError.InvalidApiKey,
            classifyProviderError(SubtitleTranslationProvider.OPENAI, 401, """{"error":{"message":"bad key"}}""")
        )
        assertEquals(
            SubtitleTranslationProviderError.RateLimited,
            classifyProviderError(SubtitleTranslationProvider.ANTHROPIC, 429, """{"error":{"message":"rate limited"}}""")
        )
    }
}
```

- [ ] **Step 2: Run the failing service tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest
```

Expected: FAIL because the service is still Gemini-specific.

- [ ] **Step 3: Rename service types**

In the renamed service file:

- Rename `GeminiSubtitleTranslationService` to `SubtitleTranslationService`.
- Rename `GeminiTranslatedSubtitleAsset` to `TranslatedSubtitleAsset`.
- Rename `GeminiTranslationChunkConfig` to `SubtitleTranslationChunkConfig`.
- Keep companion object constants `DEFAULT_CUE_CHUNK_CONFIG` and `ADDON_OVERLAY_CUE_CHUNK_CONFIG`.
- Change `translateSubtitle` and `translateCueTexts` signatures to accept `settings: SubtitleTranslationSettings` instead of `apiKey: String`.

Use these helper functions near the top of the file:

```kotlin
internal fun subtitleTranslationCueCacheKey(
    text: String,
    targetLanguageCode: String,
    settings: SubtitleTranslationSettings
): String {
    return sha256("cue|${settings.provider}|${settings.model}|${settings.baseUrl}|$targetLanguageCode|$text")
}

internal fun translatedSubtitleId(
    sourceSubtitleId: String,
    targetLanguage: String,
    settings: SubtitleTranslationSettings
): String {
    return "ai:${settings.provider.name.lowercase()}:$sourceSubtitleId:$targetLanguage"
}

internal fun subtitleTranslationDiskCacheKey(
    sourceUrl: String,
    targetLanguage: String,
    settings: SubtitleTranslationSettings
): String {
    return sha256("file|$sourceUrl|$targetLanguage|${settings.provider}|${settings.model}|${settings.baseUrl}|v2")
}

internal enum class SubtitleTranslationProviderError {
    InvalidApiKey,
    RateLimited,
    Transient,
    Unknown
}

internal fun classifyProviderError(
    provider: SubtitleTranslationProvider,
    statusCode: Int,
    body: String
): SubtitleTranslationProviderError {
    return when (statusCode) {
        401, 403 -> SubtitleTranslationProviderError.InvalidApiKey
        429 -> SubtitleTranslationProviderError.RateLimited
        in 500..599 -> SubtitleTranslationProviderError.Transient
        else -> SubtitleTranslationProviderError.Unknown
    }
}
```

- [ ] **Step 4: Update the HTTP execution path**

Before updating HTTP execution, update the disk cache key path. Change `resolveCacheFile` to accept `settings: SubtitleTranslationSettings` and use provider/model/base URL:

```kotlin
private fun resolveCacheFile(
    sourceUrl: String,
    targetLanguage: String,
    extension: String,
    settings: SubtitleTranslationSettings
): File {
    val cacheRoot = File(context.cacheDir, "ai-subtitles")
    val key = subtitleTranslationDiskCacheKey(sourceUrl, targetLanguage, settings)
    return File(cacheRoot, "$key.$extension")
}
```

Do not reuse the old `gemini-subtitles` root for newly generated files. Leaving the old directory in place is fine; stale legacy files become naturally unreachable because the v2 key and new cache root are different.

Replace the old `executeGenerationRequest(requestBody, apiKey)` call with provider dispatch:

```kotlin
private fun executeTranslationRequest(
    promptPayload: JSONObject,
    targetLanguageCode: String,
    targetLanguageName: String,
    settings: SubtitleTranslationSettings,
    includeSchema: Boolean
): String? {
    val systemPrompt = buildTranslationSystemPrompt(targetLanguageCode, targetLanguageName)
    val endpoint = providerEndpoint(settings)
    val request = when (settings.provider) {
        SubtitleTranslationProvider.OPENAI -> openAiRequest(
            endpoint = endpoint,
            apiKey = settings.apiKey,
            body = buildOpenAiChatCompletionRequest(
                settings = settings,
                systemPrompt = systemPrompt,
                userPayload = promptPayload.toString(),
                includeJsonMode = includeSchema
            )
        )
        SubtitleTranslationProvider.ANTHROPIC -> anthropicRequest(
            endpoint = endpoint,
            apiKey = settings.apiKey,
            body = buildAnthropicMessagesRequest(
                settings = settings,
                systemPrompt = systemPrompt,
                userPayload = promptPayload.toString()
            )
        )
        SubtitleTranslationProvider.GEMINI -> geminiRequest(
            endpoint = endpoint,
            apiKey = settings.apiKey,
            body = buildGeminiGenerationRequest(
                promptPayload = promptPayload,
                systemPrompt = systemPrompt,
                includeSchema = includeSchema
            )
        )
    }

    httpClient.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.w(TAG, "Subtitle translation request failed provider=${settings.provider} code=${response.code} body=${raw.take(300)}")
            throw providerException(settings.provider, response.code, raw)
        }
        return when (settings.provider) {
            SubtitleTranslationProvider.OPENAI -> parseOpenAiResponseText(raw)
            SubtitleTranslationProvider.ANTHROPIC -> parseAnthropicResponseText(raw)
            SubtitleTranslationProvider.GEMINI -> parseGeminiResponseText(raw)
        }
    }
}
```

Add:

```kotlin
private fun providerException(
    provider: SubtitleTranslationProvider,
    statusCode: Int,
    body: String
): Exception {
    return when (classifyProviderError(provider, statusCode, body)) {
        SubtitleTranslationProviderError.InvalidApiKey ->
            IllegalStateException("Subtitle translation API key was rejected.")
        SubtitleTranslationProviderError.RateLimited ->
            IllegalStateException("Subtitle translation provider rate limit reached. Try again later.")
        SubtitleTranslationProviderError.Transient ->
            IllegalStateException("Subtitle translation provider is temporarily unavailable.")
        SubtitleTranslationProviderError.Unknown ->
            IllegalStateException("Subtitle translation request failed with HTTP $statusCode.")
    }
}
```

Keep the existing Gemini request/response behavior in `geminiRequest`, `buildGeminiGenerationRequest`, and `parseGeminiResponseText`; the point is to move it behind the provider branch, not to drop Gemini support.

Update `parseTranslationResponse(responseText: String)` so the first line is:

```kotlin
val normalized = sanitizeJsonResponse(responseText)
```

Then keep the existing array/object parsing behavior. This makes all providers pass through the same translation JSON payload parser after provider-specific response extraction.

- [ ] **Step 5: Run focused service tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.repository.SubtitleTranslationProviderRequestsTest --tests com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest
```

Expected: PASS.

### Task 4: Wire Provider Settings Through Player Runtime

**Files:**
- Move/rename: `app/src/main/java/com/nexio/tv/ui/screens/player/GeminiBuiltInSubtitleCueTranslator.kt` to `app/src/main/java/com/nexio/tv/ui/screens/player/BuiltInSubtitleCueTranslator.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerAiSubtitles.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerBuiltInAiGroundwork.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt`
- Test: existing player tests plus `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerBuiltInAiGroundworkTest.kt`

- [ ] **Step 1: Update constructor and state names**

Change injected dependencies from:

```kotlin
internal val geminiSettingsDataStore: GeminiSettingsDataStore,
internal val geminiSubtitleTranslationService: GeminiSubtitleTranslationService,
```

to:

```kotlin
internal val subtitleTranslationSettingsDataStore: SubtitleTranslationSettingsDataStore,
internal val subtitleTranslationService: SubtitleTranslationService,
```

Replace runtime fields:

```kotlin
internal var geminiEnabled: Boolean = false
internal var geminiApiKey: String = ""
```

with:

```kotlin
@Volatile
internal var subtitleTranslationSettings = SubtitleTranslationSettings()
```

Use `@Volatile` because the DataStore observer writes the settings reference from a coroutine while player callbacks and subtitle translation paths may read it from other threads.

- [ ] **Step 2: Update built-in translator configuration token**

In the renamed `BuiltInSubtitleCueTranslator`, replace the API key provider with:

```kotlin
private val settingsProvider: () -> SubtitleTranslationSettings
```

Use this token so provider/model/endpoint changes restart translation:

```kotlin
override fun getConfigurationToken(format: Format): String? {
    val settings = settingsProvider()
    val targetLanguage = targetLanguageProvider()?.trim().orEmpty()
    if (!settings.enabled || settings.apiKey.isBlank() || targetLanguage.isBlank()) {
        return null
    }
    return "${format.sampleMimeType}|$targetLanguage|${settings.provider}|${settings.model}|${settings.baseUrl}|${settings.apiKey.hashCode()}"
}
```

- [ ] **Step 3: Update addon and built-in translation calls**

Replace calls like:

```kotlin
geminiSubtitleTranslationService.translateCueTexts(
    texts = sourceTexts,
    targetLanguageCode = targetLanguage,
    apiKey = geminiApiKey
)
```

with:

```kotlin
subtitleTranslationService.translateCueTexts(
    texts = sourceTexts,
    targetLanguageCode = targetLanguage,
    settings = subtitleTranslationSettings
)
```

Replace `aiTranslationConfigured = geminiEnabled && geminiApiKey.isNotBlank()` with:

```kotlin
aiTranslationConfigured =
    subtitleTranslationSettings.enabled && subtitleTranslationSettings.apiKey.isNotBlank()
```

- [ ] **Step 4: Run focused player tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerBuiltInAiGroundworkTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAddonSubtitleOverlayTest --tests com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest
```

Expected: PASS.

### Task 5: Update Android Integration Menu

**Files:**
- Move/rename: `app/src/main/java/com/nexio/tv/ui/screens/settings/GeminiSettingsScreen.kt` to `app/src/main/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsScreen.kt`
- Move/rename: `app/src/main/java/com/nexio/tv/ui/screens/settings/GeminiSettingsViewModel.kt` to `app/src/main/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsViewModel.kt`
- Modify: `app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify localized `strings.xml` files by replacing only Gemini-specific strings that still surface in the menu; use English fallback strings if no translation is available.
- Test: `app/src/test/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsViewModelTest.kt`

- [ ] **Step 1: Write failing ViewModel tests**

Create `app/src/test/java/com/nexio/tv/ui/screens/settings/SubtitleTranslationSettingsViewModelTest.kt`:

```kotlin
package com.nexio.tv.ui.screens.settings

import com.nexio.tv.domain.model.SubtitleTranslationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTranslationSettingsViewModelTest {
    @Test
    fun providerChangeResetsBlankModelAndEndpointToProviderDefaults() {
        val state = SubtitleTranslationSettingsUiState(
            provider = SubtitleTranslationProvider.OPENAI,
            model = "custom-model",
            baseUrl = "https://openrouter.ai/api/v1"
        )

        val next = state.withProviderDefaults(SubtitleTranslationProvider.ANTHROPIC)

        assertEquals(SubtitleTranslationProvider.ANTHROPIC, next.provider)
        assertEquals("claude-haiku-4-5", next.model)
        assertEquals("https://api.anthropic.com/v1", next.baseUrl)
    }

    @Test
    fun enablingRequiresApiKeyAndModel() {
        val state = SubtitleTranslationSettingsUiState(
            provider = SubtitleTranslationProvider.OPENAI,
            apiKey = "",
            model = "gpt-5-nano",
            baseUrl = "https://api.openai.com/v1"
        )

        assertEquals(SubtitleTranslationValidationError.MissingApiKey, state.enablementError())
    }
}
```

- [ ] **Step 2: Run the failing settings ViewModel tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.settings.SubtitleTranslationSettingsViewModelTest
```

Expected: FAIL because the renamed ViewModel and UI state do not exist.

- [ ] **Step 3: Implement provider/model/endpoint UI state**

Replace `GeminiSettingsUiState` with:

```kotlin
data class SubtitleTranslationSettingsUiState(
    val enabled: Boolean = false,
    val provider: SubtitleTranslationProvider = SubtitleTranslationProvider.OPENAI,
    val apiKey: String = "",
    val model: String = SubtitleTranslationDefaults.OPENAI_MODEL,
    val baseUrl: String = SubtitleTranslationDefaults.OPENAI_BASE_URL
) {
    fun fromSettings(settings: SubtitleTranslationSettings): SubtitleTranslationSettingsUiState = copy(
        enabled = settings.enabled,
        provider = settings.provider,
        apiKey = settings.apiKey,
        model = settings.model,
        baseUrl = settings.baseUrl
    )

    fun withProviderDefaults(provider: SubtitleTranslationProvider): SubtitleTranslationSettingsUiState {
        val defaults = defaultSubtitleTranslationSettings(provider)
        return copy(provider = provider, model = defaults.model, baseUrl = defaults.baseUrl)
    }

    fun enablementError(): SubtitleTranslationValidationError? {
        if (apiKey.isBlank()) return SubtitleTranslationValidationError.MissingApiKey
        if (model.isBlank()) return SubtitleTranslationValidationError.MissingModel
        if (baseUrl.isNotBlank() && !baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            return SubtitleTranslationValidationError.InvalidEndpoint
        }
        return null
    }
}

enum class SubtitleTranslationValidationError {
    MissingApiKey,
    MissingModel,
    InvalidEndpoint
}
```

- [ ] **Step 4: Update settings screen labels and rows**

In `SettingsScreen.kt`, rename `IntegrationSettingsSection.Gemini` to `IntegrationSettingsSection.SubtitleTranslation` and change the hub row title from `Google Gemini` to `Subtitle Translation`.

In the renamed content screen, show these rows:

- Enable Subtitle Translation
- Provider: OpenAI-compatible, Anthropic-compatible, Google Gemini
- Model
- API Endpoint
- API Key

Provider options:

```kotlin
private val providerOptions = listOf(
    SubtitleTranslationProvider.OPENAI to "OpenAI-compatible",
    SubtitleTranslationProvider.ANTHROPIC to "Anthropic-compatible",
    SubtitleTranslationProvider.GEMINI to "Google Gemini"
)
```

String updates in `values/strings.xml`:

```xml
<string name="settings_subtitle_translation_subtitle">AI subtitle translation with configurable model providers</string>
<string name="subtitle_translation_title">Subtitle Translation</string>
<string name="subtitle_translation_subtitle">Configure the model provider used for AI subtitle translation</string>
<string name="subtitle_translation_enable_title">Enable subtitle translation</string>
<string name="subtitle_translation_enable_subtitle">Allow AI subtitle translation during playback</string>
<string name="subtitle_translation_provider_title">Provider</string>
<string name="subtitle_translation_provider_subtitle">OpenAI-compatible, Anthropic-compatible, or Gemini</string>
<string name="subtitle_translation_model_title">Model</string>
<string name="subtitle_translation_model_subtitle">Defaults are gpt-5-nano for OpenAI and claude-haiku-4-5 for Anthropic</string>
<string name="subtitle_translation_endpoint_title">API Endpoint</string>
<string name="subtitle_translation_endpoint_subtitle">Leave default for the native provider or use a compatible endpoint such as OpenRouter</string>
<string name="subtitle_translation_api_key_title">API Key</string>
<string name="subtitle_translation_missing_api_key">Enter an API key before enabling subtitle translation.</string>
<string name="subtitle_translation_missing_model">Enter a model before enabling subtitle translation.</string>
<string name="subtitle_translation_invalid_endpoint">Enter an HTTP or HTTPS API endpoint.</string>
```

- [ ] **Step 5: Run settings tests and compile**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.ui.screens.settings.SubtitleTranslationSettingsViewModelTest
```

Then run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: both PASS.

### Task 6: Update Android Account Sync Contract

**Files:**
- Modify: `app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt`
- Modify: `app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt`
- Modify: `app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt`

- [ ] **Step 1: Add failing contract coverage**

Update `AccountConfigSyncContractTest` payload serialization assertions to include:

```kotlin
subtitleTranslation = SubtitleTranslationSyncSettings(
    enabled = true,
    provider = "OPENAI",
    model = "openai/gpt-5.2",
    baseUrl = "https://openrouter.ai/api/v1"
)
```

Assert:

```kotlin
val subtitleTranslation = json["integrations"]!!
    .jsonObject["subtitleTranslation"]!!
    .jsonObject
assertEquals("\"OPENAI\"", subtitleTranslation["provider"].toString())
assertEquals("\"openai/gpt-5.2\"", subtitleTranslation["model"].toString())
assertEquals("\"https://openrouter.ai/api/v1\"", subtitleTranslation["baseUrl"].toString())
```

Add apply assertions:

```kotlin
coVerify(exactly = 1) {
    subtitleTranslationSettingsDataStore.saveSyncedPublicSettings(
        enabled = true,
        provider = SubtitleTranslationProvider.OPENAI,
        model = "openai/gpt-5.2",
        baseUrl = "https://openrouter.ai/api/v1"
    )
}
```

Add a compatibility assertion that current clients always request the v6 contract explicitly:

```kotlin
assertEquals(6, buildAccountConfigSyncPullParams()["p_contract_version"]?.toString()?.toInt())
```

During implementation, inspect the Supabase decode configuration. If the Supabase Kotlin client in this project cannot be configured with `Json { ignoreUnknownKeys = true }`, do not rely on unknown-key tolerance for backward compatibility; Task 7's SQL pull gating must strip `subtitleTranslation` for v5 and older clients before they decode the payload.

- [ ] **Step 2: Run failing contract tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest
```

Expected: FAIL because `SubtitleTranslationSyncSettings` is missing and contract code still serializes `gemini`.

- [ ] **Step 3: Add sync model**

In `AccountSyncModels.kt`, replace `GeminiSyncSettings` usage with:

```kotlin
@Serializable
data class SubtitleTranslationSyncSettings(
    val enabled: Boolean = false,
    val provider: String = "OPENAI",
    val model: String = "gpt-5-nano",
    val baseUrl: String = "https://api.openai.com/v1"
)
```

In `IntegrationSettings`, add:

```kotlin
val subtitleTranslation: SubtitleTranslationSyncSettings = SubtitleTranslationSyncSettings(),
val gemini: GeminiSyncSettings = GeminiSyncSettings()
```

Keep `gemini` for decode compatibility only. Build payloads with `subtitleTranslation`; do not rely on `gemini` for new writes.

- [ ] **Step 4: Bump contract constant and apply settings**

Change `ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION` from `5` to `6`.

Deployment order is part of the contract change:

1. Deploy the Supabase SQL migration first.
2. Confirm v5 pull responses still contain `gemini.enabled` and do not contain `subtitleTranslation`.
3. Ship the Android and `nexio-web` v6 clients.

Do not ship app/web v6 clients before the SQL accepts `p_contract_version = 6`; otherwise sync push/pull can fail before the client can read its own settings.

Add helper:

```kotlin
internal fun SubtitleTranslationSyncSettings.toDomainSettings(apiKey: String = ""): SubtitleTranslationSettings {
    return normalizeSubtitleTranslationSettings(
        enabled = enabled,
        providerName = provider,
        apiKey = apiKey,
        model = model,
        baseUrl = baseUrl
    )
}
```

In `applyAccountConfigSyncSettings`, prefer the neutral field:

```kotlin
val remoteTranslation = settings.integrations.subtitleTranslation
subtitleTranslationSettingsDataStore.saveSyncedPublicSettings(
    enabled = remoteTranslation.enabled,
    provider = remoteTranslation.toDomainSettings().provider,
    model = remoteTranslation.model,
    baseUrl = remoteTranslation.baseUrl
)
```

For old snapshots where only `gemini.enabled` exists, `SubtitleTranslationSyncSettings` defaults to disabled. In Supabase Task 7, the SQL extraction maps old `gemini` into `subtitleTranslation`, so Android should not infer from `gemini` once the server migration is in place.

Mixed-version push rule: Android should send `subtitleTranslation` in v6 payloads and keep `gemini.enabled` set to the same boolean compatibility value. The SQL push function in Task 7 is responsible for preserving existing v6 `subtitleTranslation` details when a v5 client later pushes only `gemini.enabled`.

- [ ] **Step 5: Sync generic secret with legacy fallback**

In `AccountSettingsSyncService`, add:

```kotlin
private const val TRANSLATION_SECRET_TYPE = "translation_api_key"
private const val TRANSLATION_SECRET_REF = "integration:subtitle-translation"
private const val GEMINI_SECRET_TYPE = "gemini_api_key"
private const val GEMINI_SECRET_REF = "integration:gemini"
```

Push:

```kotlin
syncApiKeySecretToRemote(
    TRANSLATION_SECRET_TYPE,
    TRANSLATION_SECRET_REF,
    subtitleTranslationSettingsDataStore.settings.first().apiKey
)
```

Pull:

```kotlin
val translationKey =
    resolveApiKeySecretOrNull(TRANSLATION_SECRET_TYPE, TRANSLATION_SECRET_REF)
        ?: resolveApiKeySecretOrNull(GEMINI_SECRET_TYPE, GEMINI_SECRET_REF)
translationKey?.let { subtitleTranslationSettingsDataStore.setApiKey(it) }
```

Do not delete the legacy Gemini secret in this task; leave it as fallback until a separate cleanup migration can prove all clients have moved.

Secret cleanup lifecycle: keep `gemini_api_key` accepted by constraints and resolve functions until telemetry or release-channel evidence shows all active sync clients are v6+. Only then plan a separate cleanup that migrates remaining `gemini_api_key` payloads to `translation_api_key`, removes legacy fallback reads, and drops `gemini_api_key` from the allowed secret-type list.

- [ ] **Step 6: Run contract tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest
```

Expected: PASS.

### Task 7: Update Supabase Sync SQL

**Files:**
- Create: `supabase/migrations/20260411190000_add_subtitle_translation_provider_sync.sql`
- Create: `supabase/migrations/20260411190100_rollback_subtitle_translation_provider_sync.sql`
- Modify: `supabase/account_settings_sync.sql`
- Test: manual Supabase migration checks

- [ ] **Step 1: Add migration**

Create `supabase/migrations/20260411190000_add_subtitle_translation_provider_sync.sql` with these concrete changes:

```sql
-- Add provider-agnostic subtitle translation settings and secret support.

alter table public.account_secrets
  drop constraint if exists account_secrets_secret_type_check;

alter table public.account_secrets
  add constraint account_secrets_secret_type_check
  check (
    secret_type = any (array[
      'addon_credential',
      'tmdb_api_key',
      'omdb_api_key',
      'imdb_api_key',
      'mdblist_api_key',
      'premiumize_api_key',
      'torbox_api_key',
      'easydebrid_api_key',
      'gemini_api_key',
      'translation_api_key',
      'rpdb_api_key',
      'top_posters_api_key',
      'realdebrid_access_token',
      'realdebrid_refresh_token',
      'simkl_access_token',
      'trakt_access_token',
      'trakt_refresh_token'
    ]::text[])
  );
```

Then replace the current `account_settings_v2_default_payload()` body with the same payload plus:

```json
"subtitleTranslation": {
  "enabled": false,
  "provider": "OPENAI",
  "model": "gpt-5-nano",
  "baseUrl": "https://api.openai.com/v1"
}
```

inside `integrations`. Keep `"gemini": { "enabled": false }` in defaults for legacy reads.

In `account_settings_extract_canonical_v2`, map both v2 and legacy payloads:

```sql
'subtitleTranslation',
  coalesce(v_defaults#>'{integrations,subtitleTranslation}', '{}'::jsonb)
  || case
    when v_payload#>'{integrations,subtitleTranslation}' is not null then
      coalesce(v_payload#>'{integrations,subtitleTranslation}', '{}'::jsonb)
    when coalesce(v_payload#>>'{integrations,gemini,enabled}', 'false')::boolean then
      jsonb_build_object(
        'enabled', true,
        'provider', 'GEMINI',
        'model', 'gemini-2.5-flash',
        'baseUrl', 'https://generativelanguage.googleapis.com/v1beta'
      )
    else '{}'::jsonb
  end,
'gemini',
  coalesce(v_defaults#>'{integrations,gemini}', '{}'::jsonb)
  || coalesce(v_payload#>'{integrations,gemini}', '{}'::jsonb),
```

Update supported contract versions so version `6` is accepted wherever `5` is accepted.

In `sync_pull_account_snapshot`, keep the version gating pattern already used for `imdb`, `torBox`, and `easyDebrid`. For v6, return both `subtitleTranslation` and legacy `gemini`. For v5 and below, remove `subtitleTranslation` and set `gemini.enabled` from the neutral setting:

```sql
if v_requested_version >= 6 then
  v_integrations := jsonb_set(
    v_integrations,
    '{subtitleTranslation}',
    coalesce(v_integrations#>'{subtitleTranslation}', v_defaults#>'{integrations,subtitleTranslation}'),
    true
  );
  v_integrations := jsonb_set(
    v_integrations,
    '{gemini,enabled}',
    coalesce(v_integrations#>'{subtitleTranslation,enabled}', v_integrations#>'{gemini,enabled}', 'false'::jsonb),
    true
  );
else
  v_integrations := jsonb_set(
    v_integrations - 'subtitleTranslation',
    '{gemini,enabled}',
    coalesce(v_integrations#>'{subtitleTranslation,enabled}', v_integrations#>'{gemini,enabled}', 'false'::jsonb),
    true
  );
end if;
```

In `sync_push_account_settings`, merge v5 pushes instead of overwriting existing v6 provider details. Before the final `insert ... on conflict`, load the existing payload:

```sql
select coalesce(settings_payload, '{}'::jsonb)
  into v_existing_settings
from public.account_settings_public
where user_id = v_user_id;
```

Then preserve the existing neutral configuration when an older client only sends `gemini`:

```sql
if v_requested_version < 6 then
  v_settings := jsonb_set(
    v_settings,
    '{integrations,subtitleTranslation}',
    coalesce(
      v_existing_settings#>'{integrations,subtitleTranslation}',
      v_defaults#>'{integrations,subtitleTranslation}'
    ) || jsonb_build_object(
      'enabled',
      coalesce(v_settings#>'{integrations,gemini,enabled}', v_existing_settings#>'{integrations,subtitleTranslation,enabled}', 'false'::jsonb)
    ),
    true
  );
else
  v_settings := jsonb_set(
    v_settings,
    '{integrations,gemini,enabled}',
    coalesce(v_settings#>'{integrations,subtitleTranslation,enabled}', v_settings#>'{integrations,gemini,enabled}', 'false'::jsonb),
    true
  );
end if;
```

This preserves provider/model/base URL if a v6 client configured OpenAI/OpenRouter and a v5 client later toggles old Gemini translation on/off.

- [ ] **Step 2: Add rollback migration**

Create `supabase/migrations/20260411190100_rollback_subtitle_translation_provider_sync.sql`:

```sql
-- Roll back provider-agnostic subtitle translation public settings to legacy Gemini-compatible fields.

update public.account_settings_public
set settings_payload = jsonb_set(
    settings_payload - 'integrations',
    '{integrations}',
    (
      coalesce(settings_payload->'integrations', '{}'::jsonb) - 'subtitleTranslation'
    ) || jsonb_build_object(
      'gemini',
      jsonb_build_object(
        'enabled',
        coalesce(settings_payload#>'{integrations,subtitleTranslation,enabled}', settings_payload#>'{integrations,gemini,enabled}', 'false'::jsonb)
      )
    ),
    true
  ),
  updated_at = now(),
  updated_from = 'rollback:subtitle-translation'
where settings_payload#>'{integrations,subtitleTranslation}' is not null;
```

Rollback procedure:

1. Stop web/app v6 rollout.
2. Run the rollback migration against the affected Supabase project.
3. Redeploy the previous SQL functions from `supabase/account_settings_sync.sql`.
4. Keep `translation_api_key` secrets in place during rollback; do not delete them until users are safely back on a stable provider-agnostic release or a separate cleanup plan exists.

- [ ] **Step 3: Mirror migration in patch SQL**

Apply the same function definitions to `supabase/account_settings_sync.sql` so local patch users and migration users get identical behavior.

- [ ] **Step 4: Run Supabase migration list**

Run:

```bash
supabase migration list
```

Expected: PASS and the new migration file appears as local.

- [ ] **Step 5: Apply migration locally**

Run:

```bash
supabase migration up
```

Expected: PASS.

### Task 8: Update `nexio-web` Types, Defaults, and Secret Bindings

**Files:**
- Modify: `nexio-web/types/portal.ts`
- Modify: `nexio-web/utils/portal-defaults.ts`
- Modify: `nexio-web/utils/portal-settings.ts`
- Modify: `nexio-web/utils/portal-metadata.ts`
- Modify: `nexio-web/utils/integration-secret-bindings.ts`
- Modify: `nexio-web/utils/integration-delete.ts`
- Modify: `nexio-web/utils/account-secrets.ts`
- Test: `nexio-web/tests/portal-contract-v4.test.ts`
- Test: `nexio-web/tests/integration-secret-bindings.test.ts`
- Test: `nexio-web/tests/integration-delete.test.ts`

- [ ] **Step 1: Add failing web contract tests**

Update `nexio-web/tests/portal-contract-v4.test.ts`:

```ts
test('default portal settings expose vendor agnostic subtitle translation provider config', () => {
  const settings = defaultSettings() as any

  assert.equal(settings.schemaVersion, 6)
  assert.deepEqual(settings.integrations.subtitleTranslation, {
    enabled: false,
    provider: 'OPENAI',
    model: 'gpt-5-nano',
    baseUrl: 'https://api.openai.com/v1'
  })
})
```

Update `nexio-web/tests/integration-secret-bindings.test.ts`:

```ts
test('integrationSecretBinding maps subtitle translation to the generic translation secret ref', () => {
  assert.deepEqual(integrationSecretBinding('subtitle-translation'), {
    secretType: 'translation_api_key',
    secretRef: 'integration:subtitle-translation'
  })
})
```

Update `nexio-web/tests/integration-delete.test.ts`:

```ts
test('resetIntegrationSettings resets subtitle translation settings', () => {
  const settings = defaultSettings()
  settings.integrations.subtitleTranslation.enabled = true
  settings.integrations.subtitleTranslation.provider = 'ANTHROPIC'
  settings.integrations.subtitleTranslation.model = 'claude-haiku-4-5'
  settings.integrations.subtitleTranslation.baseUrl = 'https://anthropic-compatible.example/v1'

  const next = resetIntegrationSettings(settings, 'subtitle-translation')

  assert.deepEqual(next.integrations.subtitleTranslation, defaultSettings().integrations.subtitleTranslation)
})
```

- [ ] **Step 2: Run failing web tests**

Run from `nexio-web`:

```bash
node --import tsx --test tests/portal-contract-v4.test.ts tests/integration-secret-bindings.test.ts tests/integration-delete.test.ts
```

Expected: FAIL because the new type, secret, and reset paths do not exist.

- [ ] **Step 3: Update web types and defaults**

In `nexio-web/types/portal.ts`:

```ts
export type SubtitleTranslationProvider = 'OPENAI' | 'ANTHROPIC' | 'GEMINI'

export type SecretType =
  | 'addon_credential'
  | 'translation_api_key'
  | 'gemini_api_key'
  // keep the existing remaining entries unchanged

export const ACCOUNT_CONFIG_SYNC_CONTRACT_VERSION = 6

export type PortalIntegrations = {
  // existing entries
  subtitleTranslation: {
    enabled: boolean
    provider: SubtitleTranslationProvider
    model: string
    baseUrl: string
  }
  gemini: {
    enabled: boolean
  }
}
```

In `nexio-web/utils/portal-defaults.ts`, add:

```ts
subtitleTranslation: {
  enabled: false,
  provider: 'OPENAI',
  model: 'gpt-5-nano',
  baseUrl: 'https://api.openai.com/v1'
},
gemini: {
  enabled: false
}
```

In `nexio-web/utils/portal-settings.ts`, sanitize partial and legacy settings:

```ts
subtitleTranslation: {
  ...defaults.integrations.subtitleTranslation,
  ...(input?.integrations?.subtitleTranslation ?? (
    input?.integrations?.gemini?.enabled
      ? {
          enabled: true,
          provider: 'GEMINI',
          model: 'gemini-2.5-flash',
          baseUrl: 'https://generativelanguage.googleapis.com/v1beta'
        }
      : {}
  ))
},
gemini: {
  ...defaults.integrations.gemini,
  ...(input?.integrations?.gemini ?? {})
}
```

- [ ] **Step 4: Update secret refs and deletion mapping**

In both client and server account secret utilities, add:

```ts
subtitleTranslation: 'integration:subtitle-translation'
```

In `SecretType`, include `translation_api_key`.

In `integrationSecretBinding`:

```ts
case 'subtitle-translation':
  return { secretType: 'translation_api_key', secretRef: secretRefs.subtitleTranslation }
```

In `integrationSecretDeletion`, return the same generic secret. In `integrationSecretDeletions`, delete both generic and legacy Gemini secrets for this integration:

```ts
case 'subtitle-translation':
  return [
    { secretType: 'translation_api_key', secretRef: secretRefs.subtitleTranslation },
    { secretType: 'gemini_api_key', secretRef: secretRefs.gemini }
  ]
```

- [ ] **Step 5: Run web contract tests**

Run from `nexio-web`:

```bash
node --import tsx --test tests/portal-contract-v4.test.ts tests/integration-secret-bindings.test.ts tests/integration-delete.test.ts
```

Expected: PASS.

### Task 9: Update `nexio-web` Integration UI

**Files:**
- Modify: `nexio-web/components/portal/SettingsWorkspace.vue`
- Modify: `nexio-web/utils/portal-metadata.ts`

- [ ] **Step 1: Replace Gemini metadata**

In `portal-metadata.ts`, replace the `gemini` group with:

```ts
{
  id: 'subtitle-translation',
  title: 'Subtitle Translation',
  subtitle: 'AI subtitle translation using OpenAI-compatible, Anthropic-compatible, or Gemini providers. The API key is stored separately as a secret.',
  fields: [
    { path: 'integrations.subtitleTranslation.enabled', label: 'Enable subtitle translation', description: 'Allows AI subtitle translation during playback.', kind: 'toggle' },
    {
      path: 'integrations.subtitleTranslation.provider',
      label: 'Provider',
      description: 'Select the provider wire protocol.',
      kind: 'select',
      options: [
        { label: 'OpenAI-compatible', value: 'OPENAI' },
        { label: 'Anthropic-compatible', value: 'ANTHROPIC' },
        { label: 'Google Gemini', value: 'GEMINI' }
      ]
    },
    { path: 'integrations.subtitleTranslation.model', label: 'Model', description: 'Use gpt-5-nano for native OpenAI by default, claude-haiku-4-5 for native Anthropic, or any compatible provider model ID.', kind: 'text', placeholder: 'gpt-5-nano' },
    { path: 'integrations.subtitleTranslation.baseUrl', label: 'API endpoint', description: 'Use the provider base URL. OpenRouter uses https://openrouter.ai/api/v1.', kind: 'text', placeholder: 'https://api.openai.com/v1' }
  ]
}
```

- [ ] **Step 2: Update card and picker IDs**

In `SettingsWorkspace.vue`, replace `gemini` card/modal IDs with `subtitle-translation`. Use visible text:

- Card title: `Subtitle Translation`
- Card description: `AI subtitle translation with configurable model providers.`
- Picker description: `OpenAI, Anthropic, Gemini, or compatible endpoints`
- Modal title: `Subtitle Translation Settings`

Keep the existing Gemini image for now only if no neutral asset exists. Add a follow-up asset task only if design wants provider logos.

- [ ] **Step 3: Add provider/model/endpoint controls to the modal**

Add this block before the generic API key input when `activeModal === 'subtitle-translation'`:

```vue
<div v-if="activeModal === 'subtitle-translation'" class="space-y-4">
  <div class="space-y-2">
    <label class="text-[10px] font-bold uppercase tracking-[0.2em] text-on-surface-variant">Provider</label>
    <select
      :value="settings.integrations.subtitleTranslation.provider"
      @change="emit('update', 'integrations.subtitleTranslation.provider', ($event.target as HTMLSelectElement).value)"
      class="w-full bg-surface-container-lowest border border-outline-variant/20 focus:border-primary focus:ring-1 focus:ring-primary rounded-lg px-4 py-3 text-sm"
    >
      <option value="OPENAI">OpenAI-compatible</option>
      <option value="ANTHROPIC">Anthropic-compatible</option>
      <option value="GEMINI">Google Gemini</option>
    </select>
  </div>

  <div class="space-y-2">
    <label class="text-[10px] font-bold uppercase tracking-[0.2em] text-on-surface-variant">Model</label>
    <input
      :value="settings.integrations.subtitleTranslation.model"
      @input="emit('update', 'integrations.subtitleTranslation.model', ($event.target as HTMLInputElement).value)"
      class="w-full bg-surface-container-lowest border border-outline-variant/20 focus:border-primary focus:ring-1 focus:ring-primary rounded-lg px-4 py-3 text-sm font-mono"
      placeholder="gpt-5-nano"
    >
  </div>

  <div class="space-y-2">
    <label class="text-[10px] font-bold uppercase tracking-[0.2em] text-on-surface-variant">API endpoint</label>
    <input
      :value="settings.integrations.subtitleTranslation.baseUrl"
      @input="emit('update', 'integrations.subtitleTranslation.baseUrl', ($event.target as HTMLInputElement).value)"
      class="w-full bg-surface-container-lowest border border-outline-variant/20 focus:border-primary focus:ring-1 focus:ring-primary rounded-lg px-4 py-3 text-sm font-mono"
      placeholder="https://api.openai.com/v1"
    >
    <p class="text-[10px] text-on-surface-variant">
      Use https://openrouter.ai/api/v1 for OpenRouter, or the base URL from another OpenAI-compatible or Anthropic-compatible provider.
    </p>
  </div>
</div>
```

Update `requiresSecret`, `hasEnableToggle`, `integrationEnabled`, `toggleGenericIntegration`, `isConfigured`, and `apiLinkMap` for `subtitle-translation`.

- [ ] **Step 4: Type-check web**

Run from `nexio-web`:

```bash
./node_modules/.bin/vue-tsc --noEmit
```

Expected: PASS.

### Task 10: Update Docs and Settings Schema

**Files:**
- Modify: `docs/settings/settings-sync.schema.json`
- Modify: `docs-site/playback/subtitles-and-auto-translate.md`
- Modify: `docs-site/web/admin-workspaces/integrations.md`
- Modify: `docs-site/troubleshooting/index.md`
- Modify: `docs-site/features/index.md`
- Modify: `docs-site/android/screens/player.md`
- Modify: `docs-site/android/screens/settings.md`

- [ ] **Step 1: Update schema**

In `docs/settings/settings-sync.schema.json`, add `subtitleTranslation` to `integrations.properties`:

```json
"subtitleTranslation": {
  "type": "object",
  "additionalProperties": false,
  "required": ["enabled", "provider", "model", "baseUrl"],
  "properties": {
    "enabled": { "type": "boolean", "default": false },
    "provider": { "type": "string", "enum": ["OPENAI", "ANTHROPIC", "GEMINI"], "default": "OPENAI" },
    "model": { "type": "string", "minLength": 1, "default": "gpt-5-nano" },
    "baseUrl": { "type": "string", "default": "https://api.openai.com/v1" }
  }
}
```

Keep `gemini` as an optional legacy property if the schema still documents legacy payloads.

- [ ] **Step 2: Update user docs**

In `docs-site/playback/subtitles-and-auto-translate.md`, replace Gemini-specific sections with:

```markdown
Nexio keeps the subtitle experience simple first, then adds provider-backed translation when you actually need it.
```

For setup:

```markdown
- Open `Settings > Integration > Subtitle Translation`.
- Choose `OpenAI-compatible`, `Anthropic-compatible`, or `Google Gemini`.
- Leave the endpoint alone for the native provider, or set a compatible base URL such as `https://openrouter.ai/api/v1`.
- Set the model. The OpenAI default is `gpt-5-nano`; the Anthropic default is `claude-haiku-4-5`.
- Paste the provider API key.
```

In troubleshooting, replace:

```markdown
confirm Gemini is enabled
```

with:

```markdown
confirm Subtitle Translation is enabled, the provider API key is present, and the selected provider/model/endpoint are valid
```

- [ ] **Step 3: Run docs grep**

Run:

```bash
rg -n "Gemini|gemini|Google Gemini|AI Studio" docs-site docs/settings app/src/main/res/values/strings.xml nexio-web -g '!nexio-web/node_modules/**' -g '!nexio-web/.output/**'
```

Expected: only legacy compatibility references, the Gemini provider option, and historical migration comments remain.

### Task 11: Final Verification

**Files:** all changed implementation and docs files.

- [ ] **Step 1: Run Android translation and sync tests**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:testUniversalDebugUnitTest --tests com.nexio.tv.data.local.SubtitleTranslationSettingsDataStoreTest --tests com.nexio.tv.data.repository.SubtitleTranslationProviderRequestsTest --tests com.nexio.tv.data.repository.SubtitleTranslationServiceProviderTest --tests com.nexio.tv.core.sync.AccountConfigSyncContractTest --tests com.nexio.tv.ui.screens.settings.SubtitleTranslationSettingsViewModelTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerBuiltInAiGroundworkTest --tests com.nexio.tv.ui.screens.player.PlayerRuntimeControllerAddonSubtitleOverlayTest --tests com.nexio.tv.ui.screens.player.PlayerStartupSelectionPolicyTest
```

Expected: PASS.

- [ ] **Step 2: Compile Android**

Run:

```bash
env GRADLE_USER_HOME=/tmp/codex-gradle ./gradlew --no-daemon :app:compileUniversalDebugKotlin
```

Expected: PASS.

- [ ] **Step 3: Run web contract tests**

Run from `nexio-web`:

```bash
node --import tsx --test tests/portal-contract-v4.test.ts tests/integration-secret-bindings.test.ts tests/integration-delete.test.ts tests/portal-imdb-merge.test.ts
```

Expected: PASS.

- [ ] **Step 4: Type-check web**

Run from `nexio-web`:

```bash
./node_modules/.bin/vue-tsc --noEmit
```

Expected: PASS.

- [ ] **Step 5: Check migration state**

Run:

```bash
supabase migration list
```

Expected: PASS and `20260411190000_add_subtitle_translation_provider_sync.sql` is listed locally.

- [ ] **Step 6: Review mixed-version compatibility manually**

Check these scenarios:

- Existing local app with `gemini_api_key` and no provider preference reads as provider `GEMINI`, model `gemini-2.5-flash`, endpoint `https://generativelanguage.googleapis.com/v1beta`.
- New local app with no translation preferences reads as provider `OPENAI`, model `gpt-5-nano`, endpoint `https://api.openai.com/v1`, disabled.
- Web OpenRouter setup stores provider `OPENAI`, model like `openai/gpt-5.2` or any user-entered model, endpoint `https://openrouter.ai/api/v1`, and secret type `translation_api_key`.
- Anthropic setup stores provider `ANTHROPIC`, model `claude-haiku-4-5`, endpoint `https://api.anthropic.com/v1`, and secret type `translation_api_key`.
- A contract v5 pull response maps `subtitleTranslation.enabled` to `gemini.enabled` and omits `subtitleTranslation`.
- A contract v5 push that includes only `gemini.enabled` preserves an existing v6 `subtitleTranslation.provider`, `model`, and `baseUrl`.
- A failed provider request with HTTP 401/403 surfaces the rejected API key message, while HTTP 429 surfaces the rate-limit message and does not trigger adaptive chunk splitting into more provider calls.

## Self-Review

Spec coverage:

- Android app-level configuration: covered by Tasks 1, 4, 5, and 6.
- Provider-agnostic OpenAI and Anthropic HTTP support: covered by Tasks 2 and 3.
- Any OpenAI-compatible endpoint such as OpenRouter: covered by endpoint normalization and web/app model/base URL settings in Tasks 1, 2, 5, 8, and 9.
- Native OpenAI default `gpt-5-nano` and native Anthropic default `claude-haiku-4-5`: covered by Tasks 1, 5, 8, and 10.
- `nexio-web` provider/model/endpoint configuration: covered by Tasks 8 and 9.
- Supabase account sync and secret storage: covered by Tasks 6 and 7.
- Documentation updates: covered by Task 10.
- Review findings C1/C2 and H1-H5/M1-M4: covered by Revision Notes plus Tasks 1, 2, 3, 4, 6, 7, and 11.

Placeholder scan:

- The plan contains no unresolved placeholder steps or vague catch-all implementation instructions.
- The only open product note is the known model-name mismatch between the requested `gpt-5-nano` default and the current OpenAI docs listing newer `gpt-5.4-nano`; the plan follows the requested default and keeps model editable.

Type consistency:

- Android uses `SubtitleTranslationSettings`, `SubtitleTranslationProvider`, and `SubtitleTranslationSettingsDataStore`.
- Web uses `subtitleTranslation` settings and `translation_api_key` secret type.
- Legacy `gemini` and `gemini_api_key` are retained only as read/fallback compatibility paths.
