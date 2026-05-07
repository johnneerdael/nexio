package com.nexio.tv.core.artwork

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class DurableArtworkDecisionCache(
    private val file: File,
    private val gson: Gson,
    private val traceSink: RuntimeTraceSink = NoopRuntimeTraceSink,
    private val thumbnailWriteDebounceMs: Long = 0L
) : ArtworkDecisionCache, ArtworkDecisionCacheDiagnostics {
    private val lock = Any()
    private var loaded = false
    private val decisions = linkedMapOf<ArtworkDecisionKey, ArtworkDecision>()
    private val previewToCanonical = linkedMapOf<ArtworkDecisionKey, ArtworkDecisionKey>()
    private val traceSequence = AtomicLong(0L)
    private val flushExecutor: ScheduledExecutorService? =
        if (thumbnailWriteDebounceMs > 0L) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ArtworkDecisionCacheFlush").apply { isDaemon = true }
            }
        } else {
            null
        }
    private var pendingFlush: ScheduledFuture<*>? = null
    private var dirty = false
    private var lastLoadSuccess: Boolean? = null
    private var lastLoadReason: String? = null
    private var lastLoadErrorClass: String? = null
    private var lastDroppedDecisionCount: Int? = null

    override fun get(key: ArtworkDecisionKey): ArtworkDecision? = synchronized(lock) {
        ensureLoadedLocked()
        decisions[key]
    }

    override fun put(decision: ArtworkDecision) = synchronized(lock) {
        ensureLoadedLocked()
        val existing = decisions[decision.decisionKey]
        if (existing != null && existing.hasSameDurablePayload(decision)) {
            return@synchronized
        }
        decisions[decision.decisionKey] = decision
        traceArtwork(
            eventType = "artwork.decision_put",
            payload = mapOf(
                "decisionKey" to decision.decisionKey.value,
                "provider" to decision.selectedCandidate.provider?.key,
                "imageType" to decision.imageType.name,
                "sourceRole" to decision.selectedCandidate.sourceRole.name,
                "rejectedCount" to decision.rejectedCandidates.size,
                "hasFallbackCandidate" to decision.rejectedCandidates.any { candidate ->
                    candidate.provider != null &&
                        candidate.sourceRole in FALLBACK_SOURCE_ROLES
                }
            )
        )
        persistAfterPutLocked(decision)
    }

    override fun remove(key: ArtworkDecisionKey) = synchronized(lock) {
        ensureLoadedLocked()
        decisions.remove(key)
        removeLinksForLocked(setOf(key))
        persistNowLocked()
    }

    override fun linkPreviewToCanonical(
        previewKey: ArtworkDecisionKey,
        canonicalKey: ArtworkDecisionKey
    ) = synchronized(lock) {
        ensureLoadedLocked()
        previewToCanonical[previewKey] = canonicalKey
        persistNowLocked()
    }

    override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = synchronized(lock) {
        ensureLoadedLocked()
        previewToCanonical[previewKey]?.let(decisions::get)
    }

    override fun invalidateBySettingsHash(settingsHash: String) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision -> decision.settingsHash == settingsHash }
    }

    override fun invalidateByCredentialHash(credentialHash: String) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision -> decision.credentialHash == credentialHash }
    }

    override fun invalidateArtworkPolicy(
        settingsHashes: Set<String>,
        credentialHashes: Set<String>
    ) = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision ->
            decision.settingsHash in settingsHashes || decision.credentialHash in credentialHashes
        }
    }

    override fun invalidatePremiumArtworkPolicy() = synchronized(lock) {
        ensureLoadedLocked()
        invalidateMatchingLocked { decision ->
            decision.settingsHash != null || decision.credentialHash != null
        }
    }

    override fun snapshotDiagnostics(): ArtworkDecisionCacheSnapshotDiagnostics = synchronized(lock) {
        val fileStats = currentFileStats()
        ArtworkDecisionCacheSnapshotDiagnostics(
            loaded = loaded,
            decisionCount = decisions.size,
            linkCount = previewToCanonical.size,
            storeFilePresent = fileStats.present,
            storeFileReadable = fileStats.readable,
            storeFileBytes = fileStats.bytes,
            lastLoadSuccess = lastLoadSuccess,
            lastLoadReason = lastLoadReason,
            lastLoadErrorClass = lastLoadErrorClass,
            droppedDecisionCount = lastDroppedDecisionCount
        )
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        if (!file.isFile) {
            traceDecisionStoreLoad(
                success = true,
                decisionCount = 0,
                linkCount = 0,
                droppedDecisionCount = 0,
                filePresent = false
            )
            return
        }

        runCatching {
            val raw = file.readText()
            if (raw.isBlank()) {
                traceDecisionStoreLoad(
                    success = true,
                    decisionCount = 0,
                    linkCount = 0,
                    droppedDecisionCount = 0,
                    filePresent = true
                )
                return
            }
            val dto = gson.fromJson(raw, StoreDto::class.java)
            if (dto == null) {
                traceDecisionStoreLoad(
                    success = false,
                    decisionCount = 0,
                    linkCount = 0,
                    droppedDecisionCount = 0,
                    filePresent = true,
                    reason = "null_store"
                )
                return
            }
            if (dto.schemaVersion != SCHEMA_VERSION) {
                traceDecisionStoreLoad(
                    success = false,
                    decisionCount = 0,
                    linkCount = 0,
                    droppedDecisionCount = dto.decisions.orEmpty().size,
                    filePresent = true,
                    reason = "schema_version_mismatch",
                    schemaVersion = dto.schemaVersion
                )
                return
            }

            var droppedDecisionCount = 0
            dto.decisions.orEmpty()
                .mapNotNull { decision ->
                    decision.toDomainOrNull().also { restored ->
                        if (restored == null) droppedDecisionCount += 1
                    }
                }
                .forEach { decision -> decisions[decision.decisionKey] = decision }
            dto.previewLinks.orEmpty().forEach { link ->
                runCatching {
                    previewToCanonical[ArtworkDecisionKey(link.previewKey)] =
                        ArtworkDecisionKey(link.canonicalKey)
                }.onFailure {
                    // Broken preview links are non-authoritative; decisions remain usable.
                }
            }
            traceDecisionStoreLoad(
                success = true,
                decisionCount = decisions.size,
                linkCount = previewToCanonical.size,
                droppedDecisionCount = droppedDecisionCount,
                filePresent = true
            )
        }.onFailure { error ->
            decisions.clear()
            previewToCanonical.clear()
            traceDecisionStoreLoad(
                success = false,
                decisionCount = 0,
                linkCount = 0,
                droppedDecisionCount = 0,
                filePresent = true,
                errorClass = error.javaClass.simpleName
            )
        }
    }

    private fun invalidateMatchingLocked(matches: (ArtworkDecision) -> Boolean) {
        val deletedKeys = decisions.values
            .filter(matches)
            .mapTo(mutableSetOf()) { decision -> decision.decisionKey }
        if (deletedKeys.isEmpty()) return

        deletedKeys.forEach(decisions::remove)
        removeLinksForLocked(deletedKeys)
        persistNowLocked()
    }

    private fun removeLinksForLocked(keys: Set<ArtworkDecisionKey>) {
        val links = previewToCanonical.iterator()
        while (links.hasNext()) {
            val (previewKey, canonicalKey) = links.next()
            if (previewKey in keys || canonicalKey in keys) {
                links.remove()
            }
        }
    }

    internal fun flushPendingWritesForTest() = synchronized(lock) {
        flushPendingWritesLocked()
    }

    private fun persistAfterPutLocked(decision: ArtworkDecision) {
        if (decision.imageType == ArtworkType.THUMBNAIL && thumbnailWriteDebounceMs > 0L) {
            scheduleThumbnailPersistLocked()
        } else {
            persistNowLocked()
        }
    }

    private fun scheduleThumbnailPersistLocked() {
        dirty = true
        val currentFlush = pendingFlush
        if (currentFlush != null && !currentFlush.isDone && !currentFlush.isCancelled) return

        val executor = flushExecutor
        if (executor == null) {
            flushPendingWritesLocked()
            return
        }

        pendingFlush = executor.schedule(
            {
                synchronized(lock) {
                    flushPendingWritesLocked()
                }
            },
            thumbnailWriteDebounceMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun flushPendingWritesLocked() {
        pendingFlush?.cancel(false)
        pendingFlush = null
        if (!dirty) return
        dirty = false
        persistLocked()
    }

    private fun persistNowLocked() {
        pendingFlush?.cancel(false)
        pendingFlush = null
        dirty = false
        persistLocked()
    }

    private fun persistLocked() {
        var tempFile: File? = null
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()

            val dto = StoreDto(
                schemaVersion = SCHEMA_VERSION,
                decisions = decisions.values.map(DecisionDto::fromDomain),
                previewLinks = previewToCanonical.map { (previewKey, canonicalKey) ->
                    PreviewLinkDto(
                        previewKey = previewKey.value,
                        canonicalKey = canonicalKey.value
                    )
                }
            )
            tempFile = File(parent ?: File("."), "${file.name}.tmp")
            tempFile.writeText(gson.toJson(dto))
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            traceDecisionStoreWrite(success = true)
        } catch (error: Exception) {
            tempFile?.delete()
            traceDecisionStoreWrite(
                success = false,
                errorClass = error.javaClass.simpleName
            )
        }
    }

    private fun traceDecisionStoreLoad(
        success: Boolean,
        decisionCount: Int,
        linkCount: Int,
        droppedDecisionCount: Int,
        filePresent: Boolean,
        reason: String? = null,
        schemaVersion: Int? = null,
        errorClass: String? = null
    ) {
        val fileStats = currentFileStats()
        lastLoadSuccess = success
        lastLoadReason = reason
        lastLoadErrorClass = errorClass
        lastDroppedDecisionCount = droppedDecisionCount
        traceArtwork(
            eventType = "artwork.decision_store_load",
            payload = mapOf(
                "success" to success,
                "filePresent" to filePresent,
                "fileReadable" to fileStats.readable,
                "fileBytes" to fileStats.bytes,
                "decisionCount" to decisionCount,
                "linkCount" to linkCount,
                "droppedDecisionCount" to droppedDecisionCount,
                "reason" to reason,
                "schemaVersion" to schemaVersion,
                "errorClass" to errorClass
            )
        )
    }

    private fun currentFileStats(): FileStats {
        val present = file.exists()
        return FileStats(
            present = present,
            readable = present && file.canRead(),
            bytes = if (present && file.isFile) file.length() else null
        )
    }

    private data class FileStats(
        val present: Boolean,
        val readable: Boolean,
        val bytes: Long?
    )

    private fun traceDecisionStoreWrite(
        success: Boolean,
        errorClass: String? = null
    ) {
        traceArtwork(
            eventType = "artwork.decision_store_write",
            payload = mapOf(
                "success" to success,
                "decisionCount" to decisions.size,
                "linkCount" to previewToCanonical.size,
                "errorClass" to errorClass
            )
        )
    }

    private fun traceArtwork(
        eventType: String,
        payload: Map<String, Any?>
    ) {
        traceSink.emit(
            TraceEventEnvelope(
                traceSessionId = traceSink.activeTraceSessionId() ?: LOGCAT_ONLY_TRACE_SESSION_ID,
                sequence = traceSequence.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = eventType,
                payload = payload
            )
        )
    }

    private data class StoreDto(
        @SerializedName("schemaVersion")
        val schemaVersion: Int,
        @SerializedName("decisions")
        val decisions: List<DecisionDto>?,
        @SerializedName("previewLinks")
        val previewLinks: List<PreviewLinkDto>?
    )

    private data class PreviewLinkDto(
        @SerializedName("previewKey")
        val previewKey: String,
        @SerializedName("canonicalKey")
        val canonicalKey: String
    )

    private data class DecisionDto(
        @SerializedName("decisionKey")
        val decisionKey: String,
        @SerializedName("owner")
        val owner: OwnerDto,
        @SerializedName("canonicalContentId")
        val canonicalContentId: String?,
        @SerializedName("imageType")
        val imageType: String,
        @SerializedName("selectedCandidate")
        val selectedCandidate: CandidateDto,
        @SerializedName("rejectedCandidates")
        val rejectedCandidates: List<RejectedDto>?,
        @SerializedName("policyVersion")
        val policyVersion: Int,
        @SerializedName("imageLanguage")
        val imageLanguage: String,
        @SerializedName("settingsHash")
        val settingsHash: String?,
        @SerializedName("credentialHash")
        val credentialHash: String?,
        @SerializedName("createdAtMs")
        val createdAtMs: Long,
        @SerializedName("expiresAtMs")
        val expiresAtMs: Long,
        @SerializedName("staleUntilMs")
        val staleUntilMs: Long?
    ) {
        fun toDomainOrNull(): ArtworkDecision? = runCatching {
            ArtworkDecision(
                decisionKey = ArtworkDecisionKey(decisionKey),
                ownerKey = owner.toDomain(),
                canonicalContentId = canonicalContentId,
                imageType = ArtworkType.valueOf(imageType),
                selectedCandidate = selectedCandidate.toDomain(),
                rejectedCandidates = rejectedCandidates.orEmpty().map { rejected -> rejected.toDomain() },
                policyVersion = policyVersion,
                imageLanguage = imageLanguage,
                settingsHash = settingsHash,
                credentialHash = credentialHash,
                createdAtMs = createdAtMs,
                expiresAtMs = expiresAtMs,
                staleUntilMs = staleUntilMs
            )
        }.getOrNull()

        companion object {
            fun fromDomain(decision: ArtworkDecision): DecisionDto =
                DecisionDto(
                    decisionKey = decision.decisionKey.value,
                    owner = OwnerDto.fromDomain(decision.ownerKey),
                    canonicalContentId = decision.canonicalContentId,
                    imageType = decision.imageType.name,
                    selectedCandidate = CandidateDto.fromDomain(decision.selectedCandidate),
                    rejectedCandidates = decision.rejectedCandidates.map(RejectedDto::fromDomain),
                    policyVersion = decision.policyVersion,
                    imageLanguage = decision.imageLanguage,
                    settingsHash = decision.settingsHash,
                    credentialHash = decision.credentialHash,
                    createdAtMs = decision.createdAtMs,
                    expiresAtMs = decision.expiresAtMs,
                    staleUntilMs = decision.staleUntilMs
                )
        }
    }

    private data class OwnerDto(
        @SerializedName("type")
        val type: String,
        @SerializedName("contentId")
        val contentId: String?,
        @SerializedName("itemKey")
        val itemKey: String?,
        @SerializedName("sourcePayloadHash")
        val sourcePayloadHash: String?
    ) {
        fun toDomain(): ArtworkOwnerKey = when (type) {
            "canonical" -> ArtworkOwnerKey.CanonicalContent(requireNotNull(contentId))
            "preview" -> ArtworkOwnerKey.PreviewItem(
                itemKey = requireNotNull(itemKey),
                sourcePayloadHash = requireNotNull(sourcePayloadHash)
            )
            else -> error("Unknown owner type $type")
        }

        companion object {
            fun fromDomain(owner: ArtworkOwnerKey): OwnerDto = when (owner) {
                is ArtworkOwnerKey.CanonicalContent -> OwnerDto(
                    type = "canonical",
                    contentId = owner.contentId,
                    itemKey = null,
                    sourcePayloadHash = null
                )
                is ArtworkOwnerKey.PreviewItem -> OwnerDto(
                    type = "preview",
                    contentId = null,
                    itemKey = owner.itemKey,
                    sourcePayloadHash = owner.sourcePayloadHash
                )
            }
        }
    }

    private data class CandidateDto(
        @SerializedName("provider")
        val provider: ProviderDto?,
        @SerializedName("sourceRole")
        val sourceRole: String,
        @SerializedName("sourceHash")
        val sourceHash: String?,
        @SerializedName("redactedSourceForTrace")
        val redactedSourceForTrace: String?,
        @SerializedName("providerTemplate")
        val providerTemplate: TemplateDto?,
        @SerializedName("priority")
        val priority: Int
    ) {
        fun toDomain(): PersistedArtworkCandidate =
            PersistedArtworkCandidate(
                provider = provider?.toDomain(),
                sourceRole = ArtworkSourceRole.valueOf(sourceRole),
                sourceHash = sourceHash,
                redactedSourceForTrace = redactedSourceForTrace,
                providerTemplate = providerTemplate?.toDomain(),
                priority = priority
            )

        companion object {
            fun fromDomain(candidate: PersistedArtworkCandidate): CandidateDto =
                CandidateDto(
                    provider = candidate.provider?.let(ProviderDto::fromDomain),
                    sourceRole = candidate.sourceRole.name,
                    sourceHash = candidate.sourceHash,
                    redactedSourceForTrace = candidate.redactedSourceForTrace,
                    providerTemplate = candidate.providerTemplate?.let(TemplateDto::fromDomain),
                    priority = candidate.priority
                )
        }
    }

    private data class RejectedDto(
        @SerializedName("provider")
        val provider: ProviderDto?,
        @SerializedName("sourceRole")
        val sourceRole: String,
        @SerializedName("reason")
        val reason: String,
        @SerializedName("sourceHash")
        val sourceHash: String?,
        @SerializedName("redactedSourceForTrace")
        val redactedSourceForTrace: String?,
        @SerializedName("providerTemplate")
        val providerTemplate: TemplateDto?,
        @SerializedName("priority")
        val priority: Int
    ) {
        fun toDomain(): RejectedArtworkCandidate =
            RejectedArtworkCandidate(
                provider = provider?.toDomain(),
                sourceRole = ArtworkSourceRole.valueOf(sourceRole),
                reason = reason,
                sourceHash = sourceHash,
                redactedSourceForTrace = redactedSourceForTrace,
                providerTemplate = providerTemplate?.toDomain(),
                priority = priority
            )

        companion object {
            fun fromDomain(rejected: RejectedArtworkCandidate): RejectedDto =
                RejectedDto(
                    provider = rejected.provider?.let(ProviderDto::fromDomain),
                    sourceRole = rejected.sourceRole.name,
                    reason = rejected.reason,
                    sourceHash = rejected.sourceHash,
                    redactedSourceForTrace = rejected.redactedSourceForTrace,
                    providerTemplate = rejected.providerTemplate?.let(TemplateDto::fromDomain),
                    priority = rejected.priority
                )
        }
    }

    private data class TemplateDto(
        @SerializedName("provider")
        val provider: ProviderDto,
        @SerializedName("imageType")
        val imageType: String,
        @SerializedName("idType")
        val idType: String,
        @SerializedName("mediaId")
        val mediaId: String,
        @SerializedName("providerPathHash")
        val providerPathHash: String?,
        @SerializedName("settingsHash")
        val settingsHash: String?,
        @SerializedName("credentialHash")
        val credentialHash: String?,
        @SerializedName("imageLanguage")
        val imageLanguage: String,
        @SerializedName("policyVersion")
        val policyVersion: Int,
        @SerializedName("pathParams")
        val pathParams: Map<String, String>?
    ) {
        fun toDomain(): PersistedProviderTemplate =
            PersistedProviderTemplate(
                provider = provider.toDomain(),
                imageType = ArtworkType.valueOf(imageType),
                idType = idType,
                mediaId = mediaId,
                providerPathHash = providerPathHash,
                settingsHash = settingsHash,
                credentialHash = credentialHash,
                imageLanguage = imageLanguage,
                policyVersion = policyVersion,
                pathParams = pathParams.orEmpty()
            )

        companion object {
            fun fromDomain(template: PersistedProviderTemplate): TemplateDto =
                TemplateDto(
                    provider = ProviderDto.fromDomain(template.provider),
                    imageType = template.imageType.name,
                    idType = template.idType,
                    mediaId = template.mediaId,
                    providerPathHash = template.providerPathHash,
                    settingsHash = template.settingsHash,
                    credentialHash = template.credentialHash,
                    imageLanguage = template.imageLanguage,
                    policyVersion = template.policyVersion,
                    pathParams = template.pathParams
                )
        }
    }

    private data class ProviderDto(
        @SerializedName("type")
        val type: String,
        @SerializedName("integrationProvider")
        val integrationProvider: String?
    ) {
        fun toDomain(): ArtworkProviderId = when (type) {
            "runtime" -> ArtworkProviderId.RuntimeProvider(
                IntegrationProvider.valueOf(requireNotNull(integrationProvider))
            )
            "rail_preview" -> ArtworkProviderId.RailPreview
            "addon_preview" -> ArtworkProviderId.AddonPreview
            "placeholder" -> ArtworkProviderId.Placeholder
            else -> error("Unknown provider type $type")
        }

        companion object {
            fun fromDomain(provider: ArtworkProviderId): ProviderDto = when (provider) {
                is ArtworkProviderId.RuntimeProvider -> ProviderDto(
                    type = "runtime",
                    integrationProvider = provider.providerId.name
                )
                ArtworkProviderId.RailPreview -> ProviderDto(
                    type = "rail_preview",
                    integrationProvider = null
                )
                ArtworkProviderId.AddonPreview -> ProviderDto(
                    type = "addon_preview",
                    integrationProvider = null
                )
                ArtworkProviderId.Placeholder -> ProviderDto(
                    type = "placeholder",
                    integrationProvider = null
                )
            }
        }
    }

    private fun ArtworkDecision.hasSameDurablePayload(other: ArtworkDecision): Boolean =
        decisionKey == other.decisionKey &&
            ownerKey == other.ownerKey &&
            canonicalContentId == other.canonicalContentId &&
            imageType == other.imageType &&
            selectedCandidate == other.selectedCandidate &&
            rejectedCandidates == other.rejectedCandidates &&
            policyVersion == other.policyVersion &&
            imageLanguage == other.imageLanguage &&
            settingsHash == other.settingsHash &&
            credentialHash == other.credentialHash

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val LOGCAT_ONLY_TRACE_SESSION_ID = "logcat-only"
        private val FALLBACK_SOURCE_ROLES = setOf(
            ArtworkSourceRole.PRIMARY,
            ArtworkSourceRole.RAIL_PREVIEW,
            ArtworkSourceRole.ADDON_PREVIEW,
            ArtworkSourceRole.FALLBACK
        )
    }
}
