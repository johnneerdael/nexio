---
phase: 06-tvdb-foundation-and-identity
reviewed: 2026-04-15T02:26:21Z
depth: standard
files_reviewed: 27
files_reviewed_list:
  - app/src/main/java/com/nexio/tv/core/di/NetworkModule.kt
  - app/src/main/java/com/nexio/tv/core/sync/AccountConfigSyncContract.kt
  - app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityModels.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbIdentityService.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbProviderFallback.kt
  - app/src/main/java/com/nexio/tv/core/tvdb/TvdbRemoteIdNormalizer.kt
  - app/src/main/java/com/nexio/tv/data/local/TvdbIdentityCacheStore.kt
  - app/src/main/java/com/nexio/tv/data/local/TvdbSettingsDataStore.kt
  - app/src/main/java/com/nexio/tv/data/local/TvdbTokenStore.kt
  - app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt
  - app/src/main/java/com/nexio/tv/data/remote/supabase/AccountSyncModels.kt
  - app/src/main/java/com/nexio/tv/domain/model/TvdbSettings.kt
  - app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt
  - app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt
  - app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModel.kt
  - app/src/main/res/values/strings.xml
  - app/src/test/java/com/nexio/tv/core/sync/AccountConfigSyncContractTest.kt
  - app/src/test/java/com/nexio/tv/core/sync/TvdbSecretAllowlistStaticTest.kt
  - app/src/test/java/com/nexio/tv/core/tvdb/TvdbAuthServiceTest.kt
  - app/src/test/java/com/nexio/tv/core/tvdb/TvdbDiagnosticsTest.kt
  - app/src/test/java/com/nexio/tv/core/tvdb/TvdbIdentityServiceTest.kt
  - app/src/test/java/com/nexio/tv/core/tvdb/TvdbProviderFallbackTest.kt
  - app/src/test/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModelTest.kt
  - docs/settings/settings-sync.schema.json
  - supabase/account_settings_sync.sql
findings:
  critical: 0
  warning: 3
  info: 0
  total: 3
status: issues_found
---

# Phase 6: Code Review Report

**Reviewed:** 2026-04-15T02:26:21Z
**Depth:** standard
**Files Reviewed:** 27
**Status:** issues_found

## Summary

Reviewed the TVDB foundation, account sync, settings UI, schema, and tests. The implementation avoids syncing credential material in the public settings payload, but there are three behavioral/contract issues to address before relying on the feature: transient TVDB auth failures are persisted as invalid credentials, opening settings can turn a fallback diagnostic into a disabled provider, and the JSON schema is behind the formatter payload shape emitted by the app.

## Warnings

### WR-01: Transient TVDB Auth Failures Mark Credentials Invalid

**File:** `app/src/main/java/com/nexio/tv/core/tvdb/TvdbAuthService.kt:212`

**Issue:** `requestToken()` catches every exception and returns `InvalidCredentials`; `bearerToken()` and `validateCredentials()` then clear the token and persist `TvdbValidationStatus.INVALID`. A timeout, offline device, DNS failure, or TVDB 5xx during token refresh will therefore mark otherwise valid credentials as invalid and can disable TVDB behavior until the user revalidates manually. Current tests cover 401, but not network exceptions or 5xx responses.

**Fix:**
```kotlin
sealed class TvdbAuthResult(open val status: TvdbValidationStatus) {
    class Valid(...) : TvdbAuthResult(TvdbValidationStatus.VALID)
    class InvalidCredentials(val lastFailure: String) : TvdbAuthResult(TvdbValidationStatus.INVALID)
    class AuthUnavailable(val lastFailure: String) : TvdbAuthResult(TvdbValidationStatus.FALLBACK_ACTIVE)
}

private suspend fun requestToken(apiKey: String, pin: String): TvdbAuthResult {
    return try {
        val response = tvdbApi.login(TvdbLoginRequest(apikey = apiKey, pin = pin.takeIf { it.isNotBlank() }))
        when {
            response.isSuccessful && !response.body()?.data?.token.isNullOrBlank() -> TvdbAuthResult.Valid(...)
            response.code() == 401 -> TvdbAuthResult.InvalidCredentials("Invalid TVDB credentials")
            else -> TvdbAuthResult.AuthUnavailable("TVDB login failed with HTTP ${response.code()}")
        }
    } catch (error: Exception) {
        TvdbAuthResult.AuthUnavailable("TVDB login failed: ${error.javaClass.simpleName}")
    }
}
```

Then handle `AuthUnavailable` without clearing cached credentials or saving `INVALID`. Add tests for `IOException` and HTTP 500 that assert credentials and token state are not cleared.

### WR-02: Settings Observation Disables Fallback-Active TVDB

**File:** `app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsViewModel.kt:36`

**Issue:** The settings collector disables TVDB whenever `settings.enabled && !settings.isActive`. `isActive` is only true for `VALID`, so a valid configured provider that records `FALLBACK_ACTIVE` through `TvdbProviderFallback.recordFallback()` is automatically saved as disabled as soon as this ViewModel observes it. That loses the distinction between "TVDB enabled but temporarily falling back" and "TVDB turned off", and makes the fallback status string hard to trust.

**Fix:**
```kotlin
dataStore.settings.collectLatest { settings ->
    val shouldForceDisable =
        settings.enabled &&
            (!settings.configured || settings.validationStatus == TvdbValidationStatus.INVALID)

    if (shouldForceDisable) {
        dataStore.setEnabled(false)
        _uiState.update { it.fromSettings(settings.copy(enabled = false)) }
        return@collectLatest
    }

    _uiState.update { it.fromSettings(settings) }
}
```

Alternatively, remove the auto-disable entirely and rely on the toggle handler to prevent enabling invalid configurations. Add a ViewModel test with `enabled = true`, a nonblank API key, and `FALLBACK_ACTIVE` asserting `setEnabled(false)` is not called.

### WR-03: Sync Schema Rejects Formatter Payloads With Badge Rows

**File:** `docs/settings/settings-sync.schema.json:307`

**Issue:** `CustomFormatterSyncTemplate` now includes `badgeRowTemplate`, and the Kotlin test explicitly expects it in the serialized payload. The schema's `customTemplate` object has `additionalProperties: false` but does not allow `badgeRowTemplate`, so schema validation rejects a payload the app can emit when a custom formatter uses badge row content.

**Fix:**
```json
"required": ["id", "label", "nameTemplate", "descriptionTemplate", "badgeRowTemplate"],
"properties": {
  "id": { "type": "string", "default": "custom" },
  "label": { "type": "string", "default": "Custom" },
  "nameTemplate": { "type": "string", "default": "" },
  "descriptionTemplate": { "type": "string", "default": "" },
  "badgeRowTemplate": { "type": "string", "default": "" }
}
```

Add or extend a schema validation test so the JSON produced by `AccountConfigSyncPayload.serializer()` with a custom formatter is accepted by `docs/settings/settings-sync.schema.json`.

---

_Reviewed: 2026-04-15T02:26:21Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
