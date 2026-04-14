#ifndef NEXIO_ASS_DIRECT_H
#define NEXIO_ASS_DIRECT_H

#include <stdint.h>

typedef struct AssDirectContext AssDirectContext;

/**
 * Create a new libass-only render context (no FFmpeg).
 */
AssDirectContext *ass_direct_init(int width, int height, float font_scale);

/**
 * Load the ASS header (codec private data from ExoPlayer's Format).
 * This contains [Script Info], [V4+ Styles], etc.
 */
int ass_direct_load_header(AssDirectContext *ctx, const char *header_data, int header_size);

/**
 * Add a font attachment.
 */
void ass_direct_add_font(AssDirectContext *ctx, const char *name,
                         const char *data, int data_size);

/**
 * Process a subtitle chunk (dialogue line from ExoPlayer).
 * @param data    The raw ASS dialogue text.
 * @param size    Size of data.
 * @param start_ms Start time in milliseconds.
 * @param duration_ms Duration in milliseconds.
 */
void ass_direct_process_chunk(AssDirectContext *ctx, const char *data, int size,
                              int64_t start_ms, int64_t duration_ms);

/**
 * Render the subtitle frame at the given timestamp.
 * @param out_pixels Output buffer (RGBA, width*height*4 bytes).
 * @return 1 if content was rendered, 0 if blank.
 */
int ass_direct_render(AssDirectContext *ctx, int64_t time_ms, uint8_t *out_pixels);

/**
 * Process raw ASS data (full dialogue lines with embedded timing).
 */
void ass_direct_process_data(AssDirectContext *ctx, const char *data, int size);

/**
 * Flush all events (e.g. on seek or track change).
 */
void ass_direct_flush(AssDirectContext *ctx);

/**
 * Destroy and free all resources.
 */
void ass_direct_destroy(AssDirectContext *ctx);

#endif // NEXIO_ASS_DIRECT_H
