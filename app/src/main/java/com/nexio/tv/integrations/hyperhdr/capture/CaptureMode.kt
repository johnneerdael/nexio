package com.nexio.tv.integrations.hyperhdr.capture

/**
 * Wire-format choice for a single capture session. Decided once at playback start by
 * [FormatDetector] and held constant for the duration of a Format. If the playback's
 * active video Format changes (track switch), the lifecycle wiring re-detects.
 */
enum class CaptureMode { SDR_NV12, HDR_P010 }
