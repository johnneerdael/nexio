package com.nexio.tv.core.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountConfigResetSuppressionContractTest {
    @Test
    fun `local account reset suppresses pending account config pushes`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val premiumizeSource = File("app/src/main/java/com/nexio/tv/data/repository/PremiumizeService.kt").readText()
        val torBoxSource = File("app/src/main/java/com/nexio/tv/data/repository/TorBoxService.kt").readText()
        val easyDebridSource = File("app/src/main/java/com/nexio/tv/data/repository/EasyDebridService.kt").readText()
        val suppressionStart = source.indexOf("suspend fun runWithLocalResetPushSuppressed")
        val resetStart = source.indexOf("suspend fun resetLocalAccountConfigToDefaults()")
        val suppressionEnd = source.indexOf("suspend fun resetLocalAccountConfigToDefaults()", startIndex = suppressionStart)
            .takeIf { it > suppressionStart }
            ?: source.indexOf("private suspend fun clearLocalAccountSecrets", startIndex = suppressionStart)
        val resetEnd = source.indexOf("private suspend fun clearLocalAccountSecrets", startIndex = resetStart)
            .takeIf { it > resetStart }
            ?: source.length
        val suppressionBody = source.substring(suppressionStart, suppressionEnd)
        val resetBody = source.substring(resetStart, resetEnd)

        assertTrue(resetBody.contains("runWithLocalResetPushSuppressed {"))
        assertTrue(source.contains("premiumizeService.clearLocalAccountState()"))
        assertTrue(source.contains("torBoxService.clearLocalAccountState()"))
        assertTrue(source.contains("easyDebridService.clearLocalAccountState()"))
        assertTrue(premiumizeSource.contains("accountStateGeneration != observedGeneration || currentApiKey != observedApiKey"))
        assertTrue(torBoxSource.contains("accountStateGeneration != observedGeneration || currentApiKey != observedApiKey"))
        assertTrue(easyDebridSource.contains("accountStateGeneration != observedGeneration || currentApiKey != observedApiKey"))
        assertTrue(premiumizeSource.contains("replaceRefreshedAccountState("))
        assertTrue(torBoxSource.contains("replaceRefreshedAccountState("))
        assertTrue(easyDebridSource.contains("replaceRefreshedAccountState("))
        assertTrue(suppressionBody.contains("applyingRemoteMutex.withLock"))
        assertTrue(suppressionBody.contains("pushJob?.cancel()"))
        assertTrue(suppressionBody.contains("pushJob = null"))
        assertTrue(suppressionBody.contains("isApplyingRemote = true"))
        assertTrue(suppressionBody.contains("pendingChangedPaths.clear()"))
        assertTrue(suppressionBody.contains("pendingChangedPathsGeneration += 1L"))
        assertTrue(suppressionBody.contains("isApplyingRemote = false"))
    }

    @Test
    fun `local reset suppression serializes account push snapshot without holding mutex during remote rpc`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val pushStart = source.indexOf("suspend fun pushToRemote()")
        val pullStart = source.indexOf("suspend fun pullFromRemoteAndApply", startIndex = pushStart)
        val pushBody = source.substring(pushStart, pullStart)

        val mutexLock = pushBody.indexOf("applyingRemoteMutex.withLock")
        val snapshotReturn = pushBody.indexOf("AccountPushSnapshot(")
        val snapshotLockRelease = pushBody.indexOf("} ?: return@withContext Result.success(Unit)", startIndex = snapshotReturn)
        val firstLiveSessionCheck = pushBody.indexOf("if (isApplyingRemote || !hasLiveFullAccountSession())")
        val payloadBuild = pushBody.indexOf("val payload = buildLocalPayload()")
        val secondLiveSessionCheck = pushBody.indexOf("if (!hasLiveFullAccountSession())", startIndex = snapshotLockRelease)
        val remoteCall = pushBody.indexOf("withJwtRefreshRetry")
        val remotePushRpc = pushBody.indexOf("\"sync_push_account_settings_v7\"", startIndex = remoteCall)
        val afterSnapshotCapture = pushBody.substring(snapshotLockRelease)

        assertTrue("pushToRemote must acquire the same mutex as local reset suppression", mutexLock >= 0)
        assertTrue("live session check must happen inside the guarded section", mutexLock < firstLiveSessionCheck)
        assertTrue("payload must be built only after the guarded live session check", firstLiveSessionCheck < payloadBuild)
        assertTrue("payload snapshot must be returned from the guarded section", payloadBuild < snapshotReturn)
        assertTrue("payload snapshot mutex must be released before remote calls", snapshotReturn < snapshotLockRelease)
        assertTrue(
            "pushToRemote must re-check live session after payload build and before remote push RPC",
            secondLiveSessionCheck in (snapshotLockRelease + 1) until remotePushRpc
        )
        assertTrue(
            "pushToRemote must not hold the local reset mutex while executing withJwtRefreshRetry",
            snapshotLockRelease < remoteCall
        )
        assertFalse(
            "pushToRemote must not read local stores after releasing the reset mutex",
            afterSnapshotCapture.contains(".settings.first()") ||
                afterSnapshotCapture.contains(".state.first()") ||
                afterSnapshotCapture.contains(".stateForProfile(") ||
                afterSnapshotCapture.contains(".observeAccountState().first()")
        )
    }

    @Test
    fun `pull resolves remote secrets before acquiring local apply mutex`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()
        val pullStart = source.indexOf("suspend fun pullFromRemoteAndApply")
        val pullEnd = source.indexOf("private fun hasLiveFullAccountSession", startIndex = pullStart)
        val pullBody = source.substring(pullStart, pullEnd)

        val snapshotPullRpc = pullBody.indexOf("\"sync_pull_account_snapshot\"")
        val secretResolution = pullBody.indexOf("val resolvedSecrets = resolveRemoteSecretsForApply(snapshot.settings)")
        val mutexLock = pullBody.indexOf("applyingRemoteMutex.withLock")
        val lockBody = pullBody.substring(mutexLock)
        val guardedLiveSessionCheck = lockBody.indexOf("if (!hasLiveFullAccountSession())")
        val applyingRemoteFlag = lockBody.indexOf("isApplyingRemote = true")
        val stalePullFailure = pullBody.indexOf("if (!appliedRemoteSettings)")
        val addonBuild = pullBody.indexOf("val remoteAddonConfigs = buildRemoteAddonInstallConfigs")
        val finalLiveSessionCheck = pullBody.indexOf("if (!hasLiveFullAccountSession())", startIndex = addonBuild)
        val addonResult = pullBody.indexOf("Result.success(remoteAddonConfigs)")

        assertTrue("pullFromRemoteAndApply must fetch the account snapshot before resolving secrets", snapshotPullRpc >= 0)
        assertTrue("pullFromRemoteAndApply must resolve remote secrets before acquiring the apply mutex", secretResolution >= 0)
        assertTrue("pullFromRemoteAndApply must acquire the local apply mutex", mutexLock >= 0)
        assertTrue(
            "pullFromRemoteAndApply must not hold applyingRemoteMutex while resolving remote secrets",
            secretResolution in (snapshotPullRpc + 1) until mutexLock
        )
        assertTrue(lockBody.contains("applyResolvedRemoteSecrets(resolvedSecrets)"))
        assertTrue(guardedLiveSessionCheck >= 0 && guardedLiveSessionCheck < applyingRemoteFlag)
        assertTrue(stalePullFailure in (mutexLock + 1) until addonBuild)
        assertTrue(finalLiveSessionCheck in (addonBuild + 1) until addonResult)
        assertFalse(
            "pullFromRemoteAndApply must not call remote secret resolution while applyingRemoteMutex is held",
            lockBody.contains("resolveRemoteSecretsForApply") ||
                lockBody.contains("withJwtRefreshRetry")
        )
    }
}
