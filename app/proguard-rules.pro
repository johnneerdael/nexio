# Add project specific ProGuard rules here.

# ── Moshi ──────────────────────────────────────────────────────────────────────
# Keep Moshi-generated JsonAdapter classes
-keep class com.squareup.moshi.** { *; }
-keep class **JsonAdapter { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
# Keep @JsonClass-annotated classes and their generated adapters
-keepclasseswithmembers class * {
    @com.squareup.moshi.JsonClass <init>(...);
}

# ── Gson ───────────────────────────────────────────────────────────────────────
# Keep TypeToken generic signatures (used by local config serializers)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── Retrofit ───────────────────────────────────────────────────────────────────
# Keep generic signatures for Retrofit service methods
-keepattributes Signature
# Keep Retrofit service interfaces (must preserve generic return types)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Keep all project API interfaces
-keep class com.nexio.tv.data.remote.api.** { *; }

# ── OkHttp ─────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Data classes (DTOs) ────────────────────────────────────────────────────────
# Keep all DTO classes used with Moshi/Retrofit
-keep class com.nexio.tv.data.remote.dto.** { *; }
-keep class com.nexio.tv.domain.model.** { *; }
-keep class com.nexio.tv.core.tmdb.TmdbEnrichment { *; }
-keep class com.nexio.tv.core.tvdb.TvMetadataEnrichment { *; }
-keep class com.nexio.tv.core.tvdb.TvEpisodeMetadata { *; }
-keep class com.nexio.tv.core.tvdb.TvdbSeriesIdentity { *; }
-keep class com.nexio.tv.core.tvdb.TvdbRemoteIdSource { *; }
-keep class com.nexio.tv.core.tvdb.TvdbAirAvailabilityPrecision { *; }
-keep class com.nexio.tv.core.tvdb.TvdbAirAvailabilityDiagnosticReason { *; }
# Keep local snapshot payload models serialized with Gson to survive restarts/updates.
-keep class com.nexio.tv.data.local.HomeCatalogSnapshotStore$Snapshot { *; }
-keep class com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope { *; }
-keep class com.nexio.tv.data.trakt.outbox.TraktMutationOutboxSnapshot { *; }
-keep class com.nexio.tv.data.trakt.outbox.TraktMutationPriorityBucket { *; }
-keep class com.nexio.tv.data.trakt.outbox.TraktMutationLifecycleState { *; }
-keep class com.nexio.tv.data.repository.ContinueWatchingSnapshotService$EpisodeRollbackState { *; }
-keep class com.nexio.tv.data.repository.TrackingNextUpEntry { *; }
-keep class com.nexio.tv.data.repository.TraktLibraryService$LibraryRollbackState { *; }
-keep class com.nexio.tv.data.repository.SimklLibraryService$LibraryRollbackState { *; }
-keep class com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController$Snapshot { *; }
-keep class com.nexio.tv.data.repository.TraktDiscoverySnapshot { *; }
-keep class com.nexio.tv.data.repository.TraktCustomListCatalog { *; }
-keep class com.nexio.tv.data.repository.TraktPopularListOption { *; }
-keep class com.nexio.tv.data.repository.TraktRecommendationRef { *; }
-keep class com.nexio.tv.data.repository.MDBListDiscoverySnapshot { *; }
-keep class com.nexio.tv.data.repository.MDBListCustomCatalog { *; }
-keep class com.nexio.tv.data.repository.MDBListListOption { *; }

# ── Kotlin ─────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep Kotlin Metadata for reflection
-keepattributes RuntimeVisibleAnnotations

# ── NanoHTTPD (used by local server) ───────────────────────────────────────────
-keep class fi.iki.elonen.** { *; }
# Keep server classes and their inner data classes (serialized with Gson)
-keep class com.nexio.tv.core.server.** { *; }

#── QuickJS ────────────────────────────────────────────────────────────────────
# Keep quickjs-kt library classes for proper type conversion
-keep class com.dokar.quickjs.** { *; }
-keepclassmembers class com.dokar.quickjs.** { *; }
# Keep PluginRuntime and related classes for JS bindings
-keep class com.nexio.tv.core.plugin.** { *; }
-keepclassmembers class com.nexio.tv.core.plugin.** { *; }

# Keep ASS/SSA JNI bridge entry points available to native libass rendering.
-keep class com.nexio.tv.ui.screens.player.ass.AssSsaNativeBridge { *; }

# ── ExoPlayer / Media3 ────────────────────────────────────────────────────────
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keep class androidx.media.** { *; }
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.ui.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }
-keep class com.google.android.exoplayer2.ext.** { *; }

# ── Supabase / Ktor / Kotlinx Serialization ───────────────────────────────────
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class com.nexio.tv.data.remote.supabase.** { *; }
# Keep @Serializable classes and their generated serializers
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Playback instrumentation ──────────────────────────────────────────────────
# Keep tracer surface so R8 does not strip the @JvmField enabled flag or hot-path inline targets
-keep class com.nexio.tv.instrumentation.** { *; }
-keepclassmembers class com.nexio.tv.instrumentation.PlaybackTracer {
    public static boolean enabled;
}
# JCTools queue internals use reflective/unsafe field-offset lookups on concrete queue fields.
# Obfuscating or stripping those internals breaks SessionWriter startup in release builds.
-keep class org.jctools.** { *; }
# JCTools ships optional OSGi package annotations in package-info.class; they are not needed
# on Android runtime and should not fail release shrinking.
-dontwarn org.osgi.annotation.bundle.**

# ── Compose UI: AndroidLayoutApi34 (API 34 helper) ────────────────────────────
# Keep the API 34 text-layout helper isolated so its TextInclusionStrategy
# synthetic lambda is not inlined onto Android <14 verifier load paths.
-keep,allowoptimization,allowshrinking class androidx.compose.ui.text.android.AndroidLayoutApi34
-keep,allowoptimization,allowshrinking class androidx.compose.ui.text.android.AndroidLayoutApi34$*
-keepclassmembers class androidx.compose.ui.text.android.AndroidLayoutApi34 { *; }

# ── General ────────────────────────────────────────────────────────────────────
# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
