package com.nexio.tv.domain.model

/**
 * Identity of a TorBox library file being played. Parsed by the Player out of route args.
 *
 * The Player tunnels TorBox context through the existing `videoId` + `launchSource` + `filename`
 * route parameters rather than introducing dedicated nav args. `videoId` follows the format
 * `mapTorBoxItem` already emits: `tb:torrent:{torrentId}:file:{fileId}`.
 */
data class TorBoxPlaybackContext(
    val torrentId: Int,
    val fileId: Int,
    val fileName: String,
) {
    companion object {
        private val ID_PATTERN = Regex("""^tb:torrent:(\d+):file:(\d+)$""")

        /**
         * Returns a [TorBoxPlaybackContext] iff the route originated from the TorBox library tab
         * (`launchSource == "torbox"`) and the encoded video id parses cleanly.
         */
        fun fromRouteArgs(
            launchSource: String?,
            videoId: String?,
            filename: String?,
        ): TorBoxPlaybackContext? {
            if (launchSource != "torbox") return null
            val match = ID_PATTERN.matchEntire(videoId.orEmpty()) ?: return null
            val name = filename?.takeIf { it.isNotBlank() } ?: return null
            return TorBoxPlaybackContext(
                torrentId = match.groupValues[1].toInt(),
                fileId = match.groupValues[2].toInt(),
                fileName = name,
            )
        }
    }
}
