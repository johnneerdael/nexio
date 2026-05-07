package com.nexio.tv.core.artwork

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.trace.RuntimeTraceSink
import com.nexio.tv.core.trace.TraceEventEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArtworkReferenceIntegrityValidatorTest {
    private val temp = TemporaryFolder().also { it.create() }

    @Test
    fun `decision ref is valid when decision exists`() {
        val decision = decision("decision-valid")
        val decisionCache = InMemoryArtworkDecisionCache()
        decisionCache.put(decision)
        val validator = validator(decisionCache = decisionCache)

        val result = validator.validate("nexio-artwork://decision/${decision.decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.ValidDecision(decision.decisionKey),
            result
        )
    }

    @Test
    fun `canonical decision ref is valid when generated content id contains colon segments`() {
        val ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523")
        val decisionKey = ArtworkCacheKeys.decisionKey(
            ownerKey = ownerKey,
            imageType = ArtworkType.POSTER,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            premiumEnabled = true,
            settingsHash = null,
            credentialHash = null,
            policyVersion = 1
        )
        val decision = decision("canonical-colon-content-id").copy(
            decisionKey = decisionKey,
            ownerKey = ownerKey,
            canonicalContentId = "imdb:tt0137523"
        )
        val decisionCache = InMemoryArtworkDecisionCache()
        decisionCache.put(decision)
        val validator = validator(decisionCache = decisionCache)

        val result = validator.validate("nexio-artwork://decision/${decision.decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.ValidDecision(decision.decisionKey),
            result
        )
    }

    @Test
    fun `preview decision ref is valid when generated item key contains colon segments`() {
        val decision = previewDecision(
            itemKey = "series:tmdb:1399",
            sourcePayloadHash = "payloadhash"
        )
        val decisionCache = InMemoryArtworkDecisionCache()
        decisionCache.put(decision)
        val validator = validator(decisionCache = decisionCache)

        val result = validator.validate("nexio-artwork://decision/${decision.decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.ValidDecision(decision.decisionKey),
            result
        )
    }

    @Test
    fun `missing decision recovers when indexed asset file has image bytes`() {
        val decision = providerTemplateDecision("recoverable-decision")
        val assetKey = ArtworkCacheKeys.assetKeyForProviderTemplate(
            decision.selectedCandidate.providerTemplate!!
        )
        val diskCache = ArtworkAssetDiskCache(temp.newFolder("recoverable"))
        val recordStore = RecordingArtworkAssetRecordStore()
        val record = writeAssetRecord(
            diskCache = diskCache,
            decision = decision,
            assetKey = assetKey
        )
        recordStore.put(record)
        val traceSink = RecordingTraceSink()
        val validator = validator(
            decisionCache = InMemoryArtworkDecisionCache(),
            assetRecordStore = recordStore,
            diskCache = diskCache,
            traceSink = traceSink
        )

        val result = validator.validate("nexio-artwork://decision/${decision.decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.RecoverableAssetForDecision(
                decision.decisionKey,
                record.assetKey
            ),
            result
        )
        val event = traceSink.events.single { it.eventType == "artwork.orphan_decision_ref_asset_recovered" }
        val payload = event.payload as Map<*, *>
        assertEquals(artworkDecisionShortSha256(decision.decisionKey.value), payload["decisionKeyHash"])
        assertEquals(artworkDecisionShortSha256(record.assetKey.value), payload["assetKeyHash"])
        assertFalse(event.payload.toString().contains(decision.decisionKey.value))
        assertFalse(event.payload.toString().contains(record.assetKey.value))
    }

    @Test
    fun `missing decision with noncanonical indexed asset is orphaned even when file has image bytes`() {
        val decision = decision("noncanonical-reverse-index-decision")
        val diskCache = ArtworkAssetDiskCache(temp.newFolder("noncanonical-reverse-index"))
        val recordStore = RecordingArtworkAssetRecordStore()
        val record = writeAssetRecord(
            diskCache = diskCache,
            decision = decision,
            assetKey = ArtworkAssetKey("artwork-asset:RPDB:poster:recoverable")
        )
        recordStore.put(record)
        val validator = validator(
            decisionCache = InMemoryArtworkDecisionCache(),
            assetRecordStore = recordStore,
            diskCache = diskCache
        )

        val result = validator.validate("nexio-artwork://decision/${decision.decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.OrphanedDecisionRef(
                decision.decisionKey,
                "missing_authoritative_no_asset"
            ),
            result
        )
    }

    @Test
    fun `missing decision without asset is orphaned and traceable`() {
        val decisionKey = decisionKey("orphan-decision")
        val traceSink = RecordingTraceSink()
        val validator = validator(
            decisionCache = InMemoryArtworkDecisionCache(),
            traceSink = traceSink
        )

        val result = validator.validate("nexio-artwork://decision/${decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.OrphanedDecisionRef(
                decisionKey,
                "missing_authoritative_no_asset"
            ),
            result
        )
        val event = traceSink.events.single { it.eventType == "artwork.orphan_decision_ref_found" }
        val payload = event.payload as Map<*, *>
        assertEquals(artworkDecisionShortSha256(decisionKey.value), payload["decisionKeyHash"])
        assertEquals("missing_authoritative_no_asset", payload["reason"])
        assertFalse(event.payload.toString().contains(decisionKey.value))
    }

    @Test
    fun `non authoritative decision cache returns unknown not orphaned`() {
        val decisionKey = decisionKey("non-authoritative-decision")
        val validator = validator(
            decisionCache = NonAuthoritativeArtworkDecisionCache()
        )

        val result = validator.validate("nexio-artwork://decision/${decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.UnknownDecisionRef(
                decisionKey,
                "decision_cache_not_authoritative"
            ),
            result
        )
    }

    @Test
    fun `decision lookup failure returns unknown not orphaned`() {
        val decisionKey = decisionKey("lookup-failed-decision")
        val validator = validator(
            decisionCache = ThrowingLookupArtworkDecisionCache()
        )

        val result = validator.validate("nexio-artwork://decision/${decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.UnknownDecisionRef(
                decisionKey,
                "lookup_failed"
            ),
            result
        )
    }

    @Test
    fun `decision cache throwing directly from lookup returns unknown and traces hashed error`() {
        val decisionKey = decisionKey("direct-lookup-failed-decision")
        val traceSink = RecordingTraceSink()
        val validator = validator(
            decisionCache = DirectThrowingLookupArtworkDecisionCache(),
            traceSink = traceSink
        )

        val result = validator.validate("nexio-artwork://decision/${decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.UnknownDecisionRef(
                decisionKey,
                "lookup_failed"
            ),
            result
        )
        val payload = traceSink.events
            .single { it.eventType == "artwork.ref_integrity_checked" }
            .payload as Map<*, *>
        assertEquals("decision", payload["refKind"])
        assertEquals(false, payload["valid"])
        assertEquals(artworkDecisionShortSha256(decisionKey.value), payload["decisionKeyHash"])
        assertEquals("lookup_failed", payload["reason"])
        assertEquals("IllegalStateException", payload["errorClass"])
        assertEquals(artworkDecisionShortSha256("decision lookup exploded"), payload["messageHash"])
        assertFalse(eventPayloads(traceSink).contains(decisionKey.value))
        assertFalse(eventPayloads(traceSink).contains("decision lookup exploded"))
    }

    @Test
    fun `asset ref is valid when record and file bytes exist`() {
        val decision = providerTemplateDecision("asset-valid-decision")
        val assetKey = ArtworkCacheKeys.assetKeyForProviderTemplate(
            decision.selectedCandidate.providerTemplate!!
        )
        val diskCache = ArtworkAssetDiskCache(temp.newFolder("asset-valid"))
        val recordStore = RecordingArtworkAssetRecordStore()
        val record = writeAssetRecord(
            diskCache = diskCache,
            decision = decision,
            assetKey = assetKey
        )
        recordStore.put(record)
        val traceSink = RecordingTraceSink()
        val validator = validator(
            assetRecordStore = recordStore,
            diskCache = diskCache,
            traceSink = traceSink
        )

        val result = validator.validate("nexio-artwork://asset/${record.assetKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.ValidAsset(record.assetKey),
            result
        )
        val payload = traceSink.events
            .single { it.eventType == "artwork.ref_integrity_checked" }
            .payload as Map<*, *>
        assertEquals("asset", payload["refKind"])
        assertEquals(true, payload["valid"])
    }

    @Test
    fun `provider template asset ref remains valid when record and file bytes exist`() {
        val decision = providerTemplateDecision("provider-template-valid-decision")
        val assetKey = providerTemplateAssetKey()
        val diskCache = ArtworkAssetDiskCache(temp.newFolder("provider-template-valid"))
        val recordStore = RecordingArtworkAssetRecordStore()
        val record = writeAssetRecord(
            diskCache = diskCache,
            decision = decision,
            assetKey = assetKey
        )
        recordStore.put(record)
        val validator = validator(
            assetRecordStore = recordStore,
            diskCache = diskCache
        )

        val result = validator.validate("nexio-artwork://asset/${assetKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.ValidAsset(assetKey),
            result
        )
    }

    @Test
    fun `provider template asset ref is invalid when image language is not canonical`() {
        val validAssetKey = providerTemplateAssetKey()
        val invalidAssetKey = ArtworkAssetKey(validAssetKey.value.replace(":imageLang:en", ":imageLang:fr"))

        val result = validateBackedAsset(invalidAssetKey)

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `provider template asset ref is invalid when settings and credential markers are swapped`() {
        val validAssetKey = providerTemplateAssetKey()
        val invalidAssetKey = ArtworkAssetKey(
            validAssetKey.value.replace(
                ":settings:settingshash:credential:credentialhash",
                ":credential:credentialhash:settings:settingshash"
            )
        )

        val result = validateBackedAsset(invalidAssetKey)

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `provider template asset ref is invalid when backed path param keys are unsorted`() {
        val validAssetKey = providerTemplateAssetKey(
            pathParams = mapOf(
                "season" to "1",
                "episode" to "2"
            )
        )
        val invalidAssetKey = ArtworkAssetKey(
            validAssetKey.value.replace(
                ":episode:2:season:1:settings:",
                ":season:1:episode:2:settings:"
            )
        )

        val result = validateBackedAsset(invalidAssetKey)

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `provider template asset ref is invalid when policy has trailing extra parts`() {
        val validAssetKey = providerTemplateAssetKey()
        val invalidAssetKey = ArtworkAssetKey("${validAssetKey.value}:extra")

        val result = validateBackedAsset(invalidAssetKey)

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `provider template asset ref is invalid when policy has leading zero even if record and file exist`() {
        val validAssetKey = providerTemplateAssetKey()
        val invalidAssetKey = ArtworkAssetKey(validAssetKey.value.replace(":policy:1", ":policy:01"))

        val result = validateBackedAsset(invalidAssetKey)

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `provider template asset ref accepts canonical negative policy when record and file exist`() {
        val assetKey = providerTemplateAssetKey(policyVersion = -1)

        val result = validateBackedAsset(assetKey)

        assertEquals(
            ArtworkReferenceIntegrityResult.ValidAsset(assetKey),
            result
        )
    }

    @Test
    fun `bare asset key without artwork URI prefix is unsupported`() {
        val result = validator().validate(providerTemplateAssetKey().value)

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("unsupported_artwork_ref"),
            result
        )
    }

    @Test
    fun `bare decision key without artwork URI prefix is unsupported`() {
        val result = validator().validate(decisionKey("decision-valid").value)

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("unsupported_artwork_ref"),
            result
        )
    }

    @Test
    fun `asset ref is invalid when record missing`() {
        val assetKey = providerTemplateAssetKey()
        val traceSink = RecordingTraceSink()
        val validator = validator(traceSink = traceSink)

        val result = validator.validate("nexio-artwork://asset/${assetKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("missing_or_unreadable_asset"),
            result
        )
        val payload = traceSink.events
            .single { it.eventType == "artwork.ref_integrity_checked" }
            .payload as Map<*, *>
        assertEquals("asset", payload["refKind"])
        assertEquals(false, payload["valid"])
    }

    @Test
    fun `asset ref is invalid when asset key is not generated artwork asset shape`() {
        val decision = decision("non-canonical-asset-key-decision")
        val diskCache = ArtworkAssetDiskCache(temp.newFolder("non-canonical-asset-key"))
        val recordStore = RecordingArtworkAssetRecordStore()
        val record = writeAssetRecord(
            diskCache = diskCache,
            decision = decision,
            assetKey = ArtworkAssetKey("not-a-generated-artwork-asset")
        )
        recordStore.put(record)
        val validator = validator(
            assetRecordStore = recordStore,
            diskCache = diskCache
        )

        val result = validator.validate("nexio-artwork://asset/not-a-generated-artwork-asset")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `asset ref is invalid when asset key is short ad hoc shape even if record and file exist`() {
        val decision = decision("short-ad-hoc-asset-key-decision")
        val diskCache = ArtworkAssetDiskCache(temp.newFolder("short-ad-hoc-asset-key"))
        val recordStore = RecordingArtworkAssetRecordStore()
        val assetKey = ArtworkAssetKey("artwork-asset:RPDB:poster:asset-valid")
        val record = writeAssetRecord(
            diskCache = diskCache,
            decision = decision,
            assetKey = assetKey
        )
        recordStore.put(record)
        val validator = validator(
            assetRecordStore = recordStore,
            diskCache = diskCache
        )

        val result = validator.validate("nexio-artwork://asset/${assetKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `asset ref disk readability exception returns invalid and traces hashed error`() {
        val assetKey = providerTemplateAssetKey(mediaId = "tt0000001")
        val recordStore = RecordingArtworkAssetRecordStore()
        recordStore.put(assetRecord(assetKey, decisionKey = ArtworkDecisionKey("unsafe-path-decision")))
        val traceSink = RecordingTraceSink()
        val validator = validator(
            assetRecordStore = recordStore,
            diskCache = ArtworkAssetDiskCache(temp.newFolder("unsafe-path")),
            traceSink = traceSink
        )

        val result = validator.validate("nexio-artwork://asset/${assetKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("asset_read_failed"),
            result
        )
        val payload = traceSink.events
            .single { it.eventType == "artwork.ref_integrity_checked" }
            .payload as Map<*, *>
        assertEquals(artworkDecisionShortSha256(assetKey.value), payload["assetKeyHash"])
        assertEquals("IOException", payload["errorClass"])
        assertEquals(artworkDecisionShortSha256("Invalid file path"), payload["messageHash"])
        assertFalse(eventPayloads(traceSink).contains(assetKey.value))
        assertFalse(eventPayloads(traceSink).contains("Invalid file path"))
    }

    @Test
    fun `asset record lookup exception returns invalid and traces hashed error`() {
        val assetKey = providerTemplateAssetKey(mediaId = "tt0000002")
        val traceSink = RecordingTraceSink()
        val validator = validator(
            assetRecordStore = ThrowingGetArtworkAssetRecordStore(),
            traceSink = traceSink
        )

        val result = validator.validate("nexio-artwork://asset/${assetKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("asset_lookup_failed"),
            result
        )
        val payload = traceSink.events
            .single { it.eventType == "artwork.ref_integrity_checked" }
            .payload as Map<*, *>
        assertEquals(artworkDecisionShortSha256(assetKey.value), payload["assetKeyHash"])
        assertEquals("IllegalStateException", payload["errorClass"])
        assertEquals(artworkDecisionShortSha256("record store get unavailable"), payload["messageHash"])
        assertFalse(eventPayloads(traceSink).contains(assetKey.value))
        assertFalse(eventPayloads(traceSink).contains("record store get unavailable"))
    }

    @Test
    fun `decision reverse index lookup failure returns orphaned and traces failure`() {
        val decisionKey = decisionKey("reverse-index-failed-decision")
        val traceSink = RecordingTraceSink()
        val validator = validator(
            decisionCache = InMemoryArtworkDecisionCache(),
            assetRecordStore = ThrowingReverseIndexArtworkAssetRecordStore(),
            traceSink = traceSink
        )

        val result = validator.validate("nexio-artwork://decision/${decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.OrphanedDecisionRef(
                decisionKey,
                "missing_authoritative_no_asset"
            ),
            result
        )
        val event = traceSink.events.single { it.eventType == "artwork.orphan_decision_ref_asset_lookup_failed" }
        val payload = event.payload as Map<*, *>
        assertEquals(artworkDecisionShortSha256(decisionKey.value), payload["decisionKeyHash"])
        assertEquals("IllegalStateException", payload["errorClass"])
        assertEquals(artworkDecisionShortSha256("record store unavailable"), payload["messageHash"])
        assertFalse(event.payload.toString().contains(decisionKey.value))
        assertFalse(event.payload.toString().contains("record store unavailable"))
    }

    @Test
    fun `decision reverse index asset read failure returns orphaned not unknown`() {
        val decisionKey = decisionKey("reverse-index-read-failed-decision")
        val assetKey = providerTemplateAssetKey(mediaId = "tt0000003")
        val recordStore = RecordingArtworkAssetRecordStore()
        recordStore.put(assetRecord(assetKey, decisionKey = decisionKey))
        val traceSink = RecordingTraceSink()
        val validator = validator(
            decisionCache = InMemoryArtworkDecisionCache(),
            assetRecordStore = recordStore,
            diskCache = ArtworkAssetDiskCache(temp.newFolder("reverse-index-read-failed")),
            traceSink = traceSink
        )

        val result = validator.validate("nexio-artwork://decision/${decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.OrphanedDecisionRef(
                decisionKey,
                "missing_authoritative_no_asset"
            ),
            result
        )
        val event = traceSink.events.single { it.eventType == "artwork.orphan_decision_ref_asset_read_failed" }
        val payload = event.payload as Map<*, *>
        assertEquals(artworkDecisionShortSha256(decisionKey.value), payload["decisionKeyHash"])
        assertEquals(artworkDecisionShortSha256(assetKey.value), payload["assetKeyHash"])
        assertEquals("IOException", payload["errorClass"])
        assertEquals(artworkDecisionShortSha256("Invalid file path"), payload["messageHash"])
        assertFalse(event.payload.toString().contains(decisionKey.value))
        assertFalse(event.payload.toString().contains(assetKey.value))
        assertFalse(event.payload.toString().contains("Invalid file path"))
    }

    @Test
    fun `ad hoc decision ref is invalid before cache lookup even when backed by cache decision`() {
        val decision = decision("ad-hoc-cache-backed-decision")
            .copy(decisionKey = ArtworkDecisionKey("foo"))
        val decisionCache = InMemoryArtworkDecisionCache()
        decisionCache.put(decision)
        val validator = validator(decisionCache = decisionCache)

        val result = validator.validate("nexio-artwork://decision/foo")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key"),
            result
        )
    }

    @Test
    fun `decision ref is invalid when image language is not canonical`() {
        val validDecisionKey = decisionKey("invalid-image-language-decision")
        val invalidDecisionKey = validDecisionKey.value.replace(":imageLang:en", ":imageLang:fr")

        val result = validator().validate("nexio-artwork://decision/$invalidDecisionKey")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key"),
            result
        )
    }

    @Test
    fun `decision ref is invalid when policy is not numeric`() {
        val validDecisionKey = decisionKey("invalid-policy-decision")
        val invalidDecisionKey = validDecisionKey.value.replace(":policy:1", ":policy:v1")

        val result = validator().validate("nexio-artwork://decision/$invalidDecisionKey")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key"),
            result
        )
    }

    @Test
    fun `decision ref is invalid when policy has leading zero even if decision is cached`() {
        val decision = decision("leading-zero-policy-decision")
        val invalidDecisionKey = ArtworkDecisionKey(decision.decisionKey.value.replace(":policy:1", ":policy:01"))
        val decisionCache = InMemoryArtworkDecisionCache()
        decisionCache.put(decision.copy(decisionKey = invalidDecisionKey))
        val validator = validator(decisionCache = decisionCache)

        val result = validator.validate("nexio-artwork://decision/${invalidDecisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key"),
            result
        )
    }

    @Test
    fun `decision ref accepts canonical negative policy when decision is cached`() {
        val decisionKey = ArtworkCacheKeys.decisionKey(
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb-negative-policy-decision"),
            imageType = ArtworkType.POSTER,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            premiumEnabled = true,
            settingsHash = null,
            credentialHash = null,
            policyVersion = -1
        )
        val decision = decision("negative-policy-decision").copy(decisionKey = decisionKey)
        val decisionCache = InMemoryArtworkDecisionCache()
        decisionCache.put(decision)
        val validator = validator(decisionCache = decisionCache)

        val result = validator.validate("nexio-artwork://decision/${decisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.ValidDecision(decisionKey),
            result
        )
    }

    @Test
    fun `decision ref is invalid before lookup when owner segment is malformed`() {
        val decisionCache = RecordingLookupArtworkDecisionCache()
        val validator = validator(decisionCache = decisionCache)

        val result = validator.validate(
            "nexio-artwork://decision/artwork-decision:poster:notCanonical:foo:provider:RPDB:premium:true:settings:none:credential:none:imageLang:en:policy:1"
        )

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key"),
            result
        )
        assertFalse(decisionCache.lookupCalled)
    }

    @Test
    fun `decision ref is invalid before lookup when generated owner has blank segments or malformed markers`() {
        val decisionCache = RecordingLookupArtworkDecisionCache()
        val validator = validator(decisionCache = decisionCache)
        val invalidDecisionKeys = listOf(
            "artwork-decision:poster:canonical:imdb::tt0137523:provider:RPDB:premium:true:settings:none:credential:none:imageLang:en:policy:1",
            "artwork-decision:poster:preview:series::payload:payloadhash:provider:RAIL_PREVIEW:premium:false:settings:none:credential:none:imageLang:en:policy:1",
            "artwork-decision:poster:preview:series:payload:payloadhash:extra:provider:RAIL_PREVIEW:premium:false:settings:none:credential:none:imageLang:en:policy:1"
        )

        invalidDecisionKeys.forEach { invalidDecisionKey ->
            val result = validator.validate("nexio-artwork://decision/$invalidDecisionKey")

            assertEquals(
                invalidDecisionKey,
                ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key"),
                result
            )
        }
        assertFalse(decisionCache.lookupCalled)
    }

    @Test
    fun `decision ref is invalid before lookup when generated variable segments have surrounding whitespace`() {
        val validCanonicalDecisionKey = ArtworkCacheKeys.decisionKey(
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb:tt0137523"),
            imageType = ArtworkType.POSTER,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            premiumEnabled = true,
            settingsHash = "settingshash",
            credentialHash = "credentialhash",
            policyVersion = 1
        ).value
        val validPreviewDecisionKey = previewDecision(
            itemKey = "series:tmdb:1399",
            sourcePayloadHash = "payloadhash"
        ).decisionKey.value
        val decisionCache = RecordingLookupArtworkDecisionCache()
        val validator = validator(decisionCache = decisionCache)
        val invalidDecisionKeys = listOf(
            validCanonicalDecisionKey.replace(":canonical:imdb:", ":canonical: imdb :"),
            validPreviewDecisionKey.replace(":payload:payloadhash:", ":payload: payloadhash :"),
            validCanonicalDecisionKey.replace(":provider:RPDB:", ":provider: RPDB :"),
            validCanonicalDecisionKey.replace(":settings:settingshash:", ":settings: settingshash :"),
            validCanonicalDecisionKey.replace(":credential:credentialhash:", ":credential: credentialhash :")
        )

        invalidDecisionKeys.forEach { invalidDecisionKey ->
            val result = validator.validate("nexio-artwork://decision/$invalidDecisionKey")

            assertEquals(
                invalidDecisionKey,
                ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key"),
                result
            )
        }
        assertFalse(decisionCache.lookupCalled)
    }

    @Test
    fun `decision ref is invalid before lookup when provider segment is unknown`() {
        val validDecisionKey = decisionKey("invalid-provider-decision")
        val invalidDecisionKey = validDecisionKey.value.replace(":provider:RPDB:", ":provider:NOT_A_PROVIDER:")
        val decisionCache = RecordingLookupArtworkDecisionCache()
        val validator = validator(decisionCache = decisionCache)

        val result = validator.validate("nexio-artwork://decision/$invalidDecisionKey")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key"),
            result
        )
        assertFalse(decisionCache.lookupCalled)
    }

    @Test
    fun `asset refs with invalid generated canonical shapes are rejected`() {
        val validProviderTemplate = providerTemplateAssetKey().value
        val invalidRefs = listOf(
            validProviderTemplate.replace(":settings:settingshash", ""),
            validProviderTemplate.replace(":credential:credentialhash", ""),
            "artwork-asset:RPDB:poster:urlHash:urlhash:imageLang:en:policy:1",
            validProviderTemplate.replace(":policy:1", ":policy:v1"),
            validProviderTemplate.replace(":imageLang:en", ":imageLang:")
        )
        val validator = validator()

        invalidRefs.forEach { invalidRef ->
            val result = validator.validate("nexio-artwork://asset/$invalidRef")

            assertEquals(
                invalidRef,
                ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
                result
            )
        }
    }

    @Test
    fun `asset ref is invalid when generated variable segments have surrounding whitespace even if record and file exist`() {
        val validProviderTemplate = providerTemplateAssetKey(
            pathParams = mapOf(
                "episode" to "2",
                "season" to "1"
            )
        ).value
        val validRemoteUrl = ArtworkCacheKeys.assetKeyForRemoteUrl(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
            imageType = ArtworkType.POSTER,
            normalizedUrlHash = "normalizedurlhash",
            variant = "w500",
            policyVersion = 1
        ).value
        val invalidAssetKeys = listOf(
            ArtworkAssetKey(validProviderTemplate.replace("artwork-asset:RPDB:", "artwork-asset: RPDB :")),
            ArtworkAssetKey(validProviderTemplate.replace(":imdb:", ": imdb :")),
            ArtworkAssetKey(validProviderTemplate.replace(":tt0137523:", ": tt0137523 :")),
            ArtworkAssetKey(validProviderTemplate.replace(":episode:2:", ": episode :2:")),
            ArtworkAssetKey(validProviderTemplate.replace(":episode:2:", ":episode: 2 :")),
            ArtworkAssetKey(validProviderTemplate.replace(":settings:settingshash:", ":settings: settingshash :")),
            ArtworkAssetKey(validProviderTemplate.replace(":credential:credentialhash:", ":credential: credentialhash :")),
            ArtworkAssetKey(validRemoteUrl.replace(":urlHash:normalizedurlhash:", ":urlHash: normalizedurlhash :")),
            ArtworkAssetKey(validRemoteUrl.replace(":variant:w500:", ":variant: w500 :"))
        )

        invalidAssetKeys.forEach { invalidAssetKey ->
            val result = validateBackedAsset(invalidAssetKey)

            assertEquals(
                invalidAssetKey.value,
                ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
                result
            )
        }
    }

    @Test
    fun `asset ref is invalid when provider segment is unknown even if record and file exist`() {
        val validAssetKey = providerTemplateAssetKey()
        val invalidAssetKey = ArtworkAssetKey(validAssetKey.value.replace("artwork-asset:RPDB:", "artwork-asset:NOT_A_PROVIDER:"))

        val result = validateBackedAsset(invalidAssetKey)

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `asset ref is invalid when provider segment is a non-artwork integration provider even if record and file exist`() {
        val validAssetKey = providerTemplateAssetKey()
        val invalidAssetKey = ArtworkAssetKey(
            validAssetKey.value.replace("artwork-asset:RPDB:", "artwork-asset:OPEN_SUBTITLES:")
        )

        val result = validateBackedAsset(invalidAssetKey)

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `asset ref is invalid when provider segment is raw addon even if record and file exist`() {
        val validAssetKey = providerTemplateAssetKey()
        val invalidAssetKey = ArtworkAssetKey(validAssetKey.value.replace("artwork-asset:RPDB:", "artwork-asset:ADDON:"))

        val result = validateBackedAsset(invalidAssetKey)

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `decision ref is invalid when provider segment is raw addon even if decision is cached`() {
        val validDecision = decision("raw-addon-provider-decision")
        val invalidDecisionKey = ArtworkDecisionKey(
            validDecision.decisionKey.value.replace(":provider:RPDB:", ":provider:ADDON:")
        )
        val decisionCache = InMemoryArtworkDecisionCache()
        decisionCache.put(validDecision.copy(decisionKey = invalidDecisionKey))
        val validator = validator(decisionCache = decisionCache)

        val result = validator.validate("nexio-artwork://decision/${invalidDecisionKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key"),
            result
        )
    }

    @Test
    fun `malformed asset URI is invalid before asset lookup`() {
        val validator = validator(assetRecordStore = ThrowingGetArtworkAssetRecordStore())

        val result = validator.validate("nexio-artwork://asset")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `malformed decision URI is invalid before decision lookup`() {
        val decisionCache = RecordingLookupArtworkDecisionCache()
        val validator = validator(decisionCache = decisionCache)

        val result = validator.validate("nexio-artwork://decision")

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_decision_key"),
            result
        )
        assertFalse(decisionCache.lookupCalled)
    }

    @Test
    fun `malformed artwork root is invalid before cache or store lookup`() {
        val decisionCache = RecordingLookupArtworkDecisionCache()
        val validator = validator(
            decisionCache = decisionCache,
            assetRecordStore = ThrowingGetArtworkAssetRecordStore()
        )

        listOf(
            "nexio-artwork:/decision/${decisionKey("malformed-single-slash").value}",
            "nexio-artwork:asset/${providerTemplateAssetKey().value}",
            "nexio-artwork:",
            "nexio-artwork://foo/artwork-decision:poster:canonical:imdb:tt0137523",
            "nexio-artwork://assetx/${providerTemplateAssetKey().value}"
        ).forEach { ref ->
            val result = validator.validate(ref)

            assertEquals(
                ref,
                ArtworkReferenceIntegrityResult.Invalid("invalid_artwork_key_ref"),
                result
            )
        }
        assertFalse(decisionCache.lookupCalled)
    }

    @Test
    fun `noop validator treats malformed artwork root as invalid artwork key ref`() {
        listOf(
            "nexio-artwork:/decision/${decisionKey("noop-malformed-single-slash").value}",
            "nexio-artwork:asset/${providerTemplateAssetKey().value}",
            "nexio-artwork:",
            "nexio-artwork://foo/artwork-decision:poster:canonical:imdb:tt0137523"
        ).forEach { ref ->
            val result = NoopArtworkReferenceIntegrityValidator.validate(ref)

            assertEquals(
                ref,
                ArtworkReferenceIntegrityResult.Invalid("invalid_artwork_key_ref"),
                result
            )
        }
    }

    @Test
    fun `remote url asset ref is valid when record and file bytes exist`() {
        val decision = decision("remote-url-asset-valid-decision")
        val assetKey = ArtworkCacheKeys.assetKeyForRemoteUrl(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.TMDB),
            imageType = ArtworkType.POSTER,
            normalizedUrlHash = "normalizedurlhash",
            variant = "w500",
            policyVersion = 1
        )
        val diskCache = ArtworkAssetDiskCache(temp.newFolder("remote-url-asset-valid"))
        val recordStore = RecordingArtworkAssetRecordStore()
        val record = writeAssetRecord(
            diskCache = diskCache,
            decision = decision,
            assetKey = assetKey
        )
        recordStore.put(record)
        val validator = validator(
            assetRecordStore = recordStore,
            diskCache = diskCache
        )

        val result = validator.validate("nexio-artwork://asset/${assetKey.value}")

        assertEquals(
            ArtworkReferenceIntegrityResult.ValidAsset(assetKey),
            result
        )
    }

    @Test
    fun `provider template asset ref is invalid when backed path param keys are duplicated`() {
        val validAssetKey = providerTemplateAssetKey(
            pathParams = mapOf(
                "episode" to "2",
                "season" to "1"
            )
        )
        val invalidAssetKey = ArtworkAssetKey(
            validAssetKey.value.replace(
                ":episode:2:season:1:settings:",
                ":episode:2:episode:3:season:1:settings:"
            )
        )

        val result = validateBackedAsset(invalidAssetKey)

        assertEquals(
            ArtworkReferenceIntegrityResult.Invalid("invalid_asset_key"),
            result
        )
    }

    @Test
    fun `blank and null refs are Empty`() {
        val validator = validator()

        assertEquals(ArtworkReferenceIntegrityResult.Empty, validator.validate(null))
        assertEquals(ArtworkReferenceIntegrityResult.Empty, validator.validate(""))
        assertEquals(ArtworkReferenceIntegrityResult.Empty, validator.validate("   "))
    }

    private fun validator(
        decisionCache: ArtworkDecisionCache = InMemoryArtworkDecisionCache(),
        assetRecordStore: ArtworkAssetRecordStore = RecordingArtworkAssetRecordStore(),
        diskCache: ArtworkAssetDiskCache = ArtworkAssetDiskCache(temp.newFolder()),
        traceSink: RuntimeTraceSink = RecordingTraceSink()
    ): ArtworkReferenceIntegrityValidator =
        DefaultArtworkReferenceIntegrityValidator(
            decisionCache = decisionCache,
            assetRecordStore = assetRecordStore,
            diskCache = diskCache,
            traceSink = traceSink
        )

    private fun writeAssetRecord(
        diskCache: ArtworkAssetDiskCache,
        decision: ArtworkDecision,
        assetKey: ArtworkAssetKey
    ): ArtworkAssetRecord {
        val record = diskCache.recordFor(
            assetKey = assetKey,
            decision = decision,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            sourceHash = "source-hash",
            mimeType = "image/jpeg",
            byteCount = 4L,
            fetchedAtMs = 1_000L
        )
        return diskCache.write(
            record,
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01)
        ).record
    }

    private fun validateBackedAsset(assetKey: ArtworkAssetKey): ArtworkReferenceIntegrityResult {
        val diskCache = ArtworkAssetDiskCache(temp.newFolder())
        val recordStore = RecordingArtworkAssetRecordStore()
        val record = writeAssetRecord(
            diskCache = diskCache,
            decision = providerTemplateDecision("backed-invalid-asset-decision"),
            assetKey = assetKey
        )
        recordStore.put(record)
        return validator(
            assetRecordStore = recordStore,
            diskCache = diskCache
        ).validate("nexio-artwork://asset/${assetKey.value}")
    }

    private fun assetRecord(
        assetKey: ArtworkAssetKey,
        decisionKey: ArtworkDecisionKey?
    ): ArtworkAssetRecord =
        ArtworkAssetRecord(
            assetKey = assetKey,
            decisionKey = decisionKey,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            imageType = ArtworkType.POSTER,
            relativePath = "\u0000",
            mimeType = "image/jpeg",
            byteCount = 4L,
            sourceHash = "source-hash",
            policyVersion = 1,
            fetchedAtMs = 1_000L,
            expiresAtMs = 2_000L,
            staleUntilMs = 3_000L
        )

    private fun eventPayloads(traceSink: RecordingTraceSink): String =
        traceSink.events.joinToString(separator = "\n") { it.payload.toString() }

    private fun providerTemplateAssetKey(
        mediaId: String = "tt0137523",
        pathParams: Map<String, String> = emptyMap(),
        policyVersion: Int = 1
    ): ArtworkAssetKey =
        ArtworkCacheKeys.assetKeyForProviderTemplate(
            providerTemplate(
                mediaId = mediaId,
                pathParams = pathParams,
                policyVersion = policyVersion
            )
        )

    private fun decisionKey(key: String): ArtworkDecisionKey =
        ArtworkCacheKeys.decisionKey(
            ownerKey = ArtworkOwnerKey.CanonicalContent("imdb-$key"),
            imageType = ArtworkType.POSTER,
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            premiumEnabled = true,
            settingsHash = null,
            credentialHash = null,
            policyVersion = 1
        )

    private class RecordingArtworkAssetRecordStore : ArtworkAssetRecordStore {
        private val records = linkedMapOf<ArtworkAssetKey, ArtworkAssetRecord>()

        override fun put(record: ArtworkAssetRecord) {
            records[record.assetKey] = record
        }

        override fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord? =
            records[assetKey]

        override fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord? =
            records.values
                .filter { it.decisionKey == decisionKey }
                .maxByOrNull { it.fetchedAtMs }
    }

    private class ThrowingGetArtworkAssetRecordStore : ArtworkAssetRecordStore {
        override fun put(record: ArtworkAssetRecord) = Unit

        override fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord? {
            error("record store get unavailable")
        }

        override fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord? = null
    }

    private class ThrowingReverseIndexArtworkAssetRecordStore : ArtworkAssetRecordStore {
        override fun put(record: ArtworkAssetRecord) = Unit

        override fun get(assetKey: ArtworkAssetKey): ArtworkAssetRecord? = null

        override fun findLatestAssetForDecision(decisionKey: ArtworkDecisionKey): ArtworkAssetRecord? {
            error("record store unavailable")
        }
    }

    private class RecordingLookupArtworkDecisionCache : ArtworkDecisionCache {
        var lookupCalled = false

        override fun get(key: ArtworkDecisionKey): ArtworkDecision? = null

        override fun lookup(
            key: ArtworkDecisionKey,
            requiredContext: ArtworkDecisionAuthorityContext?
        ): ArtworkDecisionLookupResult {
            lookupCalled = true
            error("decision lookup should not be called")
        }

        override fun put(decision: ArtworkDecision) = Unit

        override fun remove(key: ArtworkDecisionKey) = Unit

        override fun linkPreviewToCanonical(
            previewKey: ArtworkDecisionKey,
            canonicalKey: ArtworkDecisionKey
        ) = Unit

        override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = null

        override fun invalidateBySettingsHash(settingsHash: String) = Unit

        override fun invalidateByCredentialHash(credentialHash: String) = Unit

        override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) = Unit

        override fun invalidatePremiumArtworkPolicy() = Unit
    }

    private class NonAuthoritativeArtworkDecisionCache : ArtworkDecisionCache {
        override fun get(key: ArtworkDecisionKey): ArtworkDecision? = null

        override fun loadState(): ArtworkDecisionStoreLoadState =
            ArtworkDecisionStoreLoadState.NotLoaded

        override fun put(decision: ArtworkDecision) = Unit

        override fun remove(key: ArtworkDecisionKey) = Unit

        override fun linkPreviewToCanonical(
            previewKey: ArtworkDecisionKey,
            canonicalKey: ArtworkDecisionKey
        ) = Unit

        override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = null

        override fun invalidateBySettingsHash(settingsHash: String) = Unit

        override fun invalidateByCredentialHash(credentialHash: String) = Unit

        override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) = Unit

        override fun invalidatePremiumArtworkPolicy() = Unit
    }

    private class ThrowingLookupArtworkDecisionCache : ArtworkDecisionCache {
        override fun get(key: ArtworkDecisionKey): ArtworkDecision? {
            error("decision cache lookup unavailable")
        }

        override fun put(decision: ArtworkDecision) = Unit

        override fun remove(key: ArtworkDecisionKey) = Unit

        override fun linkPreviewToCanonical(
            previewKey: ArtworkDecisionKey,
            canonicalKey: ArtworkDecisionKey
        ) = Unit

        override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = null

        override fun invalidateBySettingsHash(settingsHash: String) = Unit

        override fun invalidateByCredentialHash(credentialHash: String) = Unit

        override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) = Unit

        override fun invalidatePremiumArtworkPolicy() = Unit
    }

    private class DirectThrowingLookupArtworkDecisionCache : ArtworkDecisionCache {
        override fun get(key: ArtworkDecisionKey): ArtworkDecision? = null

        override fun lookup(
            key: ArtworkDecisionKey,
            requiredContext: ArtworkDecisionAuthorityContext?
        ): ArtworkDecisionLookupResult {
            error("decision lookup exploded")
        }

        override fun put(decision: ArtworkDecision) = Unit

        override fun remove(key: ArtworkDecisionKey) = Unit

        override fun linkPreviewToCanonical(
            previewKey: ArtworkDecisionKey,
            canonicalKey: ArtworkDecisionKey
        ) = Unit

        override fun getCanonicalForPreview(previewKey: ArtworkDecisionKey): ArtworkDecision? = null

        override fun invalidateBySettingsHash(settingsHash: String) = Unit

        override fun invalidateByCredentialHash(credentialHash: String) = Unit

        override fun invalidateArtworkPolicy(settingsHashes: Set<String>, credentialHashes: Set<String>) = Unit

        override fun invalidatePremiumArtworkPolicy() = Unit
    }

    private class RecordingTraceSink : RuntimeTraceSink {
        val events = mutableListOf<TraceEventEnvelope<*>>()

        override fun emit(event: TraceEventEnvelope<*>) {
            events += event
        }

        override fun eventsWritten(): Long = events.size.toLong()

        override fun eventsDropped(): Long = 0L
    }

    private fun decision(key: String): ArtworkDecision {
        val contentId = "imdb-$key"
        return ArtworkDecision(
            decisionKey = decisionKey(key),
            ownerKey = ArtworkOwnerKey.CanonicalContent(contentId),
            canonicalContentId = contentId,
            imageType = ArtworkType.POSTER,
            selectedCandidate = PersistedArtworkCandidate(
                provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
                sourceRole = ArtworkSourceRole.PREMIUM,
                sourceHash = "source-hash",
                redactedSourceForTrace = null,
                providerTemplate = null,
                priority = 10
            ),
            rejectedCandidates = emptyList(),
            policyVersion = 1,
            settingsHash = null,
            credentialHash = null,
            createdAtMs = 1_000L,
            expiresAtMs = 2_000L,
            staleUntilMs = 3_000L
        )
    }

    private fun previewDecision(
        itemKey: String,
        sourcePayloadHash: String
    ): ArtworkDecision =
        decision("preview-decision").copy(
            decisionKey = ArtworkCacheKeys.decisionKey(
                ownerKey = ArtworkOwnerKey.PreviewItem(itemKey, sourcePayloadHash),
                imageType = ArtworkType.POSTER,
                provider = ArtworkProviderId.RailPreview,
                premiumEnabled = false,
                settingsHash = null,
                credentialHash = null,
                policyVersion = 1
            ),
            ownerKey = ArtworkOwnerKey.PreviewItem(itemKey, sourcePayloadHash),
            canonicalContentId = null
        )

    private fun providerTemplateDecision(key: String): ArtworkDecision {
        val template = providerTemplate()
        return decision(key).copy(
            selectedCandidate = PersistedArtworkCandidate(
                provider = template.provider,
                sourceRole = ArtworkSourceRole.PREMIUM,
                sourceHash = "source-hash",
                redactedSourceForTrace = null,
                providerTemplate = template,
                priority = 10
            )
        )
    }

    private fun providerTemplate(
        mediaId: String = "tt0137523",
        pathParams: Map<String, String> = emptyMap(),
        policyVersion: Int = 1
    ): PersistedProviderTemplate =
        PersistedProviderTemplate(
            provider = ArtworkProviderId.RuntimeProvider(IntegrationProvider.RPDB),
            imageType = ArtworkType.POSTER,
            idType = "imdb",
            mediaId = mediaId,
            providerPathHash = "pathhash",
            settingsHash = "settingshash",
            credentialHash = "credentialhash",
            policyVersion = policyVersion,
            pathParams = pathParams
        )
}
