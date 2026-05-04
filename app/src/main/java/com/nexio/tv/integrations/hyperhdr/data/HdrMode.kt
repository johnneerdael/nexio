package com.nexio.tv.integrations.hyperhdr.data

/**
 * Controls how the capture path picks between SDR (NV12) and HDR (P010) wire formats.
 *
 * - [Auto] inspects ExoPlayer's Format.colorInfo at playback start and picks HDR_P010 for
 *   ST.2084 (HDR10 PQ) and HLG sources, SDR_NV12 otherwise.
 * - [ForceSdr] always sends NV12 regardless of source colorimetry. Useful when HyperHDR's
 *   P010 LUT isn't calibrated, or for diagnostic comparison.
 *
 * Force-HDR is intentionally not offered — sending P010 from a non-PQ source produces a
 * zeroed-out high-bit histogram that HyperHDR's LUT can't make sense of.
 */
enum class HdrMode { Auto, ForceSdr }
